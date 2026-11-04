// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! KG fork (2026-11-04, Phase 1): the Android seam over
//! [`concat_host::templates`], the project-template bundle engine
//! upstream already ships. A template is a project with replaceable
//! slots: placeholder media in the bin, bundled design files beside it.
//! The UI exposes only what Phase 1 promises: list, save the current
//! project as a template, apply a template with slot fills, and the one
//! built-in template, "KG Bilibili split". The full template editor -
//! designing a new template layout from scratch in the UI - is Phase 3,
//! not this build.

use std::path::PathBuf;

use concat_host::session::Session;
use concat_host::templates::{self, SlotFill};
use concat_project::commands::{Command, NewMedia};
use concat_project::model::{MediaKind, TextStyle};
use concat_project::DocumentSettings;

use crate::session::MobileSession;
use crate::{context, MobileError};

/// One slot in a template, as the UI lists it.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TemplateSlotView {
    /// The bin id the fill replaces.
    pub media_id: String,
    /// The slot's name, from the placeholder media.
    pub name: String,
    /// "video", "audio" or "image".
    pub kind: String,
    /// The slot's length in seconds, from its clips.
    pub seconds: f64,
}

/// One template in the library, as the UI lists it.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TemplateInfoView {
    /// The bundle folder.
    pub path: String,
    /// The template's name.
    pub name: String,
    /// Frame width in pixels.
    pub width: u32,
    /// Frame height in pixels.
    pub height: u32,
    /// Frame rate numerator.
    pub rate_num: i64,
    /// Frame rate denominator.
    pub rate_den: i64,
    /// The replaceable slots, in timeline order.
    pub slots: Vec<TemplateSlotView>,
    /// Whether the bundle carries a poster picture.
    pub has_poster: bool,
}

fn view_of(info: templates::TemplateInfo) -> TemplateInfoView {
    TemplateInfoView {
        path: info.path,
        name: info.name,
        width: info.width,
        height: info.height,
        rate_num: info.rate_num,
        rate_den: info.rate_den,
        slots: info
            .slots
            .into_iter()
            .map(|slot| TemplateSlotView {
                media_id: slot.media_id,
                name: slot.name,
                kind: match slot.kind {
                    MediaKind::Video => "video".to_owned(),
                    MediaKind::Audio => "audio".to_owned(),
                    MediaKind::Image => "image".to_owned(),
                },
                seconds: slot.seconds,
            })
            .collect(),
        has_poster: info.has_poster,
    }
}

/// Every template in the library, in name order; a folder that does not
/// parse is skipped by the engine, not by this function.
#[uniffi::export]
pub fn list_templates() -> Vec<TemplateInfoView> {
    let Ok(context) = context() else {
        return Vec::new();
    };
    templates::list(&context.dirs.config)
        .into_iter()
        .map(view_of)
        .collect()
}

/// Saves the open project as a template under `name`. A name that
/// already exists is refused, not overwritten, by the engine.
#[uniffi::export]
pub fn save_template(
    session: &MobileSession,
    name: String,
) -> Result<TemplateInfoView, MobileError> {
    let context = context()?;
    let info = session.with_session(|session| {
        let document = session.document();
        let settings = session.settings();
        templates::save(&context.dirs.config, &document, &settings, session.path(), &name)
            .map_err(MobileError::Failed)
    })?;
    Ok(view_of(info))
}

/// One slot fill, as the UI sends it: a picked file, not a full engine
/// media record, so the Kotlin side builds no engine JSON.
#[derive(Debug, serde::Deserialize)]
struct SimpleFill {
    #[serde(rename = "mediaId")]
    media_id: String,
    path: String,
    name: String,
    #[serde(default)]
    duration: Option<f64>,
    #[serde(default = "default_kind")]
    kind: String,
    #[serde(default)]
    width: Option<u32>,
    #[serde(default)]
    height: Option<u32>,
    #[serde(default)]
    has_audio: bool,
}

fn default_kind() -> String {
    "video".to_owned()
}

/// Applies `template_path` as a new project called `name`, filling
/// every slot from `fills_json` (a JSON array of [`SimpleFill`]).
/// Returns the new project's folder, for the editor to open. Unfilled
/// slots are an error, by the engine, not a half-filled project.
#[uniffi::export]
pub fn instantiate_template(
    template_path: String,
    name: String,
    fills_json: String,
) -> Result<String, MobileError> {
    let context = context()?;
    let simple: Vec<SimpleFill> =
        serde_json::from_str(&fills_json).map_err(|error| MobileError::Failed(error.to_string()))?;
    let fills: Vec<SlotFill> = simple
        .into_iter()
        .map(|fill| SlotFill {
            media_id: fill.media_id,
            item: NewMedia {
                path: fill.path,
                name: fill.name,
                duration: fill.duration,
                kind: match fill.kind.as_str() {
                    "audio" => MediaKind::Audio,
                    "image" => MediaKind::Image,
                    _ => MediaKind::Video,
                },
                width: fill.width,
                height: fill.height,
                frame_rate: None,
                frame_rate_fraction: None,
                video_codec: None,
                audio_codec: None,
                has_audio: fill.has_audio,
                audio_tracks: Vec::new(),
                origin: None,
                color_space: Default::default(),
            },
        })
        .collect();
    let projects = context.projects.to_string_lossy().into_owned();
    let info = templates::instantiate(&template_path, &projects, &name, fills)?;
    Ok(info.path)
}

