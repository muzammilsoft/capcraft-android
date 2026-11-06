// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! A video file written by Android's own MediaCodec, through the NDK's C
//! API (libmediandk) - the Kinemaster route: no FFmpeg between the frames
//! and the chip.
//!
//! Phase 3 (2026-11-06). The 2026-11-06 device log proved FFmpeg's
//! `h264_mediacodec` stalls on this phone: hisi takes 4 frames, c2 takes
//! 5, each then sits quiet for the whole stall timeout, while preview
//! plays smoothly. The native path talks to the same components
//! directly - `AMediaCodec_createCodecByName("OMX.hisi.video.encoder.avc")`
//! and the HEVC one - and muxes with `AMediaMuxer`, so a stall in
//! FFmpeg's wrapper cannot be what stops the export. Whether the chip
//! itself stalls the same way is what the device test in 0.0.12-beta
//! answers; the log lines say which encoder ran and how far it got.
//!
//! ByteBuffer input, NOT Surface input: the compositor hands back RGBA
//! pixels, so frames go in as YUV420 buffers. Surface input would need
//! the GPU to draw into the encoder's surface, which this compositor
//! does not do - said here honestly rather than claimed. The frames are
//! converted RGBA to YUV420P with the same swscale the FFmpeg encoder
//! uses, then copied into the input buffer in the layout the codec
//! reports (planar or semi-planar, with its own stride and slice
//! height), so a codec that wants aligned rows gets them.
//!
//! MPEG-4 stays the fallback: any failure here - the component will
//! not open, the colour layout is one this file does not write, a
//! stall - ends this attempt and the export goes on with it. The
//! native encoder is SDR eight-bit only: an HDR timeline never reaches
//! it (the export plan routes HDR to the classic encoders instead).

use std::ffi::CString;
use std::os::fd::AsRawFd;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use concat_core::frame::{Depth, Frame};
use ffmpeg_the_third::format::Pixel;
use ffmpeg_the_third::software::scaling;
use ffmpeg_the_third::util::frame::video::Video;

use crate::encode::{FrameSink, NativeKind};
use crate::error::{Error, Result};
use crate::ffi;

/// How long a native encoder may take no input buffer, or hand back no
/// output after the end of the stream, before the export calls it
/// stuck: the same three seconds the FFmpeg encoders get (Phase 3),
/// so a stalled chip costs three seconds, not twenty.
const STALL_PATIENCE: Duration = Duration::from_secs(3);

/// The pause between two asks for an input buffer, so a busy chip is
/// waited for rather than spun on.
const ASK_WAIT: Duration = Duration::from_millis(2);

/// Android's `COLOR_FormatYUV420Planar` (0x13): Y, then U, then V.
const COLOR_YUV420_PLANAR: i32 = 19;
/// Android's `COLOR_FormatYUV420SemiPlanar` (0x15): Y, then UV interleaved.
const COLOR_YUV420_SEMI_PLANAR: i32 = 21;
/// Android's `COLOR_FormatYUV420Flexible` (0x7F420888): ask for flexible,
/// read back which of the two the codec actually takes.
const COLOR_YUV420_FLEXIBLE: i32 = 0x7F42_0888;

/// The NDK's media API, declared here rather than through a crate: the
/// functions are a small, stable set, and no new dependency is needed
/// for them. `libmediandk` has shipped in the NDK since API 26; the
/// app's minimum is 29.
#[allow(non_camel_case_types)]
mod sys {
    use std::os::raw::{c_char, c_int, c_void};

    pub enum AMediaCodec {}
    pub enum AMediaFormat {}
    pub enum AMediaMuxer {}

    /// `AMediaCodecBufferInfo`: where a buffer's bytes sit, when they
    /// play, and whether they end the stream.
    #[repr(C)]
    pub struct AMediaCodecBufferInfo {
        pub offset: i32,
        pub size: i32,
        pub presentation_time_us: i64,
        pub flags: u32,
    }

