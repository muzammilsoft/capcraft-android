# AGENT-CHANGELOG.md

Change log for agent-built versions of this fork, newest first.
Each entry: what changed and why, in KG's terms.

## v0.0.11-beta — Phase 2 (2026-11-05)

KG ordered all of Phase 2 built together, after 0.0.10-beta proved
itself on his phone (export size solved, export startup delay still
open, templates UI poor).

- **Templates show a preview.** Every template entry now shows its
  layout at a glance: the bundle's poster picture when it has one,
  otherwise a drawn schematic (video slot left, text blocks right
  for the built-in split screen; one bar per slot for other
  templates). Why: the list used to show only a name, so KG could
  not tell templates apart.
- **Built-in template renamed "Split screen".** No app or platform
  name in the UI any more. Why: KG said the built-in template must
  not be branded after another app/platform. Devices that already
  have it as "KG Bilibili split" get the bundle renamed in place
  (folder + name inside template.json), so the library never holds
  both names.
- **Templates list hides when empty.** No header, no empty-state
  line. Why: KG said a useless section should not be shown when
  there are no templates. The create/save rows stay visible, since
  they are how the first template comes to exist.
- **Backup/restore in Settings only.** Removed from the Templates
  panel (Settings already had it since Phase 1). Why: KG's order.
- **Editable vector shapes.** A new shape kind, "vector": a polygon
  whose points are drawn in order and closed, with a corner-roundness
  slider and per-shape fill/stroke. Five presets in the Stickers
  shelf (pentagon, chevron, speech bubble, right triangle, house),
  and a Vector panel to drag points, tap to add a point on the
  nearest edge, delete a point, round corners, and stretch Width /
  Height non-uniformly (a square pulled from one side becomes a
  rectangle, pulled far becomes a line). Why: KG's motion-graphics
  shapes must be vectors he can reshape, not fixed stickers; shapes
  travel inside templates as ordinary clips, so no template-code
  change was needed.
- **Motion graph (multi-point curves).** A value-vs-time graph for
  one animated property (Position X/Y, Scale, Rotation, Opacity):
  every keyframe is a point to drag, tap empty space to add one,
  tap one to delete it, plus 7 presets (Linear, Ease in/out/in-out,
  Overshoot, Bounce, Hold then go) laid over the clip as point
  lists. Why: the project had curves only for speed; KG wanted
  curves between keyframes for the animated properties, with ready
  presets and custom multi-point editing, in the way After Effects'
  value graph and Alight Motion's per-segment easing do it. The
  bend between two points is still the per-segment Bezier ease set
  in Graphs; this panel is the points.
- **Export startup-delay diagnosis.** Stage timing logs
  (`export: timing <stage> <ms>ms`) at titles, flatten, background,
  validate-probe, compositor, build-timeline, before-picture and
  render total, visible in the export-log view - now also after a
  successful export, which is exactly when a slow start needs
  diagnosing. The native encoder list is written after the export
  starts, so its cost is never part of the startup delay. Why: KG
  reports exports take long to START even for short videos, cause
  unknown, and the app showed no timings. These are log-only; the
  working export path, encoder order and MPEG-4 fallback are
  untouched, so the fix for the delay itself waits on one slow
  export's log from his phone.
- **Preview frame follows stretch.** A stretched shape is now framed
  stretched in the preview, as its still is.

Kept from Phase 1, unchanged: encoder order (hisi, c2, MPEG-4, no
Google), ExportSheet MBPS stops [1,2,3,5,10,20], presets Small /
Balanced / Quality, backup to Downloads/CapCraftBackup + restore +
template zip export/import, 10-minute long-clip target, stable test
key (release stays DRAFT, not published).

## v0.0.10-beta — Phase 1 (2026-11-04)

Native encoder order (hisi, c2, MPEG-4, no Google encoder), export
presets (Small 720p24 1.5 Mbps, Balanced 1080p24 2 Mbps, Quality
1080p30 3 Mbps) and MBPS stops [1,2,3,5,10,20], Bilibili split
template, backup to Downloads/CapCraftBackup + restore, template zip
export/import, stable test key (CAPCRAFT_KEYSTORE_BASE64). Device
result: export size solved for small clips (30 s at 4.4 MB lowest /
33 MB highest), export startup delay remained.
