# AGENTS.md

Operating notes for future agents working on this fork
(muzammilsoft/capcraft-android, KG's personal CapCraft version).

## Standing rules (KG)
- After EVERY change: update this file and `docs/AGENT-CHANGELOG.md`
  (new version entry: what + why) before pushing.
- Pushes, releases and uploads only on KG's explicit order.
- Encoder order is binding: OMX.hisi.video.encoder.avc first, then
  c2.android.avc.encoder, then native MPEG-4 fallback last. NO
  OMX.google.h264.encoder in ANY default order (Auto or Classic).
  The Google encoder is reachable ONLY as the pinned manual Settings
  choice "H.264 Google only" (Phase 3, KG's explicit order 2026-11-06,
  after his earlier ban - an experiment on his Huawei, unproven until
  he tests it). The hisi/c2 attempts go through FFmpeg's
  h264_mediacodec pinned by component name; the native path calls
  MediaCodec directly through the NDK (ByteBuffer input, not Surface).
- Stall patience is 3 seconds per encoder attempt (Phase 3; was 10).
  Each attempt is tried once and timed in the export log; the
  compositor is built once per export and reused across attempts.
  A pinned (manual) encoder never retries inside the encoder.
- Stable TEST signing key: GitHub Actions secret
  CAPCRAFT_KEYSTORE_BASE64, set 2026-11-04. NEVER delete, regenerate
  or replace it. A tag build with no secret must FAIL, never generate
  a fresh random key. No key material in repo, chat, memory or files.
- No rebrand, no new app name/icon, no applicationId change.
- Builds run only in GitHub Actions (no Android SDK / Rust toolchain
  in the agent VM). API-created git refs do NOT trigger Actions push
  workflows: use workflow_dispatch on the target tag ref.
- Keep executable scripts mode 100755 (git-database API pushes strip
  it otherwise and CI fails with "Permission denied"). Adding an enum
  variant requires covering every match on it. versionCode follows
  the formula in app/build.gradle.kts (0.0.10-beta = 1001, so
  0.0.11-beta = 1101).
- Do NOT reword the MPEG-4 fallback error strings ("took no frame",
  "failed too", "mediacodec encoder open failed"): the fallback
  matches on them.
- Do NOT add new Reporter stage names for pre-render phases:
  `fraction_of()` maps any unknown stage to 0.9.
- KG's phone is a Huawei Y8p, Android 10, run natively (NOT in
  Gbox). Test builds install side-by-side only after one final
  uninstall when the signing key changes; from 0.0.10 onward the
  stable test key should allow update-over.

## Phase 2 (0.0.11-beta, 2026-11-05)
- Templates show a preview (poster.jpg when the bundle has one, a
  drawn layout schematic otherwise); the built-in template is named
  "Split screen" (renamed in place from "KG Bilibili split" on
  devices that already have it); the list hides when empty.
- Backup/restore live in Settings only, not in the Templates panel.
- Vector shapes: `ShapeKind::Vector` + `ShapeStyle.points` /
  `corner_radius`, painted in concat-text `paint_shape`; presets in
  the Stickers shelf, point editor in the Vector panel, Width/Height
  stretch via `setClipTransform` stretchX/stretchY (0.1..10).
- Motion graph: multi-point value graph over a clip's existing
  keyframes (Position X/Y, Scale, Rotation, Opacity) with 7 presets
  as point lists; the bend between two points is still the
  per-segment Bezier ease set in Graphs.
- Export timing: `export: timing <stage> <ms>ms` log lines at every
  export stage (titles, flatten, background, validate-probe,
  compositor, build-timeline, before-picture, render total), visible
  in the export-log view, now also on a successful export. The
  native encoder list is written after the export starts, not before.

## Phase 3 (0.0.12-beta, 2026-11-06)