    pub const AMEDIA_OK: c_int = 0;
    /// `AMEDIACODEC_INFO_TRY_AGAIN_LATER` (-1): nothing yet, ask again.
    pub const INFO_TRY_AGAIN_LATER: isize = -1;
    /// `AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED` (-2): the output format
    /// is ready, and the muxer takes its track from it.
    pub const INFO_OUTPUT_FORMAT_CHANGED: isize = -2;
    /// `AMEDIACODEC_CONFIGURE_FLAG_ENCODE` (1): configure as an encoder.
    pub const CONFIGURE_FLAG_ENCODE: u32 = 1;
    /// `AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM` (4).
    pub const BUFFER_FLAG_END_OF_STREAM: u32 = 4;
    /// `AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG` (2): codec setup data,
    /// carried by the muxer, not written as a sample.
    pub const BUFFER_FLAG_CODEC_CONFIG: u32 = 2;
    /// `AMEDIAMUXER_OUTPUT_MPEG_4` (0): an .mp4 file.
    pub const MUXER_OUTPUT_MPEG_4: c_int = 0;

    #[link(name = "mediandk")]
    unsafe extern "C" {
        pub fn AMediaCodec_createCodecByName(name: *const c_char) -> *mut AMediaCodec;
        pub fn AMediaCodec_delete(codec: *mut AMediaCodec);
        pub fn AMediaCodec_configure(
            codec: *mut AMediaCodec,
            format: *const AMediaFormat,
            surface: *mut c_void,
            crypto: *mut c_void,
            flags: u32,
        ) -> c_int;
        pub fn AMediaCodec_start(codec: *mut AMediaCodec) -> c_int;
        pub fn AMediaCodec_stop(codec: *mut AMediaCodec) -> c_int;
        pub fn AMediaCodec_dequeueInputBuffer(codec: *mut AMediaCodec, timeout_us: i64) -> isize;
        pub fn AMediaCodec_getInputBuffer(
            codec: *mut AMediaCodec,
            idx: usize,
            out_size: *mut usize,
        ) -> *mut u8;
        pub fn AMediaCodec_queueInputBuffer(
            codec: *mut AMediaCodec,
            idx: usize,
            offset: usize,
            size: usize,
            time: i64,
            flags: u32,
        ) -> c_int;
        pub fn AMediaCodec_dequeueOutputBuffer(
            codec: *mut AMediaCodec,
            info: *mut AMediaCodecBufferInfo,
            timeout_us: i64,
        ) -> isize;
        pub fn AMediaCodec_getOutputBuffer(
            codec: *mut AMediaCodec,
            idx: usize,
            out_size: *mut usize,
        ) -> *mut u8;
        pub fn AMediaCodec_getOutputFormat(codec: *mut AMediaCodec) -> *mut AMediaFormat;
        pub fn AMediaCodec_getInputFormat(codec: *mut AMediaCodec) -> *mut AMediaFormat;
        pub fn AMediaCodec_releaseOutputBuffer(
            codec: *mut AMediaCodec,
            idx: usize,
            render: bool,
        ) -> c_int;
        pub fn AMediaCodec_signalEndOfInputStream(codec: *mut AMediaCodec) -> c_int;
        pub fn AMediaFormat_new() -> *mut AMediaFormat;
        pub fn AMediaFormat_delete(format: *mut AMediaFormat);
        pub fn AMediaFormat_setString(
            format: *mut AMediaFormat,
            name: *const c_char,
            value: *const c_char,
        ) -> u8;
        pub fn AMediaFormat_setInt32(
            format: *mut AMediaFormat,
            name: *const c_char,
            value: i32,
        ) -> u8;
        pub fn AMediaFormat_getInt32(
            format: *mut AMediaFormat,
            name: *const c_char,
            out: *mut i32,
        ) -> u8;
        pub fn AMediaMuxer_new(fd: c_int, format: c_int) -> *mut AMediaMuxer;
        pub fn AMediaMuxer_delete(muxer: *mut AMediaMuxer);
        pub fn AMediaMuxer_addTrack(
            muxer: *mut AMediaMuxer,
            format: *const AMediaFormat,
        ) -> isize;
        pub fn AMediaMuxer_start(muxer: *mut AMediaMuxer) -> c_int;
        pub fn AMediaMuxer_stop(muxer: *mut AMediaMuxer) -> c_int;
        pub fn AMediaMuxer_writeSampleData(
            muxer: *mut AMediaMuxer,
            track: usize,
            data: *const u8,
            info: *const AMediaCodecBufferInfo,
        ) -> c_int;
    }
}

