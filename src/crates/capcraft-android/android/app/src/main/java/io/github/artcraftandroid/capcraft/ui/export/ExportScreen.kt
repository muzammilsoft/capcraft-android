package io.github.artcraftandroid.capcraft.ui.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.core.ExportListener
import io.github.artcraftandroid.capcraft.core.ExportSettings
import io.github.artcraftandroid.capcraft.core.GifSettings
import io.github.artcraftandroid.capcraft.core.Motion
import io.github.artcraftandroid.capcraft.core.cancelExport
import io.github.artcraftandroid.capcraft.core.exportPath
import io.github.artcraftandroid.capcraft.core.exportSize
import io.github.artcraftandroid.capcraft.core.startExport
import io.github.artcraftandroid.capcraft.core.startGifExport
import io.github.artcraftandroid.capcraft.engine.ExportService
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.intoBitmap
import io.github.artcraftandroid.capcraft.engine.userMessage
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import io.github.artcraftandroid.capcraft.ui.editor.exportAudioKbps
import io.github.artcraftandroid.capcraft.ui.editor.exportKbps
import io.github.artcraftandroid.capcraft.ui.editor.exportResolutionOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Stage {
    data class Running(val fraction: Float) : Stage
    data class Done(val uri: Uri?) : Stage
    data class Failed(val message: String) : Stage
}

/**
 * Exporting: the cover with the progress around it while the engine writes the file, then the
 * finished video saved to the phone's Movies folder, with sharing.
 */