/// Deletes the template at `template_path`, when it is inside the
/// library; anything else is refused by the engine.
#[uniffi::export]
pub fn delete_template(template_path: String) -> Result<(), MobileError> {
    let context = context()?;
    templates::delete(&context.dirs.config, &template_path)?;
    Ok(())
}

const KG_TEMPLATE_NAME: &str = "KG Bilibili split";

/// Creates the one built-in Phase 1 template, if it is not already in
/// the library, and returns it. Landscape 16:9, 30 fps: the main video
/// slot on the left half, three text slots on the right - title at the
/// top, notes in the middle, question at the bottom - over a black
/// background. The text slots are ordinary title clips after applying:
/// the user taps one in the editor and retypes it, which is how a
/// title, a note and a question are replaced; only the video slot is a
/// FillSlot media slot. The layout values are a first proof, not a
/// finished design: the device test must judge them and they will be
/// tuned from that verdict, not claimed perfect here.
#[uniffi::export]
pub fn ensure_kg_bilibili_template() -> Result<TemplateInfoView, MobileError> {
    let context = context()?;
    if let Some(existing) = templates::list(&context.dirs.config)
        .into_iter()
        .find(|info| info.name == KG_TEMPLATE_NAME)
    {
        return Ok(view_of(existing));
    }
    let source: PathBuf = context.cache.join("kg-template-source");
    let _ = std::fs::create_dir_all(&source);
    let source_str = source.to_string_lossy().into_owned();
    let settings = DocumentSettings {
        name: KG_TEMPLATE_NAME.to_owned(),
        width: 1920,
        height: 1080,
        rate_num: 30,
        rate_den: 1,
        canvas: [0, 0, 0, 255],
    };
    let mut session = Session::open(&source_str, settings.clone())?;
    let media_id = session
        .apply(Command::AddMedia {
            item: NewMedia {
                path: String::new(),
                name: "Main video".to_owned(),
                duration: Some(10.0),
                kind: MediaKind::Video,
                width: Some(1920),
                height: Some(1080),
                frame_rate: Some(30.0),
                frame_rate_fraction: Some("30/1".to_owned()),
                video_codec: Some("h264".to_owned()),
                audio_codec: None,
                has_audio: false,
                audio_tracks: Vec::new(),
                origin: None,
                color_space: Default::default(),
            },
        })?
        .created_id
        .ok_or_else(|| MobileError::Failed("the template slot minted no id".to_owned()))?;
    session.apply(Command::SetMediaPlaceholder {
        media_id: media_id.clone(),
        placeholder: true,
    })?;
    let clip_id = session
        .apply(Command::AddClipAtFirstFree {
            media_id: media_id.clone(),
            start: 0.0,
        })?
        .created_id
        .ok_or_else(|| MobileError::Failed("the template clip minted no id".to_owned()))?;
    // Left half: scale 0.55, centre a fifth of a frame left of centre.
    session.apply(Command::SetClipTransform {
        clip_id,
        scale: Some(0.55),
        offset_x: Some(-0.22),
        offset_y: Some(0.0),
        rotation: None,
        stretch_x: None,
        stretch_y: None,
    })?;
    let texts: [(&str, f64, f64); 3] = [
        ("TITLE - tap to replace", 0.09, -0.35),
        ("NOTES - tap to replace", 0.06, 0.0),
        ("QUESTION - tap to replace", 0.07, 0.35),
    ];
    for (content, font_size, offset_y) in texts {
        let mut style = TextStyle::default();
        style.content = content.to_owned();
        style.font_size = font_size;
        let clip_id = session
            .apply(Command::AddTextClip {
                track_id: None,
                above: true,
                start: 0.0,
                style: Some(style),
                duration: Some(10.0),
                offset_y: Some(offset_y),
            })?
            .created_id
            .ok_or_else(|| MobileError::Failed("the template title minted no id".to_owned()))?;
        // Right half: centre just over a quarter of a frame right of
        // centre, so the block sits beside, not over, the video slot.
        session.apply(Command::SetClipTransform {
            clip_id,
            scale: None,
            offset_x: Some(0.28),
            offset_y: Some(offset_y),
            rotation: None,
            stretch_x: None,
            stretch_y: None,
        })?;
    }
    let document = session.document();
    let info = templates::save(
        &context.dirs.config,
        &document,
        &settings,
        &source_str,
        KG_TEMPLATE_NAME,
    )?;
    let _ = std::fs::remove_dir_all(&source);
    Ok(view_of(info))
}
