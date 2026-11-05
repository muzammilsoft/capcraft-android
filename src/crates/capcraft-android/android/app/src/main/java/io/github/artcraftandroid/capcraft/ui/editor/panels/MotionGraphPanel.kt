package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** One animated property the graph can show, and the value range it draws in. */
private data class MotionProperty(val name: String, val label: String, val min: Double, val max: Double, val unit: String)

private val MOTION_PROPERTIES = listOf(
    MotionProperty("offsetX", "Position X", -3.0, 3.0, ""),
    MotionProperty("offsetY", "Position Y", -3.0, 3.0, ""),
    MotionProperty("scale", "Scale", 0.05, 8.0, "x"),
    MotionProperty("rotation", "Rotation", -180.0, 180.0, "°"),
    MotionProperty("opacity", "Opacity", 0.0, 1.0, "%"),
)

/** A preset curve as points along the clip: (time 0..1, progress 0..1, may overshoot). */
private data class MotionPreset(val name: String, val points: List<Pair<Double, Double>>)

private val MOTION_PRESETS = listOf(
    MotionPreset("Linear", listOf(0.0 to 0.0, 1.0 to 1.0)),
    MotionPreset("Ease in", listOf(0.0 to 0.0, 0.7 to 0.3, 1.0 to 1.0)),
    MotionPreset("Ease out", listOf(0.0 to 0.0, 0.3 to 0.7, 1.0 to 1.0)),
    MotionPreset("Ease in-out", listOf(0.0 to 0.0, 0.25 to 0.1, 0.75 to 0.9, 1.0 to 1.0)),
    MotionPreset("Overshoot", listOf(0.0 to 0.0, 0.6 to 1.1, 0.85 to 0.95, 1.0 to 1.0)),
    MotionPreset("Bounce", listOf(0.0 to 0.0, 0.5 to 1.1, 0.7 to 0.85, 0.85 to 1.05, 1.0 to 1.0)),
    MotionPreset("Hold then go", listOf(0.0 to 0.0, 0.6 to 0.0, 1.0 to 1.0)),
)

/**
 * Phase 2: the multi-point curve for one animated property - the
 * motion graph After Effects and Alight Motion both offer, in this
 * codebase's terms. Time runs across the clip, the property's value
 * runs up it, and every keyframe of that property is a point: drag
 * one to move it, tap empty space to add one, tap one and delete it.
 * The presets lay a whole curve over the clip at once, as points
 * between the property's first and last values. The bend between
 * two points is still the per-segment ease Graphs sets: this panel
 * is the points, Graphs is the bend.
 */