@Composable
fun ExportScreen(vm: EditorViewModel, onClose: () -> Unit, onHome: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<Stage>(Stage.Running(0f)) }
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    // a new try after a failure starts the export again
    var attempt by remember { mutableIntStateOf(0) }
    // Phase 3: the encoder this export runs on, from Settings; the
    // export log says which one actually produced the file.
    val encoderChoice = remember { exportEncoderSetting(context) }
    val encoderLabel = remember { exportEncoderLabel(encoderChoice) }
    // the export log, read from this run's engine log file when a failure asks for it
    var showLog by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    // the notification an export in the background shows its progress in; asked for once
    val notify = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) {}

    BackHandler {
        if (showLog) {
            showLog = false
        } else {
            if (stage is Stage.Running) {
                runCatching { cancelExport() }
            }
            onClose()
        }
    }

    LaunchedEffect(attempt) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { notify.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
        val s = vm.session ?: return@LaunchedEffect
        val t = vm.timeline ?: return@LaunchedEffect
        if (vm.exportPhoto) {
            // a photo: the frame under the playhead at the chosen size, saved straight to Pictures
            val size = exportSize(t.width, t.height, exportResolutionOf(vm).toUInt())
            val at = vm.playhead.coerceIn(0, (t.durationUs - 1).coerceAtLeast(0))
            val picture = withContext(Threads.preview) {
                runCatching { s.frame(at, size[0], size[1], Motion.STILL).intoBitmap(null) }.getOrNull()
            }
            cover = picture
            val uri = picture?.let { withContext(Dispatchers.IO) { publishPhoto(context, it) } }
            stage = if (uri != null) Stage.Done(uri) else Stage.Failed("The photo could not be saved.")
            return@LaunchedEffect
        }
        cover = withContext(Threads.preview) {
            runCatching {
                val w = 360
                val h = (w * t.height.toFloat() / t.width.toFloat()).roundToInt() / 2 * 2
                s.frame(0, w.toUInt(), h.coerceAtLeast(2).toUInt(), Motion.STILL).intoBitmap(null)
            }.getOrNull()
        }
        val short = min(t.width.toInt(), t.height.toInt())
        val res = exportResolutionOf(vm)
        val size = exportSize(t.width, t.height, res.toUInt())
        val kbps = exportKbps(vm, size[0].toInt(), size[1].toInt())
        val gif = vm.exportGif
        val name = "CapCraft_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + if (gif) ".gif" else ".mp4"
        val out = withContext(Threads.commands) { exportPath(name) }
        val appContext = context.applicationContext
        val listener = object : ExportListener {
            override fun onProgress(fraction: Float) {
                ExportService.progress(appContext, fraction)
                scope.launch(Dispatchers.Main) { progress = fraction }
            }

            override fun onDone(path: String) {
                scope.launch {
                    val uri = withContext(Dispatchers.IO) { if (gif) publishGif(appContext, File(path)) else publish(appContext, File(path)) }
                    ExportService.finish(appContext, if (gif) "Saved to Pictures" else "Saved to your device")
                    stage = Stage.Done(uri)
                }
            }

            override fun onFailed(message: String, cancelled: Boolean) {
                ExportService.finish(appContext, if (cancelled) null else "Export failed")
                scope.launch(Dispatchers.Main) {
                    if (cancelled) onClose() else stage = Stage.Failed(message)
                }
            }
        }
        // the app may go to the background now: the service keeps it going
        ExportService.start(appContext)
        val started = withContext(Threads.commands) {
            runCatching {
                if (gif) {
                    startGifExport(s, GifSettings(out, vm.gifSize.toUInt(), vm.gifFps.toUInt()), listener)
                } else {
                    // Phase 2: the native encoder list is written AFTER
                    // the export starts, not before it, so its cost is
                    // never part of the export's startup delay. The log
                    // view reads the newest file whenever it is opened,
                    // and a failure takes seconds to surface, so the list
                    // is there by the time anyone looks for it.
                    startExport(
                        s,
                        ExportSettings(res.toUInt(), vm.exportFps.toUInt(), kbps.toUInt(), exportAudioKbps(vm).toUInt(), false, encoderChoice, out),
                        listener,
                    )
                    runCatching { writeNativeEncoderList(appContext) }
                }
            }
        }
        started.onFailure {
            ExportService.finish(appContext, null)
            stage = Stage.Failed(it.userMessage)
        }
    }

    // keep the screen on while exporting
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(stage is Stage.Running) {
        view.keepScreenOn = stage is Stage.Running
        onDispose { view.keepScreenOn = false }
    }

    // the page takes its colour from the video, deepening to black at the foot
    val tone = remember(cover) {
        cover?.let { c ->
            val one = android.graphics.Bitmap.createScaledBitmap(c, 1, 1, true).getPixel(0, 0)
            Color(android.graphics.Color.red(one) / 255f * 0.45f, android.graphics.Color.green(one) / 255f * 0.45f, android.graphics.Color.blue(one) / 255f * 0.45f)
        } ?: Color(0xFF1A1A1A)
    }
    Column(
        Modifier.fillMaxSize()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(tone, Color(0xFF0D0D0D))))
            .statusBarsPadding().navigationBarsPadding()
            .clickable(enabled = false) {},
    ) {
        Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable {
                if (showLog) showLog = false
                else {
                    if (stage is Stage.Running) runCatching { cancelExport() }
                    onClose()
                }
            }, contentAlignment = Alignment.Center) {
                Glyph(if (stage is Stage.Done) LucideIcons.ChevronLeft else LucideIcons.X, Palette.Text, size = 26.dp)
            }
            Spacer(Modifier.weight(1f))
            if (stage is Stage.Done) {
                Text("Done", style = Type.BodyStrong, modifier = Modifier.clickable(onClick = onHome).padding(12.dp))
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            val b = cover
            val t = vm.timeline
            val aspect = if (t != null && t.height > 0u) t.width.toFloat() / t.height.toFloat() else 9f / 16f
            Box(
                Modifier.height(300.dp).aspectRatio(aspect.coerceIn(0.4f, 2.2f)).clip(RoundedCornerShape(10.dp)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val st = stage
                if (st is Stage.Done && st.uri != null && !vm.exportGif && !vm.exportPhoto) {
                    // the finished video, playing quietly in place of the cover
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            android.widget.VideoView(ctx).apply {
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    mp.setVolume(0f, 0f)
                                    start()
                                }
                                setVideoURI(st.uri)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (b != null) {
                    Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                if (stage is Stage.Running) {
                    // the share done, written over the dimmed cover
                    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                        Text("${(progress * 100).roundToInt()}%", style = Type.Title.copy(fontSize = 30.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                    }
                    Box(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(Palette.Accent),
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            when (val st = stage) {
                is Stage.Running -> {
                    // a tip between two rules, as the page keeps the user waiting
                    Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).height(1.dp).background(Color(0x33FFFFFF)))
                        Glyph(LucideIcons.Lightbulb, Palette.TextSecondary, size = 16.dp, modifier = Modifier.padding(horizontal = 8.dp))
                        Text(
                            "You can leave CapCraft while the video saves",
                            style = Type.Small.copy(textAlign = TextAlign.Center),
                            modifier = Modifier.width(240.dp),
                        )
                        Box(Modifier.padding(start = 8.dp).weight(1f).height(1.dp).background(Color(0x33FFFFFF)))
                    }
                    // Phase 3: which encoder was selected (the log says
                    // which one actually produced the file).
                    Spacer(Modifier.height(12.dp))
                    Text("Encoder: $encoderLabel", style = Type.Small.copy(textAlign = TextAlign.Center), modifier = Modifier.padding(horizontal = 32.dp))
                }
                is Stage.Done -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Glyph(LucideIcons.CircleCheck, Palette.Accent, size = 20.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(if (vm.exportGif || vm.exportPhoto) "Saved to Pictures" else "Saved to your device", style = Type.BodyStrong.copy(fontSize = 16.sp))
                    }
                    Spacer(Modifier.height(26.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                        ShareTarget(LucideIcons.Share2, "Share") { st.uri?.let { share(context, it) } }
                        ShareTarget(LucideIcons.ExternalLink, "Open") { st.uri?.let { open(context, it, vm.exportGif, vm.exportPhoto) } }
                    }
                    // Phase 2: the export log is also reachable after a
                    // slow-but-successful export, not only after a
                    // failure - that is exactly when the startup-delay
                    // timings in it are needed.
                    Spacer(Modifier.height(20.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x22FFFFFF)).clickable {
                            logText = loadExportLog(context)
                            showLog = true
                        }.padding(horizontal = 24.dp, vertical = 10.dp),
                    ) { Text("View export log", style = Type.BodyStrong) }
                }
                is Stage.Failed -> {
                    Text("Export failed", style = Type.Title)
                    Spacer(Modifier.height(8.dp))
                    Text(st.message, style = Type.Small.copy(textAlign = TextAlign.Center), modifier = Modifier.padding(horizontal = 32.dp))
                    Spacer(Modifier.height(20.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.Accent).clickable {
                            progress = 0f
                            stage = Stage.Running(0f)
                            attempt++
                        }.padding(horizontal = 24.dp, vertical = 10.dp),
                    ) { Text("Try again", style = Type.BodyStrong.copy(color = Color.White)) }
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x22FFFFFF)).clickable {
                            logText = loadExportLog(context)
                            showLog = true
                        }.padding(horizontal = 24.dp, vertical = 10.dp),
                    ) { Text("View export log", style = Type.BodyStrong) }
                }
            }
        }
        if (stage is Stage.Done) {
            Box(
                Modifier.padding(16.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x22FFFFFF)).clickable(onClick = onHome),
                contentAlignment = Alignment.Center,
            ) { Text("New project", style = Type.BodyStrong) }
        }
    }
    if (showLog) {
        // The export log, full screen: what the engine wrote during this
        // run's export, filtered to export and encoder lines, so a
        // failure can be read and shared instead of guessed at.
        Column(
            Modifier.fillMaxSize().background(Color(0xFF0D0D0D)).statusBarsPadding().navigationBarsPadding().padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Export log", style = Type.BodyStrong.copy(fontSize = 18.sp), modifier = Modifier.weight(1f))
                Text("Close", style = Type.BodyStrong, modifier = Modifier.clickable { showLog = false }.padding(12.dp))
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(logText, style = Type.Small.copy(fontSize = 12.sp))
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Accent).clickable {
                    shareExportLog(context, logText)
                },
                contentAlignment = Alignment.Center,
            ) { Text("Share export log", style = Type.BodyStrong.copy(color = Color.White)) }
        }
    }
}

