# AGENTS.md

Operating notes for future agents working on this fork
(muzammilsoft/capcraft-android, KG's personal CapCraft version).

## Standing rules (KG)
- After EVERY change: update this file and `docs/AGENT-CHANGELOG.md`
  (new version entry: what + why) before pushing.
- Pushes, releases and uploads only on KG's explicit order.
- Encoder order is binding: OMX.hisi.video.encoder.avc first, then
  c2.android.avc.encoder, then native MPEG-4 fallback last. NO
  OMX.google.h264.encoder anywhere, even as fallback. The hisi/c2
  attempts go through FFmpeg's h264_mediacodec pinned by component
  name; this is NOT a full native Java MediaCodec encoder.
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