/// One native encode: the chip, the muxer, and the file between them.
///
/// The raw pointers are owned here and only touched on the export
/// thread; `Drop` stops and frees them when an export fails part way
/// through, so the chip has its session back for the fallback.
pub struct NativeEncoder {
    path: PathBuf,
    codec: *mut sys::AMediaCodec,
    muxer: *mut sys::AMediaMuxer,
    /// The file the muxer writes into, kept open until the muxer is
    /// deleted. Declared last so it closes last.
    file: std::fs::File,
    track: Option<usize>,
    muxer_started: bool,
    width: u32,
    height: u32,
    fps: f64,
    /// The colour layout the codec takes: planar or semi-planar.
    color_format: i32,
    /// The row stride and picture height the codec's input buffer is
    /// laid out with, which may be wider/taller than the picture.
    stride: u32,
    slice_height: u32,
    scaler: scaling::Context,
    written: u64,
    finished: bool,
    label: &'static str,
}

// The pointers are only used on the export thread, which owns this
// encoder from its creation to its finish or drop.
// SAFETY: no method touches the codec or muxer from another thread.
unsafe impl Send for NativeEncoder {}

impl NativeEncoder {
    /// Opens the component for `kind` and the muxer for `path`,
    /// overwriting anything already there. Fails in words the export
    /// log can be read from when the component will not open, wants a
    /// colour layout this file does not write, or the muxer will not
    /// start - each of which sends the export on to MPEG-4.
    pub fn create(
        path: impl AsRef<Path>,
        width: u32,
        height: u32,
        fps: f64,
        bitrate_kbps: u32,
        kind: NativeKind,
    ) -> Result<Self> {
        let path = path.as_ref().to_path_buf();
        let file = std::fs::File::create(&path).map_err(|source| Error::Io {
            path: path.clone(),
            source,
        })?;
        let fail = |operation: &'static str, detail: String| Error::Ffi {
            operation,
            path: path.clone(),
            detail,
        };
        // A C string for every name the NDK is handed.
        let cstr = |text: &str| CString::new(text).unwrap_or_else(|_| CString::new("").unwrap());
        let component = cstr(kind.component());
        // SAFETY: every call below hands the NDK live pointers it owns
        // for the call, checks the null or status it answers with, and
        // frees what it made on the way out.
        unsafe {
            let codec = sys::AMediaCodec_createCodecByName(component.as_ptr());
            if codec.is_null() {
                return Err(fail(
                    "create native encoder",
                    format!("{} would not open", kind.component()),
                ));
            }
            let cleanup_codec = |codec: *mut sys::AMediaCodec| {
                sys::AMediaCodec_delete(codec);
            };
            let format = sys::AMediaFormat_new();
            if format.is_null() {
                cleanup_codec(codec);
                return Err(fail(
                    "create native encoder",
                    "the encoder format would not open".to_owned(),
                ));
            }
            let set_string = |name: &str, value: &str| {
                sys::AMediaFormat_setString(format, cstr(name).as_ptr(), cstr(value).as_ptr()) != 0
            };
            let set_int = |name: &str, value: i32| {
                sys::AMediaFormat_setInt32(format, cstr(name).as_ptr(), value) != 0
            };
            // The bitrate the export sheet asked for, in bits a second,
            // clamped to what an i32 holds: a 20 Mbps ask is 20,000,000.
            let bitrate_bps =
                (i64::from(bitrate_kbps) * 1000).clamp(100_000, 100_000_000) as i32;
            let frame_rate = fps.round().clamp(1.0, 120.0) as i32;
            let configured = set_string("mime", kind.mime())
                && set_int("width", width as i32)
                && set_int("height", height as i32)
                && set_int("bitrate", bitrate_bps)
                && set_int("frame-rate", frame_rate)
                && set_int("i-frame-interval", 2)
                && set_int("color-format", COLOR_YUV420_FLEXIBLE);
            if !configured {
                sys::AMediaFormat_delete(format);
                cleanup_codec(codec);
                return Err(fail(
                    "configure native encoder",
                    format!("{} refused its format", kind.component()),
                ));
            }
            let status =
                sys::AMediaCodec_configure(codec, format, std::ptr::null_mut(), std::ptr::null_mut(), sys::CONFIGURE_FLAG_ENCODE);
            sys::AMediaFormat_delete(format);
            if status != sys::AMEDIA_OK {
                cleanup_codec(codec);
                return Err(fail(
                    "configure native encoder",
                    format!("{} refused to configure (status {status})", kind.component()),
                ));
            }
            let status = sys::AMediaCodec_start(codec);
            if status != sys::AMEDIA_OK {
                cleanup_codec(codec);
                return Err(fail(
                    "start native encoder",
                    format!("{} refused to start (status {status})", kind.component()),
                ));
            }
            // Which colour layout the codec actually takes, and the
            // stride it lays its input buffer out with: flexible means
            // ask, then read the answer, rather than guessing.
            let input_format = sys::AMediaCodec_getInputFormat(codec);
            let mut color_format = 0i32;
            let mut stride = 0i32;
            let mut slice_height = 0i32;
            let mut have_format = false;
            if !input_format.is_null() {
                let got_color = sys::AMediaFormat_getInt32(
                    input_format,
                    cstr("color-format").as_ptr(),
                    &mut color_format,
                ) != 0;
                // Stride and slice height are optional: a codec that
                // does not name them lays its rows out at the width.
                let _ = sys::AMediaFormat_getInt32(
                    input_format,
                    cstr("stride").as_ptr(),
                    &mut stride,
                );
                let _ = sys::AMediaFormat_getInt32(
                    input_format,
                    cstr("slice-height").as_ptr(),
                    &mut slice_height,
                );
                sys::AMediaFormat_delete(input_format);
                have_format = got_color;
            }
            if !have_format
                || (color_format != COLOR_YUV420_PLANAR
                    && color_format != COLOR_YUV420_SEMI_PLANAR)
            {
                let _ = sys::AMediaCodec_stop(codec);
                cleanup_codec(codec);
                return Err(fail(
                    "configure native encoder",
                    format!(
                        "{} wants colour format 0x{color_format:x}, not YUV420 planar or semi-planar",
                        kind.component()
                    ),
                ));
            }
            let muxer = sys::AMediaMuxer_new(
                file.as_raw_fd(),
                sys::MUXER_OUTPUT_MPEG_4,
            );
            if muxer.is_null() {
                let _ = sys::AMediaCodec_stop(codec);
                cleanup_codec(codec);
                return Err(fail(
                    "create native muxer",
                    "the MP4 muxer would not open".to_owned(),
                ));
            }
            let mut scaler = scaling::Context::get(
                Pixel::RGBA,
                width,
                height,
                Pixel::YUV420P,
                width,
                height,
                scaling::Flags::BILINEAR,
            )
            .map_err(|error| ffi::fail("scaler", &path, error))?;
            // BT.709 coefficients from full-range RGB to limited-range
            // YUV, exactly as the FFmpeg encoder is set up: without
            // this swscale's default is BT.601, and the file's colours
            // would come out on the wrong matrix for a 709 tag.
            // (SAFETY: `scaler` owns a live SwsContext; the coefficient
            // tables are static and the call only sets fields on it.
            // This sits inside the `unsafe` block above, so no second
            // `unsafe` is needed here.)
            let coefficients = ffmpeg_the_third::sys::sws_getCoefficients(
                ffmpeg_the_third::sys::SWS_CS_ITU709,
            );
            ffmpeg_the_third::sys::sws_setColorspaceDetails(
                scaler.as_mut_ptr(),
                coefficients,
                1,
                coefficients,
                0,
                0,
                1 << 16,
                1 << 16,
            );
            log::info!(
                "export encoder: {} component={} hardware=true color-format={} stride={} slice-height={}",
                kind.label(),
                kind.component(),
                color_format,
                if stride > 0 { stride } else { width as i32 },
                if slice_height > 0 { slice_height } else { height as i32 },
            );
            Ok(Self {
                path,
                codec,
                muxer,
                file,
                track: None,
                muxer_started: false,
                width,
                height,
                fps,
                color_format,
                stride: if stride > 0 { stride as u32 } else { width },
                slice_height: if slice_height > 0 { slice_height as u32 } else { height },
                scaler,
                written: 0,
                finished: false,
                label: kind.label(),
            })
        }
    }

    /// The name the export log uses for this attempt.
    pub fn label(&self) -> &'static str {
        self.label
    }

    /// How many frames have been accepted so far.
    pub const fn written(&self) -> u64 {
        self.written
    }

    /// The failure of an encoder that stopped, in the same words the
    /// FFmpeg encoders use, so the export log reads the same for both.
    fn stalled(&self, detail: String) -> Error {
        Error::Stalled {
            operation: "encode",
            path: self.path.clone(),
            detail,
            hardware: true,
        }
    }

    fn failed(&self, operation: &'static str, detail: String) -> Error {
        Error::Ffi {
            operation,
            path: self.path.clone(),
            detail,
        }
    }

    /// Copies the converted YUV420P frame into the codec's input
    /// buffer, in the layout the codec reported: planar (Y, U, V) or
    /// semi-planar (Y, then UV interleaved), each row at the codec's
    /// stride, each plane at its slice height.
    ///
    /// # Safety
    /// `dst` is the input buffer the codec handed out, `capacity` its
    /// size in bytes; nothing is written past it.
    unsafe fn copy_into(&self, converted: &Video, dst: *mut u8, capacity: usize) -> Result<usize> {
        let width = self.width as usize;
        let height = self.height as usize;
        let stride = self.stride as usize;
        let slice_height = self.slice_height as usize;
        let y_size = stride * slice_height;
        let uv_stride = stride / 2;
        let uv_height = slice_height / 2;
        let uv_size = uv_stride * uv_height;
        let needed = if self.color_format == COLOR_YUV420_PLANAR {
            y_size + uv_size * 2
        } else {
            y_size + uv_size
        };
        if capacity < needed {
            return Err(self.failed(
                "encode",
                format!(
                    "the native encoder's input buffer holds {capacity} bytes, but a {width}x{height} frame needs {needed}"
                ),
            ));
        }
        // SAFETY: the caller guarantees `dst` holds `capacity` bytes;
        // the check above keeps every write below `needed`.
        unsafe {
            let y_dst = std::slice::from_raw_parts_mut(dst, y_size);
            let y_src = converted.data(0);
            let y_src_stride = converted.stride(0);
            for row in 0..height {
                y_dst[row * stride..row * stride + width]
                    .copy_from_slice(&y_src[row * y_src_stride..row * y_src_stride + width]);
            }
            if self.color_format == COLOR_YUV420_PLANAR {
                let u_dst = std::slice::from_raw_parts_mut(dst.add(y_size), uv_size);
                let v_dst = std::slice::from_raw_parts_mut(dst.add(y_size + uv_size), uv_size);
                let u_src = converted.data(1);
                let v_src = converted.data(2);
                let u_src_stride = converted.stride(1);
                let v_src_stride = converted.stride(2);
                let uv_width = width / 2;
                let uv_rows = height / 2;
                for row in 0..uv_rows {
                    u_dst[row * uv_stride..row * uv_stride + uv_width].copy_from_slice(
                        &u_src[row * u_src_stride..row * u_src_stride + uv_width],
                    );
                    v_dst[row * uv_stride..row * uv_stride + uv_width].copy_from_slice(
                        &v_src[row * v_src_stride..row * v_src_stride + uv_width],
                    );
                }
            } else {
                let uv_dst = std::slice::from_raw_parts_mut(dst.add(y_size), uv_size);
                let u_src = converted.data(1);
                let v_src = converted.data(2);
                let u_src_stride = converted.stride(1);
                let v_src_stride = converted.stride(2);
                let uv_width = width / 2;
                let uv_rows = height / 2;
                for row in 0..uv_rows {
                    for col in 0..uv_width {
                        uv_dst[row * uv_stride + col * 2] = u_src[row * u_src_stride + col];
                        uv_dst[row * uv_stride + col * 2 + 1] = v_src[row * v_src_stride + col];
                    }
                }
            }
        }
        Ok(needed)
    }

    /// Reads out finished output until there is none waiting, writing
    /// samples to the muxer. Returns whether the end of the stream came
    /// back, and how many packets were read - the caller resets its
    /// stall wait on packets, not on asks. A `timeout_us` of zero only
    /// takes what is already there.
    ///
    /// # Safety
    /// The codec and muxer pointers are live for the whole call.
    unsafe fn drain(&mut self, timeout_us: i64) -> Result<(bool, u32)> {
        let mut ended = false;
        let mut packets = 0u32;
        loop {
            let mut info = sys::AMediaCodecBufferInfo {
                offset: 0,
                size: 0,
                presentation_time_us: 0,
                flags: 0,
            };
            // SAFETY: `info` is a live struct the codec fills in.
            let index = unsafe { sys::AMediaCodec_dequeueOutputBuffer(self.codec, &mut info, timeout_us) };
            if index == sys::INFO_TRY_AGAIN_LATER {
                break;
            }
            if index == sys::INFO_OUTPUT_FORMAT_CHANGED {
                // SAFETY: the format the codec hands back is live until
                // it is deleted below, and the muxer copies what it
                // needs from it when the track is added.
                let format = unsafe { sys::AMediaCodec_getOutputFormat(self.codec) };
                if format.is_null() {
                    return Err(self.failed(
                        "encode",
                        "the native encoder handed back no output format".to_owned(),
                    ));
                }
                let track = unsafe { sys::AMediaMuxer_addTrack(self.muxer, format) };
                unsafe { sys::AMediaFormat_delete(format) };
                if track < 0 {
                    return Err(self.failed(
                        "encode",
                        format!("the MP4 muxer refused the native track (status {track})"),
                    ));
                }
                let status = unsafe { sys::AMediaMuxer_start(self.muxer) };
                if status != sys::AMEDIA_OK {
                    return Err(self.failed(
                        "encode",
                        format!("the MP4 muxer refused to start (status {status})"),
                    ));
                }
                self.track = Some(track as usize);
                self.muxer_started = true;
                log::info!("export encoder: {} track {} started in the muxer", self.label, track);
                continue;
            }
            if index < 0 {
                return Err(self.failed(
                    "encode",
                    format!("the native encoder's output failed (status {index})"),
                ));
            }
            let index = index as usize;
            if info.flags & sys::BUFFER_FLAG_END_OF_STREAM != 0 {
                ended = true;
            }
            if info.flags & sys::BUFFER_FLAG_CODEC_CONFIG != 0 {
                // Codec setup data: the muxer already has it from the
                // output format, so it is released, not written.
                unsafe { sys::AMediaCodec_releaseOutputBuffer(self.codec, index, false) };
                if ended {
                    break;
                }
                continue;
            }
            if info.size == 0 {
                // An empty buffer, usually the end of the stream itself:
                // nothing to write, just released.
                unsafe { sys::AMediaCodec_releaseOutputBuffer(self.codec, index, false) };
                if ended {
                    break;
                }
                continue;
            }
            packets += 1;
            if self.muxer_started {
                let Some(track) = self.track else {
                    unsafe { sys::AMediaCodec_releaseOutputBuffer(self.codec, index, false) };
                    return Err(self.failed(
                        "encode",
                        "the native encoder wrote a sample before its track started".to_owned(),
                    ));
                };
                let mut size = 0usize;
                // SAFETY: the buffer the codec hands back holds `size`
                // bytes from `info.offset`; the muxer reads them during
                // this call only.
                let data = unsafe { sys::AMediaCodec_getOutputBuffer(self.codec, index, &mut size) };
                if data.is_null() {
                    unsafe { sys::AMediaCodec_releaseOutputBuffer(self.codec, index, false) };
                    return Err(self.failed(
                        "encode",
                        "the native encoder handed back no output buffer".to_owned(),
                    ));
                }
                let status = unsafe {
                    sys::AMediaMuxer_writeSampleData(
                        self.muxer,
                        track,
                        data.add(info.offset as usize),
                        &info,
                    )
                };
                unsafe { sys::AMediaCodec_releaseOutputBuffer(self.codec, index, false) };
                if status != sys::AMEDIA_OK {
                    return Err(self.failed(
                        "encode",
                        format!("the MP4 muxer refused a sample (status {status})"),
                    ));
                }
            } else {
                unsafe { sys::AMediaCodec_releaseOutputBuffer(self.codec, index, false) };
            }
            if ended {
                break;
            }
        }
        Ok((ended, packets))
    }

    /// Hands one converted frame to the codec, waiting up to the stall
    /// patience for an input buffer, draining output while waiting so
    /// the codec has somewhere to put what it finishes.
    fn send(&mut self, converted: &Video, presentation_time_us: i64) -> Result<()> {
        let started = Instant::now();
        loop {
            // SAFETY: the codec pointer is live; the timeout is in
            // microseconds, ten milliseconds a ask.
            let index = unsafe { sys::AMediaCodec_dequeueInputBuffer(self.codec, 10_000) };
            if index >= 0 {
                let index = index as usize;
                let mut capacity = 0usize;
                // SAFETY: the buffer the codec hands back holds
                // `capacity` bytes for this call.
                let buffer = unsafe { sys::AMediaCodec_getInputBuffer(self.codec, index, &mut capacity) };
                if buffer.is_null() {
                    return Err(self.failed(
                        "encode",
                        "the native encoder handed back no input buffer".to_owned(),
                    ));
                }
                // SAFETY: `copy_into` writes at most `capacity` bytes.
                let size = unsafe { self.copy_into(converted, buffer, capacity) }?;
                let status = unsafe {
                    sys::AMediaCodec_queueInputBuffer(
                        self.codec,
                        index,
                        0,
                        size,
                        presentation_time_us,
                        0,
                    )
                };
                if status != sys::AMEDIA_OK {
                    return Err(self.failed(
                        "encode",
                        format!("the native encoder refused a frame (status {status})"),
                    ));
                }
                // SAFETY: the codec and muxer are live.
                let _ = unsafe { self.drain(0) }?;
                return Ok(());
            }
            // No input buffer yet: read out what the codec finished,
            // which is what frees one, and call it stuck at three
            // seconds of nothing - the same patience as FFmpeg's.
            // SAFETY: the codec and muxer are live.
            let _ = unsafe { self.drain(0) }?;
            if started.elapsed() >= STALL_PATIENCE {
                return Err(self.stalled(
                    "the encoder took no frame for 3 seconds".to_owned(),
                ));
            }
            std::thread::sleep(ASK_WAIT);
        }
    }
}

