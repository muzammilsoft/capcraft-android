package io.github.artcraftandroid.capcraft.ui.editor.panels

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import io.github.artcraftandroid.capcraft.ui.common.exportBackup
import io.github.artcraftandroid.capcraft.ui.common.exportTemplateFile
import io.github.artcraftandroid.capcraft.ui.common.importTemplateFile
import io.github.artcraftandroid.capcraft.ui.common.restoreBackup
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import io.github.artcraftandroid.capcraft.ui.editor.Panel
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
 * Backup is here too, because it is what makes a reinstall safe:
 * export before you uninstall, restore after you reinstall, and check
 * the result in the message before you delete anything.
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
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Threads.commands) {
            val result = runCatching { restoreBackup(context, uri) }.getOrElse { it.userMessage }
            withContext(kotlinx.coroutines.Dispatchers.Main) { vm.message = result }
        }
    }

    PanelFrame("Templates", vm, tall = true) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(
                "A template is a layout with slots. Pick a video for each slot, apply, then open the new project from Home.",
                style = Type.Small.copy(fontSize = 13.sp),
            )
            Spacer(Modifier.height(12.dp))
            ActionRow("Create the KG Bilibili split template") {
                if (busy) return@ActionRow
                busy = true
                scope.launch(Threads.commands) {
                    val result = runCatching { ensureKgBilibiliTemplate() }.fold(
                        onSuccess = { "KG Bilibili split is ready: ${it.width}x${it.height}, ${it.slots.size} video slot(s). Apply it below." },
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
            Text("Templates", style = Type.BodyStrong.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            if (templates.isEmpty()) {
                Text("No templates yet. Create the KG Bilibili split above, or save this project as one.", style = Type.Small.copy(fontSize = 13.sp))
            }
            for (template in templates) {
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
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
            Text("Backup before reinstall", style = Type.BodyStrong.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            Text(
                "Export first, uninstall second, restore after reinstall. Backup holds projects (without cache), templates and looks; downloaded models are not included and must be re-downloaded.",
                style = Type.Small.copy(fontSize = 13.sp),
            )
            Spacer(Modifier.height(8.dp))
            ActionRow("Export backup to Downloads/CapCraftBackup") {
                scope.launch(Threads.commands) {
                    val result = runCatching { exportBackup(context) }.getOrElse { it.userMessage }
                    withContext(kotlinx.coroutines.Dispatchers.Main) { vm.message = result }
                }
            }
            Spacer(Modifier.height(8.dp))
            ActionRow("Restore backup (zip)") { restore.launch(arrayOf("application/zip", "application/octet-stream")) }
            Spacer(Modifier.height(16.dp))
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