@Composable
fun MotionGraphPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    var property by remember { mutableStateOf(MOTION_PROPERTIES[2]) }
    val json = remember(clip.json) { vm.clipJson(clip.id) ?: JSONObject() }
    val keysArray = json.optJSONArray("keys") ?: JSONArray()
    var chosen by remember(clip.id, property.name) { mutableIntStateOf(-1) }

    /** This property's keys, sorted by time, as (at, value). */
    fun readKeys(): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until keysArray.length()) {
            val k = keysArray.optJSONObject(i) ?: continue
            if (k.optString("property") != property.name) continue
            out += k.optDouble("at") to k.optDouble("value")
        }
        return out.sortedBy { it.first }
    }
    var points by remember(clip.id, clip.json, property.name) { mutableStateOf(readKeys()) }

    /** The property's value when it has no keys: its constant, from the clip. */
    val baseValue = when (property.name) {
        "scale" -> json.optDouble("scale", 1.0)
        "offsetX" -> json.optDouble("offsetX", 0.0)
        "offsetY" -> json.optDouble("offsetY", 0.0)
        "rotation" -> json.optDouble("rotation", 0.0)
        else -> json.optDouble("opacity", 1.0)
    }

    fun sendKey(at: Double, value: Double, gesture: String? = null) {
        vm.command(
            JSONObject().put("op", "setClipKey").put("clipId", clip.id).put("property", property.name)
                .put("at", at).put("value", value),
            gesture,
        )
    }

    fun sendPoints(next: List<Pair<Double, Double>>, gesture: String? = null) {
        points = next
        val batch = JSONArray()
        next.forEach { (at, value) ->
            batch.put(
                JSONObject().put("op", "setClipKey").put("clipId", clip.id).put("property", property.name)
                    .put("at", at).put("value", value),
            )
        }
        if (batch.length() > 0) vm.command(JSONObject().put("op", "batch").put("commands", batch), gesture)
    }

    fun applyPreset(preset: MotionPreset) {
        val start = points.firstOrNull()?.second ?: baseValue
        val end = points.lastOrNull()?.second ?: baseValue
        val next = preset.points.map { (at, progress) ->
            at to (start + (end - start) * progress).coerceIn(property.min, property.max)
        }
        sendPoints(next)
        chosen = -1
    }

    fun valueLabel(v: Double): String = when (property.name) {
        "opacity" -> "${(v * 100).roundToInt()}%"
        "scale" -> "${"%.2f".format(v)}x"
        "rotation" -> "${v.roundToInt()}°"
        else -> "${"%.2f".format(v)}"
    }

    PanelFrame("Motion graph", vm, tall = true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                MOTION_PROPERTIES.forEach { p ->
                    Text(
                        p.label,
                        style = Type.Small.copy(
                            color = if (p == property) Palette.Text else Palette.TextSecondary,
                            fontWeight = if (p == property) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                        ),
                        modifier = Modifier.clickable { property = p; chosen = -1 }.padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
            }
            val playheadAt = ((vm.playhead - clip.startUs).toDouble() / clip.durationUs.coerceAtLeast(1)).coerceIn(0.0, 1.0)
            val latest by rememberUpdatedState(points)
            Canvas(
                Modifier.fillMaxWidth().height(170.dp).padding(vertical = 8.dp)
                    .pointerInput(clip.id, property.name) {
                        detectTapGestures { tap ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            fun toOffset(at: Double, v: Double): Offset {
                                val y = ((property.max - v) / (property.max - property.min) * h).toFloat()
                                return Offset((at * w).toFloat(), y)
                            }
                            val near = latest.indexOfFirst { (at, v) -> (toOffset(at, v) - tap).getDistance() < 30.dp.toPx() }
                            if (near >= 0) {
                                chosen = near
                            } else {
                                // add a point where tapped, at the value the line has there
                                val at = (tap.x / w).toDouble().coerceIn(0.0, 1.0)
                                val value = (property.max - tap.y / h * (property.max - property.min)).coerceIn(property.min, property.max)
                                val next = (latest + (at to value)).sortedBy { it.first }
                                chosen = next.indexOf(at to value)
                                sendPoints(next)
                            }
                        }
                    }
                    .pointerInput(clip.id, property.name) {
                        var dragging = -1
                        detectDragGestures(
                            onDragStart = { down ->
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                fun toOffset(at: Double, v: Double): Offset {
                                    val y = ((property.max - v) / (property.max - property.min) * h).toFloat()
                                    return Offset((at * w).toFloat(), y)
                                }
                                dragging = latest.withIndex().minByOrNull { (_, p) -> (toOffset(p.first, p.second) - down).getDistance() }?.index ?: -1
                                chosen = dragging
                            },
                            onDragEnd = { dragging = -1; vm.endGesture() },
                            onDragCancel = { dragging = -1; vm.endGesture() },
                        ) { change, _ ->
                            val i = dragging
                            if (i < 0) return@detectDragGestures
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val now = latest
                            val lo = if (i == 0) 0.0 else now[i - 1].first + 0.01
                            val hi = if (i == now.size - 1) 1.0 else now[i + 1].first - 0.01
                            val at = (change.position.x / w).toDouble().coerceIn(lo, hi)
                            val value = (property.max - change.position.y / h * (property.max - property.min)).coerceIn(property.min, property.max)
                            val next = now.toMutableList().apply { set(i, at to value) }
                            // live-drag sends one key at a time, so the
                            // preview follows the finger without rewriting
                            // the whole ride on every move
                            points = next
                            sendKey(at, value, "motion-graph")
                        }
                    },
            ) {
                val w = size.width
                val h = size.height
                fun y(v: Double) = ((property.max - v) / (property.max - property.min) * h).toFloat()
                // range guides
                for (label in listOf(property.min, (property.min + property.max) / 2, property.max)) {
                    drawLine(Color(0x22FFFFFF), Offset(0f, y(label)), Offset(w, y(label)), 1.dp.toPx())
                }
                val shown = points.ifEmpty { listOf(0.0 to baseValue, 1.0 to baseValue) }
                val path = Path()
                shown.forEachIndexed { i, (at, v) ->
                    val x = (at * w).toFloat()
                    if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
                }
                drawPath(path, Color.White, style = Stroke(2.dp.toPx()))
                points.forEachIndexed { i, (at, v) ->
                    val c = Offset((at * w).toFloat(), y(v))
                    drawCircle(if (i == chosen) Palette.Accent else Color.White, radius = if (i == chosen) 8.dp.toPx() else 6.dp.toPx(), center = c)
                }
                val px = (playheadAt * w).toFloat()
                drawLine(Palette.Accent.copy(alpha = 0.8f), Offset(px, 0f), Offset(px, h), 1.5.dp.toPx())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val current = if (chosen in points.indices) points[chosen] else null
                Text(
                    current?.let { "Key at ${(it.first * 100).roundToInt()}%: ${valueLabel(it.second)}" }
                        ?: if (points.isEmpty()) "No keys yet on ${property.label} - tap the graph to add the first"
                        else "Tap a point to pick it, tap empty space to add one",
                    style = Type.Small.copy(fontSize = 12.sp),
                    modifier = Modifier.weight(1f),
                )
                if (current != null) {
                    Text(
                        "Delete key",
                        style = Type.Small.copy(color = Palette.Accent),
                        modifier = Modifier.clickable {
                            vm.command(
                                JSONObject().put("op", "clearClipKey").put("clipId", clip.id)
                                    .put("property", property.name).put("at", current.first),
                            )
                            chosen = -1
                        }.padding(8.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Curve presets", style = Type.BodyStrong.copy(fontSize = 14.sp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                MOTION_PRESETS.forEach { preset ->
                    Text(
                        preset.name,
                        style = Type.Small,
                        modifier = Modifier.clickable { applyPreset(preset) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
            Text(
                "A preset lays points from the first value to the last. The bend between two points is the ease set in Graphs.",
                style = Type.Small.copy(fontSize = 12.sp),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}
