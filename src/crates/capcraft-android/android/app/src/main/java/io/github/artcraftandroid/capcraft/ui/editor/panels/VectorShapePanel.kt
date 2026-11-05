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

/** The points of a vector shape's style JSON, as fractions of its size, centred. */
private fun parsePoints(style: JSONObject): List<Pair<Double, Double>> {
    val array = style.optJSONArray("points") ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val p = array.optJSONArray(i) ?: return@mapNotNull null
        if (p.length() < 2) null else p.optDouble(0) to p.optDouble(1)
    }
}

private fun pointsJson(points: List<Pair<Double, Double>>): JSONArray =
    JSONArray().apply {
        points.forEach { (x, y) -> put(JSONArray().apply { put(x); put(y) }) }
    }

/**
 * Phase 2: a vector shape's geometry, edited by hand. The polygon is
 * drawn big: drag a point to move it, tap empty space to add a point
 * on the nearest edge, tap a point to pick it and delete it. Corners
 * round together with one slider, and Width / Height stretch the shape
 * from its sides without touching its points - a square pulled from
 * one side becomes a rectangle, pulled far becomes a line - because
 * the stretch multiplies the shape's own width and height, not its
 * uniform scale. Fill and outline stay in Style.
 */
@Composable
fun VectorShapePanel(vm: EditorViewModel) {
    val clip = vm.selectedClip?.takeIf { it.kind == "shape" } ?: run { vm.panel = null; return }
    val style = remember(clip.json) { vm.clipJson(clip.id)?.optJSONObject("shape") ?: JSONObject() }
    if (style.optString("kind") != "vector") {
        PanelFrame("Vector shape", vm, height = 180.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    "This shape is a fixed figure, so it has no points to edit. Add a vector shape from Stickers to draw your own.",
                    style = Type.Small.copy(fontSize = 13.sp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Add a vector shape",
                    style = Type.BodyStrong.copy(color = Palette.Accent),
                    modifier = Modifier.clickable {
                        vm.addVectorShape(
                            listOf(-0.5 to -0.5, 0.5 to -0.5, 0.5 to 0.5, -0.5 to 0.5),
                            "Vector",
                        )
                        vm.panel = null
                    }.padding(8.dp),
                )
            }
        }
        return
    }
    var points by remember(clip.id, clip.json) { mutableStateOf(parsePoints(style)) }
    var chosen by remember(clip.id) { mutableIntStateOf(-1) }
    var corner by remember(clip.id, clip.json) { mutableStateOf(style.optDouble("cornerRadius", 0.0)) }
    val json = remember(clip.json) { vm.clipJson(clip.id) ?: JSONObject() }
    var stretchX by remember(clip.id, clip.json) { mutableStateOf(json.optDouble("stretchX", 1.0).toFloat()) }
    var stretchY by remember(clip.id, clip.json) { mutableStateOf(json.optDouble("stretchY", 1.0).toFloat()) }

    fun send(next: List<Pair<Double, Double>>, radius: Double) {
        points = next
        corner = radius
        val shape = JSONObject(style.toString()).put("points", pointsJson(next)).put("cornerRadius", radius)
        vm.patch(clip.id, JSONObject().put("shape", shape), "vector-shape")
    }

    fun sendStretch(x: Float, y: Float) {
        stretchX = x
        stretchY = y
        vm.command(
            JSONObject().put("op", "setClipTransform").put("clipId", clip.id)
                .put("stretchX", x.toDouble()).put("stretchY", y.toDouble()),
            "vector-stretch",
        )
    }

    PanelFrame("Vector shape", vm, tall = true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            val latest by rememberUpdatedState(points)
            Canvas(
                Modifier.fillMaxWidth().height(190.dp).padding(vertical = 8.dp)
                    .pointerInput(clip.id) {
                        detectTapGestures { tap ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            fun toPoint(o: Offset): Pair<Double, Double> =
                                (o.x / w * 1.2 - 0.6) to (o.y / h * 1.2 - 0.6)
                            fun toOffset(p: Pair<Double, Double>): Offset =
                                Offset(((p.first + 0.6) / 1.2 * w).toFloat(), ((p.second + 0.6) / 1.2 * h).toFloat())
                            val near = latest.indexOfFirst { (toOffset(it) - tap).getDistance() < 30.dp.toPx() }
                            if (near >= 0) {
                                chosen = near
                            } else {
                                // add on the nearest edge, so the polygon keeps its order
                                val tapPoint = toPoint(tap)
                                val next = if (latest.size < 3) {
                                    (latest + tapPoint).toMutableList()
                                } else {
                                    var best = 0
                                    var bestDist = Double.MAX_VALUE
                                    for (i in latest.indices) {
                                        val a = latest[i]
                                        val b = latest[(i + 1) % latest.size]
                                        val mid = (a.first + b.first) / 2 to (a.second + b.second) / 2
                                        val d = (mid.first - tapPoint.first) * (mid.first - tapPoint.first) +
                                            (mid.second - tapPoint.second) * (mid.second - tapPoint.second)
                                        if (d < bestDist) {
                                            bestDist = d
                                            best = i
                                        }
                                    }
                                    latest.toMutableList().apply { add(best + 1, tapPoint) }
                                }
                                chosen = next.indexOf(tapPoint)
                                send(next, corner)
                            }
                        }
                    }
                    .pointerInput(clip.id) {
                        var dragging = -1
                        detectDragGestures(
                            onDragStart = { down ->
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                fun toOffset(p: Pair<Double, Double>): Offset =
                                    Offset(((p.first + 0.6) / 1.2 * w).toFloat(), ((p.second + 0.6) / 1.2 * h).toFloat())
                                dragging = latest.withIndex().minByOrNull { (_, p) -> (toOffset(p) - down).getDistance() }?.index ?: -1
                                chosen = dragging
                            },
                            onDragEnd = { dragging = -1; vm.endGesture() },
                            onDragCancel = { dragging = -1; vm.endGesture() },
                        ) { change, _ ->
                            val i = dragging
                            if (i < 0) return@detectDragGestures
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val x = (change.position.x / w * 1.2 - 0.6).coerceIn(-1.0, 1.0)
                            val y = (change.position.y / h * 1.2 - 0.6).coerceIn(-1.0, 1.0)
                            val next = latest.toMutableList().apply { set(i, x to y) }
                            send(next, corner)
                        }
                    },
            ) {
                val w = size.width
                val h = size.height
                fun toOffset(p: Pair<Double, Double>): Offset =
                    Offset(((p.first + 0.6) / 1.2 * w).toFloat(), ((p.second + 0.6) / 1.2 * h).toFloat())
                // the frame's middle, for orientation
                drawLine(Color(0x22FFFFFF), Offset(w / 2, 0f), Offset(w / 2, h), 1.dp.toPx())
                drawLine(Color(0x22FFFFFF), Offset(0f, h / 2), Offset(w, h / 2), 1.dp.toPx())
                if (points.size >= 3) {
                    val path = Path()
                    points.forEachIndexed { i, p ->
                        val o = toOffset(p)
                        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                    }
                    path.close()
                    drawPath(path, Color.White.copy(alpha = 0.85f))
                    drawPath(path, Palette.Accent, style = Stroke(2.dp.toPx()))
                }
                points.forEachIndexed { i, p ->
                    val c = toOffset(p)
                    drawCircle(if (i == chosen) Palette.Accent else Color.White, radius = if (i == chosen) 8.dp.toPx() else 6.dp.toPx(), center = c)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${points.size} points - tap the shape to add one, drag to move it", style = Type.Small.copy(fontSize = 12.sp), modifier = Modifier.weight(1f))
                if (chosen in 0 until points.size && points.size > 3) {
                    Text(
                        "Delete point",
                        style = Type.Small.copy(color = Palette.Accent),
                        modifier = Modifier.clickable {
                            val next = points.filterIndexed { i, _ -> i != chosen }
                            chosen = -1
                            send(next, corner)
                        }.padding(8.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            ValueSlider(
                value = (corner * 100).toFloat().coerceIn(0f, 50f),
                range = 0f..50f,
                label = { "${it.roundToInt()}%" },
                onChange = { v -> send(points, (v / 100.0)) },
                onDone = { vm.endGesture() },
                title = "Corner roundness",
            )
            Spacer(Modifier.height(4.dp))
            ValueSlider(
                value = (stretchX * 100).toFloat().coerceIn(10f, 1000f),
                range = 10f..1000f,
                label = { "${it.roundToInt()}%" },
                onChange = { v -> sendStretch(v / 100f, stretchY) },
                onDone = { vm.endGesture() },
                title = "Width",
            )
            Spacer(Modifier.height(4.dp))
            ValueSlider(
                value = (stretchY * 100).toFloat().coerceIn(10f, 1000f),
                range = 10f..1000f,
                label = { "${it.roundToInt()}%" },
                onChange = { v -> sendStretch(stretchX, v / 100f) },
                onDone = { vm.endGesture() },
                title = "Height",
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}
