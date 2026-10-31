package io.github.artcraftandroid.capcraft

import android.app.Application
import android.content.ComponentCallbacks2
import android.system.Os
import io.github.artcraftandroid.capcraft.core.init
import io.github.artcraftandroid.capcraft.core.trimMemory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class CapCraftApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The engine, started once per process on a background thread. */
    val engine: Deferred<Result<Unit>> by lazy {
        scope.async {
            runCatching {
                // Rust's temporary folder and home on Android are not the app's own: point them at
                // it before the library first reads them.
                Os.setenv("TMPDIR", cacheDir.absolutePath, true)
                Os.setenv("HOME", filesDir.absolutePath, true)
                File(cacheDir, "exports").mkdirs()
                init(filesDir.absolutePath, cacheDir.absolutePath)
                // KG fork fix (2026-10-31): the voice model is bundled in the APK
                // (CI downloads it into app/src/main/assets before building;
                // a local builder places it there by hand), so
                // Isolate voice works with no network at all. The Rust side
                // (voice.rs model()) checks this exact file and its SHA-256
                // before it ever tries a download, and the download stays as
                // the fallback if this copy is missing or wrong. The model is
                // 535,638 bytes; a file of any other size is replaced.
                runCatching { installBundledVoiceModel() }
                    .onFailure { android.util.Log.w("CapCraft", "bundled voice model not installed", it) }
                NativeContext.start(this@CapCraftApplication)
                engineLoaded = true
            }
        }
    }

    /** Copies the bundled voice model into the engine folder Rust reads it from. */
    private fun installBundledVoiceModel() {
        val target = File(filesDir, "engine/speech-enhancement/gtcrn_simple.onnx")
        if (target.exists() && target.length() == 535_638L) return
        target.parentFile?.mkdirs()
        assets.open("gtcrn_simple.onnx").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        if (target.length() != 535_638L) {
            target.delete()
            error("bundled voice model has the wrong size")
        }
    }

    @Volatile
    private var engineLoaded = false

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && engineLoaded) {
            scope.launch { runCatching { trimMemory() } }
        }
    }
}