impl FrameSink for NativeEncoder {
    fn write_frame(&mut self, frame: &Frame) -> Result<()> {
        if frame.width() != self.width || frame.height() != self.height {
            return Err(Error::FrameSizeMismatch {
                want_width: self.width,
                want_height: self.height,
                got_width: frame.width(),
                got_height: frame.height(),
            });
        }
        if self.finished {
            return Err(Error::Io {
                path: self.path.clone(),
                source: std::io::Error::other("encoder was already finished"),
            });
        }
        if frame.depth() == Depth::Sixteen {
            return Err(Error::Io {
                path: self.path.clone(),
                source: std::io::Error::other(
                    "the native encoder takes eight-bit frames; an HDR timeline needs the classic encoder",
                ),
            });
        }
        let mut rgba = Video::new(Pixel::RGBA, self.width, self.height);
        {
            let stride = rgba.stride(0);
            let row = self.width as usize * 4;
            let data = rgba.data_mut(0);
            for (y, source) in frame.pixels().chunks_exact(row).enumerate() {
                data[y * stride..y * stride + row].copy_from_slice(source);
            }
        }
        let mut converted = Video::empty();
        self.scaler
            .run(&rgba, &mut converted)
            .map_err(|error| ffi::fail("convert", &self.path, error))?;
        // SAFETY: `converted` is a live frame the scaler just filled;
        // the range is a plain field the encoder reads with the picture.
        unsafe {
            (*converted.as_mut_ptr()).color_range = ffmpeg_the_third::color::Range::MPEG.into();
        }
        let presentation_time_us = (self.written as f64 * 1_000_000.0 / self.fps) as i64;
        self.send(&converted, presentation_time_us)?;
        self.written += 1;
        Ok(())
    }