/** One way to send the finished video on: a round button and its name under it. */
@Composable
private fun ShareTarget(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)).background(Color(0x26FFFFFF)), contentAlignment = Alignment.Center) {
            Glyph(icon, Palette.Text, size = 22.dp)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = Type.Small.copy(color = Palette.Text))
    }
}

/** Opens the saved file in whatever app the phone shows videos or pictures with. */
private fun open(context: Context, uri: Uri, gif: Boolean, photo: Boolean = false) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, if (photo) "image/jpeg" else if (gif) "image/gif" else "video/mp4")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Saves a still as a JPEG in Pictures/CapCraft. */
private fun publishPhoto(context: Context, picture: Bitmap): Uri? = runCatching {
    val name = "CapCraft_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CapCraft")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        ?: return@runCatching null
    resolver.openOutputStream(uri)?.use { out -> picture.compress(Bitmap.CompressFormat.JPEG, 95, out) }
    values.clear()
    values.put(MediaStore.Images.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    uri
}.getOrNull()

/** Copies a finished GIF into Pictures/CapCraft and deletes the cache copy. */
private fun publishGif(context: Context, file: File): Uri? = runCatching {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CapCraft")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        ?: return@runCatching null
    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } }
    values.clear()
    values.put(MediaStore.Images.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    file.delete()
    uri
}.getOrNull()