KG's order, from his own 2026-11-06 device log (0.0.11-beta,
`export-log-20261106_064215.txt`): a 22-second video took 68 seconds
- compositor 2.1 s, flatten/probe 0 ms, hisi stalled after 4 frames,
c2 stalled after 5, each costing the old 10-second patience, then
MPEG-4 encoded 643 frames in 41 s (15.6 fps). A 6-minute attempt died
the same way (hisi stalled after 4 frames). So the startup delay was
never project loading: it was twenty seconds of dead MediaCodec
waiting on every export.

- **Native MediaCodec encoder (the Kinemaster route).** A real native
  path in Rust (`concat-media/src/native_encoder.rs`): the NDK's
  `AMediaCodec`/`AMediaMuxer` C API (libmediandk, no new crates),
  `AMediaCodec_createCodecByName` pinned to
  OMX.hisi.video.encoder.avc (H.264) and OMX.hisi.video.encoder.hevc
  (HEVC, smaller files), ByteBuffer input - NOT Surface input, said
  honestly: the compositor hands back RGBA pixels, so frames go in as
  YUV420 buffers in the layout the codec reports (planar or
  semi-planar, with its stride/slice-height). MPEG-4 stays the fallback
  if a native encoder fails mid-export. Whether the chip itself stalls
  the same way FFmpeg's wrapper did is UNPROVEN until KG tests it on
  his phone - the log says which encoder ran and how far it got.
- **"Export encoder" setting (Settings tab).** Eight real choices,
  each with a plain explanation, persisted in SharedPreferences
  (survives restarts and update-over installs; NOT in a backup zip -
  backups hold projects/templates/looks only, a pre-existing limit):
  Auto (native H.264, then MPEG-4 - the new default), Classic auto
  (hisi, c2, MPEG-4 - the old order, kept only to compare), MPEG-4
  only, H.264 hisi only, H.264 c2 only, H.264 Google only, Native
  H.264 only, Native HEVC only. The export screen shows the selected
  encoder; the export log shows the selected one, each attempt with
  ms and frames, the final encoder, total time and encode fps
  (`export: encoder final=<name> frames=<n> attempt_ms=<ms>
  encode_fps=<fps> total_ms=<ms>`).
- **Google encoder, manual only (KG's explicit 2026-11-06 order).**
  OMX.google.h264.encoder was added as the "H.264 Google only"
  Settings choice after KG said "try adding it, if it works that is a
  gain" - lifting his earlier ban for this build only. It is in NO
  Auto order and NO Classic order, has the same 3-second stall timeout
  and MPEG-4 fallback, and is labelled in Settings as a software
  encoder and an unproven experiment on his Huawei.
- **Quick wins in the same build.** Stall timeout 10 s -> 3 s per
  encoder attempt (FFmpeg and native alike); the compositor/timeline
  is built ONCE per export and reused across encoder retries instead
  of re-initialising per retry; per-encoder timing logs (selected
  encoder, each attempt with ms, frames accepted, final encoder,
  total time, encode fps) in the export log, visible after success as
  well as failure.
- **Per-encoder comparison table for KG's phone** (same clip, each
  setting - fill in on the device; nothing here is claimed until then):

  | Setting | Time | File size | Log says |
  |---|---|---|---|
  | Auto (native H.264) |  |  | `final=native-h264` or `final=mpeg4` |
  | Classic auto |  |  | `final=ffmpeg-hisi/c2` or `final=mpeg4` |
  | MPEG-4 only |  |  | `final=mpeg4` |
  | H.264 hisi only |  |  | hisi frames, then `final=mpeg4` |
  | H.264 c2 only |  |  | c2 frames, then `final=mpeg4` |
  | H.264 Google only |  |  | Google frames, then `final=mpeg4` |
  | Native H.264 only |  |  | `final=native-h264` or `final=mpeg4` |
  | Native HEVC only |  |  | `final=native-hevc` or `final=mpeg4` |

Kept from Phase 1/2, unchanged: MPEG-4 fallback, ExportSheet MBPS
stops [1,2,3,5,10,20], presets Small / Balanced / Quality, template
preview + neutral "Split screen", vector shapes, motion graph, stage
timing logs, backup/restore in Settings only, stable test key (release
stays DRAFT, not published).
