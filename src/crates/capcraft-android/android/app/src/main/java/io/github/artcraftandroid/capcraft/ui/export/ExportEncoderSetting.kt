package io.github.artcraftandroid.capcraft.ui.export

import android.content.Context

/**
 * Phase 3 (0.0.12-beta): the "Export encoder" Settings choice.
 *
 * Which encoder an export runs on. Every option is a real path, not a
 * label, and every one but MPEG-4-only falls back to MPEG-4 when its
 * encoder fails, so an export never dies just because a comparison
 * encoder stalled. Kept in the same SharedPreferences file as the
 * linkage switch, so it survives restarts and update-over installs;
 * it is NOT in a backup zip (backups hold projects, templates and
 * looks only - a pre-existing limit, not a Phase 3 change).
 *
 * The Google encoder is a MANUAL choice only, on KG's explicit
 * 2026-11-06 order ("try adding it, if it works that is a gain") after
 * his earlier ban: it is in no Auto order and no Classic order, and
 * whether it encodes on his Huawei is unproven until he tests it.
 */
data class ExportEncoderOption(val id: String, val title: String, val explanation: String)

val EXPORT_ENCODER_OPTIONS: List<ExportEncoderOption> = listOf(
    ExportEncoderOption(
        "auto",
        "Auto (native H.264, then MPEG-4)",
        "Try this first. Fast when the phone's own encoder works, and falls back to the proven slow MPEG-4.",
    ),
    ExportEncoderOption(
        "classic",
        "Classic auto (hisi, then c2, then MPEG-4)",
        "The old 0.0.11 order, kept only to compare against the new native path. Slow to start here (two stalls).",
    ),
    ExportEncoderOption(
        "mpeg4",
        "MPEG-4 only",
        "Straight to the proven software encoder. Slow (about 13 minutes for a 6-minute video) but it always finishes.",
    ),
    ExportEncoderOption(
        "ffmpeg-hisi",
        "H.264 hisi only (manual test)",
        "The phone chip's encoder through FFmpeg. Stalled after 4 frames in the last log; falls back to MPEG-4.",
    ),
    ExportEncoderOption(
        "ffmpeg-c2",
        "H.264 c2 only (manual test)",
        "Android's software H.264 through FFmpeg. Stalled after 5 frames in the last log; falls back to MPEG-4.",
    ),
    ExportEncoderOption(
        "ffmpeg-google",
        "H.264 Google only (manual test)",
        "Google's software H.264 encoder, an experiment on this Huawei - unproven. Falls back to MPEG-4 if it stalls.",
    ),
    ExportEncoderOption(
        "native-h264",
        "Native H.264 only",
        "The phone's chip encoder, called directly (no FFmpeg). The fast hope; falls back to MPEG-4 if it fails.",
    ),
    ExportEncoderOption(
        "native-hevc",
        "Native HEVC only",
        "The phone's chip HEVC encoder, called directly. Smaller files than H.264; falls back to MPEG-4 if it fails.",
    ),
)

private const val SETTINGS = "capcraft-settings"
private const val EXPORT_ENCODER_KEY = "export-encoder"

/** The stored choice, or "auto" when nothing was ever chosen. */
fun exportEncoderSetting(context: Context): String {
    val stored = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        .getString(EXPORT_ENCODER_KEY, "auto") ?: "auto"
    return if (EXPORT_ENCODER_OPTIONS.any { it.id == stored }) stored else "auto"
}

/** The short title Settings and the export screen show for [id]. */
fun exportEncoderLabel(id: String): String =
    EXPORT_ENCODER_OPTIONS.firstOrNull { it.id == id }?.title ?: "Auto (native H.264, then MPEG-4)"

/** Stores the choice. Unknown ids are ignored, never stored. */
fun setExportEncoderSetting(context: Context, id: String) {
    if (EXPORT_ENCODER_OPTIONS.none { it.id == id }) return
    context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        .edit().putString(EXPORT_ENCODER_KEY, id).apply()
}