    fn finish(&mut self) -> Result<()> {
        if self.finished {
            return Ok(());
        }
        self.finished = true;
        // The end of the stream: an input buffer carrying the flag, or
        // the stream signalled when no buffer comes within the patience.
        let started = Instant::now();
        let mut signalled = false;
        while !signalled {
            // SAFETY: the codec pointer is live.
            let index = unsafe { sys::AMediaCodec_dequeueInputBuffer(self.codec, 10_000) };
            if index >= 0 {
                let status = unsafe {
                    sys::AMediaCodec_queueInputBuffer(
                        self.codec,
                        index as usize,
                        0,
                        0,
                        (self.written as f64 * 1_000_000.0 / self.fps) as i64,
                        sys::BUFFER_FLAG_END_OF_STREAM,
                    )
                };
                if status != sys::AMEDIA_OK {
                    return Err(self.failed(
                        "encode",
                        format!("the native encoder refused the end of the stream (status {status})"),
                    ));
                }
                signalled = true;
            } else if started.elapsed() >= STALL_PATIENCE {
                let status = unsafe { sys::AMediaCodec_signalEndOfInputStream(self.codec) };
                if status != sys::AMEDIA_OK {
                    return Err(self.stalled(
                        "the encoder took no frame for 3 seconds".to_owned(),
                    ));
                }
                signalled = true;
            } else {
                // SAFETY: the codec and muxer are live.
                let _ = unsafe { self.drain(0) }?;
                std::thread::sleep(ASK_WAIT);
            }
        }
        // The tail: drain until the end of the stream comes back, or
        // three seconds with nothing new, which is a stall, not a tail.
        let mut last = Instant::now();
        loop {
            // SAFETY: the codec and muxer are live.
            let (ended, packets) = unsafe { self.drain(10_000) }?;
            if ended {
                break;
            }
            if packets > 0 {
                last = Instant::now();
            } else if last.elapsed() >= STALL_PATIENCE {
                return Err(self.stalled(
                    "the encoder held its last frames for 3 seconds".to_owned(),
                ));
            }
        }
        // The file is finished here, not when this encoder drops: stop
        // the muxer now, so the audio step reads a complete file.
        // SAFETY: the pointers are live; the drop below sees the nulls
        // and skips them.
        unsafe {
            if self.muxer_started {
                let _ = sys::AMediaMuxer_stop(self.muxer);
                self.muxer_started = false;
            }
            if !self.muxer.is_null() {
                sys::AMediaMuxer_delete(self.muxer);
                self.muxer = std::ptr::null_mut();
            }
            if !self.codec.is_null() {
                let _ = sys::AMediaCodec_stop(self.codec);
                sys::AMediaCodec_delete(self.codec);
                self.codec = std::ptr::null_mut();
            }
        }
        Ok(())
    }
}

impl Drop for NativeEncoder {
    fn drop(&mut self) {
        // SAFETY: the pointers are either live or null; stopping a
        // codec or muxer that already stopped is harmless, and a null
        // one is skipped. The file closes when the struct does, after
        // the muxer is gone.
        unsafe {
            if !self.codec.is_null() {
                let _ = sys::AMediaCodec_stop(self.codec);
                sys::AMediaCodec_delete(self.codec);
                self.codec = std::ptr::null_mut();
            }
            if !self.muxer.is_null() {
                if self.muxer_started {
                    let _ = sys::AMediaMuxer_stop(self.muxer);
                }
                sys::AMediaMuxer_delete(self.muxer);
                self.muxer = std::ptr::null_mut();
            }
        }
    }
}
