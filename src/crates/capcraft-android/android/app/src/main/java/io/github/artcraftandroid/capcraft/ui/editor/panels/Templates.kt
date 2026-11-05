package io.github.artcraftandroid.capcraft.ui.editor.panels

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.core.TemplateInfoView
import io.github.artcraftandroid.capcraft.core.deleteTemplate
import io.github.artcraftandroid.capcraft.core.ensureKgBilibiliTemplate
import io.github.artcraftandroid.capcraft.core.instantiateTemplate
import io.github.artcraftandroid.capcraft.core.listTemplates
import io.github.artcraftandroid.capcraft.core.saveTemplate
import io.github.artcraftandroid.capcraft.engine.Media
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.userMessage
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.common.MediaKinds
import io.github.artcraftandroid.capcraft.ui.common.MediaPick
import io.github.artcraftandroid.capcraft.ui.common.exportTemplateFile
import io.github.artcraftandroid.capcraft.ui.common.importTemplateFile
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import io.github.artcraftandroid.capcraft.ui.editor.Panel
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 1: project templates, template files and backup.
 *
 * A template is a project with replaceable slots, not a finished
 * video. Save the open project as a template (mark a clip as a slot
 * first, below), apply a template by picking a clip for each video
 * slot, then open the new project from Home: the editor holds one
 * session, so applying creates the project rather than switching to
 * it in place. Text slots (title, notes, question) are ordinary title
 * clips in the new project: tap one in the editor and retype it.
 * A fill clip is copied into the project you picked it from, and the
 * new project refers to that copy, so keep the picking project until
 * the new one is exported; Phase 3 will copy fills into the new
 * project itself.
 *
 * Phase 2: every template shows a preview of its layout (its poster
 * picture when the bundle has one, a drawn schematic of its slots
 * otherwise), the built-in template is named neutrally ("Split
 * screen"), and the list itself is hidden when there are no templates
 * at all. Backup lives in Settings only now, not here.
 */
@Composable
fun TemplatesPanel(vm: EditorViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var templates by remember { mutableStateOf<List<TemplateInfoView>>(emptyList()) }
    var templateName by remember { mutableStateOf("") }
    var newProjectName by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<TemplateInfoView?>(null) }
    var fills by remember { mutableStateOf<Map<String, Uri>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch(Threads.commands) {
            val list = runCatching { listTemplates() }.getOrDefault(emptyList())
            withContext(kotlinx.coroutines.Dispatchers.Main) { templates = list }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }

    val importTemplate = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Threads.commands) {
            val result = runCatching { importTemplateFile(context, uri) }.getOrElse { it.userMessage }
            withContext(kotlinx.coroutines.Dispatchers.Main) { vm.message = result; refresh() }
        }
    }

    PanelFrame("Templates", vm, tall = true) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(
                "A template is a layout with slots. Pick a video for each slot, apply, then open the new project from Home.",
                style = Type.Small.copy(fontSize = 13.sp),
            )
            Spacer(Modifier.height(12.dp))
            ActionRow("Create the split screen template") {
                if (busy) return@ActionRow
                busy = true
                scope.launch(Threads.commands) {
                    val result = runCatching { ensureKgBilibiliTemplate() }.fold(
                        onSuccess = { "Split screen is ready: ${it.width}x${it.height}, ${it.slots.size} video slot(s). Apply it below." },
                        onFailure = { it.userMessage },
                    )
                    withContext(kotlinx.coroutines.Dispatchers.Main) { vm.message = result; busy = false; refresh() }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Save this project as a template", style = Type.BodyStrong.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SearchField(templateName, { templateName = it }, "Template name") }
                Spacer(Modifier.width(8.dp))
                ActionButton("Save") {
                    val s = vm.session ?: return@ActionButton
                    if (templateName.isBlank()) { vm.message = "Give the template a name first"; return@ActionButton }
                    scope.launch(Threads.commands) {
                        val result = runCatching { saveTemplate(s, templateName) }.fold(
                            onSuccess = { "Template saved: ${it.name}." },
                            onFailure = { it.userMessage },
                        )
                        withContext(kotlinx.coroutines.Dispatchers.Main) { vm.message = result; refresh() }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Mark a clip as a slot first: tap a video clip below, then save. A slot keeps its place and length; only its file is replaced.",
                style = Type.Small.copy(fontSize = 13.sp),
            )
            val clips = vm.timeline?.rows?.flatMap { it.clips }?.filter { it.kind == "video" || it.kind == "image" } ?: emptyList()
            for (clip in clips) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(clip.name.ifBlank { clip.kind }, style = Type.BodyStrong.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
                    ActionButton("Mark as slot") {
                        vm.command(JSONObject().put("op", "setMediaPlaceholder").put("mediaId", clip.mediaId).put("placeholder", true))
                        vm.message = "Marked ${clip.name.ifBlank { clip.kind }} as a template slot. Save the project as a template now."
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // Phase 2: no templates, no list at all - not even the header
            // or an empty-state line. The create and save rows above are
            // how the first template comes to exist.
            if (templates.isNotEmpty()) {
                Text("Templates", style = Type.BodyStrong.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                for (template in templates) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        TemplatePreview(template)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(template.name, style = Type.BodyStrong.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
                            Text("${template.width}x${template.height}", style = Type.Small.copy(fontSize = 12.sp))
                        }
                        Text(
                            if (template.slots.isEmpty()) "No video slots (text and layout only)" else "Slots: ${template.slots.joinToString { it.name }}",
                            style = Type.Small.copy(fontSize = 12.sp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionButton(if (selected?.path == template.path) "Selected" else "Apply") {
                                selected = template
                                fills = emptyMap()
                                newProjectName = template.name + " copy"
                            }
                            ActionButton("Export file") {
                                scope.launch(Threads.commands) {
                                    val result = runCatching { exportTemplateFile(context, template.path) }.getOrElse { it.userMessage }
                                    withContext(kotlinx.coroutines.Dispatchers.Main) { vm.message = result }
                                }
                            }
                            ActionButton("Delete") {
                                scope.launch(Threads.commands) {
                                    runCatching { deleteTemplate(template.path) }
                                    withContext(kotlinx.coroutines.Dispatchers.Main) { refresh() }
                                }
                            }
                        }
                    }
                }
            }
            selected?.let { template ->
                Spacer(Modifier.height(12.dp))
                Text("Apply: ${template.name}", style = Type.BodyStrong.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SearchField(newProjectName, { newProjectName = it }, "New project name") }
                }
                for (slot in template.slots) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(slot.name, style = Type.BodyStrong.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
                        val picked = fills[slot.mediaId]
                        ActionButton(if (picked != null) "Picked" else "Pick clip") {
                            MediaPick.open(MediaKinds.BOTH, multiple = false) { uris ->
                                val uri = uris.firstOrNull() ?: return@open
                                fills = fills + (slot.mediaId to uri)
                            }
                        }
                    }
                }
                if (template.slots.isEmpty()) {
                    Text("This template has no video slots: it applies as-is, with its text clips to retype.", style = Type.Small.copy(fontSize = 13.sp))
                }
                Spacer(Modifier.height(8.dp))
                ActionRow("Create project from template") {
                    if (newProjectName.isBlank()) { vm.message = "Give the new project a name first"; return@ActionRow }
                    val missing = template.slots.filter { fills[it.mediaId] == null }
                    if (missing.isNotEmpty()) { vm.message = "Pick a clip for: ${missing.joinToString { it.name }}"; return@ActionRow }
                    scope.launch(Threads.commands) {
                        val result = runCatching {
                            val fillsJson = JSONArray().apply {
                                for (slot in template.slots) {
                                    val uri = fills[slot.mediaId]!!
                                    val file = Media.importUri(context, vm.path, uri)
                                    put(JSONObject().apply {
                                        put("mediaId", slot.mediaId)
                                        put("path", file.absolutePath)
                                        put("name", file.name)
                                        put("kind", if (slot.kind == "image") "image" else "video")
                                        put("hasAudio", true)
                                    })
                                }
                            }.toString()
                            instantiateTemplate(template.path, newProjectName, fillsJson)
                        }.fold(
                            onSuccess = { path -> "Project created: $newProjectName. Go back to Home and open it (it is at the top of recents)." },
                            onFailure = { it.userMessage },
                        )
                        withContext(kotlinx.coroutines.Dispatchers.Main) { vm.message = result; vm.panel = null }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Template files", style = Type.BodyStrong.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            ActionRow("Import template file (zip)") { importTemplate.launch(arrayOf("application/zip", "application/octet-stream")) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Phase 2: a template's layout at a glance. The bundle's poster
 * picture when it has one (decoded off the main thread, like a
 * project's cover on Home); otherwise a drawn schematic in the
 * template's own aspect ratio - the video slot on the left and the
 * text slots on the right for the built-in split screen, one bar per
 * slot for any other template. A schematic is a layout preview, not a
 * frame: it never pretends to show footage that is not there.
 */
@Composable
private fun TemplatePreview(template: TemplateInfoView) {
    val poster by produceState<android.graphics.Bitmap?>(null, template.path, template.hasPoster) {
        value = if (!template.hasPoster) null else withContext(Threads.thumbnails) {
            runCatching {
                val file = File(template.path, "poster.jpg")
                if (file.isFile) BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 }) else null
            }.getOrNull()
        }
    }
    val aspect = (template.width.toFloat() / template.height.coerceAtLeast(1).toFloat()).coerceIn(0.5f, 3f)
    Box(
        Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A2A)),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = poster
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (template.name == "Split screen") {
            // the built-in layout: video left, three text blocks right
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(0.55f).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(Color(0xFF5A7DFF)))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(0.45f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.85f)))
                    Box(Modifier.fillMaxWidth(0.8f).height(8.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.55f)))
                    Box(Modifier.fillMaxWidth(0.9f).height(8.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.55f)))
                }
            }
        } else {
            // any other template: one bar per slot, over a frame outline
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val slots = template.slots.ifEmpty { null }
                if (slots == null) {
                    Canvas(Modifier.fillMaxWidth().height(24.dp)) { drawRect(Color.White.copy(alpha = 0.4f)) }
                    Text("Layout only", style = Type.Small.copy(color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp))
                } else {
                    for (slot in slots) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF5A7DFF).copy(alpha = 0.8f)))
                            Spacer(Modifier.width(6.dp))
                            Text(slot.name, style = Type.Small.copy(color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Accent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.BodyStrong.copy(color = Palette.OnAccent, fontSize = 14.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.Chip)
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.BodyStrong.copy(fontSize = 13.sp))
    }
}