/** Copies the finished file into Movies/CapCraft and deletes the cache copy. */
private fun publish(context: Context, file: File): Uri? = runCatching {
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CapCraft")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        ?: return@runCatching null
    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } }
    values.clear()
    values.put(MediaStore.Video.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    file.delete()
    uri
}.getOrNull()

/**
 * The export lines from this run's engine log file, in the app's private
 * engine folder, or a plain sentence saying why there are none. The file
 * is named by the run's start time, so the newest one is this run's.
 * The first lines (build, system, cpu) are kept, then every line about
 * the export, its encoder, its GPU or a failure, capped at 300 lines so
 * the screen stays readable.
 */
/**
 * Phase 1 native encoder diagnostics: the phone's own MediaCodec
 * encoder list, through Android's API (not Gbox, not FFmpeg's wrapper),
 * written to a file the export log view can show and the user can
 * share. Names, MIME type, and hardware vs software, for video/avc
 * first, then every other video encoder on the phone.
 */
private fun writeNativeEncoderList(context: Context) {
    runCatching {
        val folder = File(context.filesDir, "engine/logs").apply { mkdirs() }
        val name = "native-encoders-" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        val lines = mutableListOf<String>()
        lines.add("native encoders (Android MediaCodecList; Phase 3 order: Auto is native H.264 then MPEG-4, Classic is hisi, c2, MPEG-4, Google is a manual test option only):")
        val codecs = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos
            .filter { it.isEncoder }
        val avc = codecs.filter { it.supportedTypes.contains("video/avc") }
        val others = codecs.filter { !it.supportedTypes.contains("video/avc") && it.supportedTypes.any { type -> type.startsWith("video/") } }
        for (info in avc + others) {
            val hardware = if (android.os.Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else null
            val software = if (android.os.Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly else null
            lines.add("native-encoder: ${info.name} types=${info.supportedTypes.joinToString(",")} hardware=$hardware softwareOnly=$software vendor=${info.isVendor}")
        }
        if (lines.size == 1) lines.add("native-encoder: none found")
        File(folder, name).writeText(lines.joinToString("\n"))
    }
}

private fun loadExportLog(context: Context): String {
    return runCatching {
        val folder = File(context.filesDir, "engine/logs")
        val native = folder.listFiles { f -> f.name.startsWith("native-encoders-") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name }?.firstOrNull()?.readText()
        val engine = runCatching {
            val files = folder.listFiles { f -> f.name.startsWith("concat-") && f.name.endsWith(".log") }
                ?.sortedByDescending { it.name } ?: return@runCatching "No engine log folder found."
            val latest = files.firstOrNull() ?: return@runCatching "No engine log file found."
            val lines = latest.readLines()
            val kept = mutableListOf<String>()
            kept.addAll(lines.take(3))
            val wanted = listOf("export", "encoder", "mediacodec", "would not open", "stalled", "starting again", "rendering", "GPU", "compositor", "failed", "panic")
            for (line in lines) {
                if (wanted.any { line.contains(it, ignoreCase = true) }) kept.add(line)
                if (kept.size >= 300) break
            }
            if (kept.isEmpty()) "No export lines in ${latest.name}." else kept.joinToString("\n")
        }.getOrElse { "Could not read the export log: ${it.message}" }
        if (native != null) "$native\n\n$engine" else engine
    }.getOrElse { "Could not read the export log: ${it.message}" }
}

/** Writes the export log to the cache exports folder and shares it. */
private fun shareExportLog(context: Context, text: String) {
    runCatching {
        val folder = File(context.cacheDir, "exports").apply { mkdirs() }
        val name = "export-log-" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        val file = File(folder, name).apply { writeText(text) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null))
    }
}

private fun share(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = context.contentResolver.getType(uri) ?: "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}
