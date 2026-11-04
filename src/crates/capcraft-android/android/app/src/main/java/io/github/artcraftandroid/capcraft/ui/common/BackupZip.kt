package io.github.artcraftandroid.capcraft.ui.common

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * KG fork (2026-11-04, Phase 1): backup and template files, as single
 * zip files, so a test-key reinstall does not delete everything.
 *
 * A backup zip holds, under one folder each: every project folder
 * (without its cache/, which is rebuildable), the template library,
 * and the imported looks library, plus a manifest.json naming what is
 * inside. It is published to Downloads/CapCraftBackup, a folder that
 * survives an uninstall, unlike the app's private files.
 *
 * Restore keeps every folder's original name: project documents hold
 * absolute paths into their own media/ folder, so renaming a restored
 * project would break its clips. A folder that already exists is
 * skipped, not overwritten or renamed, and the result names what was
 * restored and what was skipped.
 *
 * What is NOT in a backup, honestly: the engine's downloaded models
 * (filesDir/engine/models and the speech models), which are large and
 * re-downloadable; the app's settings beyond what a project holds;
 * and any file outside projects/, templates/ and effects/. Those are
 * lost on uninstall and must be re-downloaded or re-set.
 */

/** Zips [source] (a folder) into [zipFile], skipping [skipTop] top-level entries and *.part files. */
fun zipFolder(source: File, zipFile: File, skipTop: Set<String> = setOf("cache")) {
    zipFile.parentFile?.mkdirs()
    ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
        fun add(file: File, entryName: String) {
            if (file.isDirectory) {
                file.listFiles()?.sortedBy { it.name }?.forEach { add(it, "$entryName/${it.name}") }
            } else if (!file.name.endsWith(".part")) {
                zip.putNextEntry(ZipEntry(entryName))
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        if (source.isDirectory) {
            source.listFiles()?.sortedBy { it.name }?.forEach { child ->
                if (child.name !in skipTop) add(child, child.name)
            }
        }
    }
}

/** Unzips [zipFile] into [dest], refusing entries that escape it. */
fun unzipToFolder(zipFile: File, dest: File) {
    val destCanon = dest.canonicalFile
    ZipInputStream(FileInputStream(zipFile)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val out = File(destCanon, entry.name).canonicalFile
            require(out.path.startsWith(destCanon.path + File.separator) || out == destCanon) { "Bad zip entry: ${entry.name}" }
            if (entry.isDirectory) {
                out.mkdirs()
            } else {
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { zip.copyTo(it) }
            }
            entry = zip.nextEntry
        }
    }
}

private fun backupFileName(prefix: String): String =
    prefix + "-" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".zip"

/** Publishes [zipFile] to Downloads/[subfolder] and returns its MediaStore Uri, or null. */
fun publishZipToDownloads(context: Context, zipFile: File, subfolder: String): Uri? {
    return runCatching {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, zipFile.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/$subfolder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { out -> FileInputStream(zipFile).use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    }.getOrNull()
}

/** Builds a backup zip in the cache and publishes it to Downloads/CapCraftBackup. Returns a summary line. */
fun exportBackup(context: Context): String {
    val staging = File(context.cacheDir, "backup").apply { mkdirs() }
    val zip = File(staging, backupFileName("CapCraftBackup"))
    val root = File(context.filesDir, "backup-root").apply { mkdirs() }
    // Build the zip's tree under backup-root, then zip it, so entries
    // read projects/<folder>/..., templates/<name>/..., effects/<id>/...
    val projectsSrc = File(context.filesDir, "projects")
    val templatesSrc = File(context.filesDir, "engine/templates")
    val effectsSrc = File(context.filesDir, "engine/effects")
    var projectCount = 0
    var templateCount = 0
    var lookCount = 0
    fun copyTree(src: File, dest: File, skipCache: Boolean): Int {
        if (!src.exists()) return 0
        var count = 0
        src.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val target = File(dest, child.name)
                target.mkdirs()
                child.walkTopDown().forEach { f ->
                    if (f == child) return@forEach
                    val rel = f.relativeTo(child)
                    if (skipCache && rel.path.split(File.separator).firstOrNull() == "cache") return@forEach
                    if (f.name.endsWith(".part")) return@forEach
                    val out = File(target, rel.path)
                    if (f.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); f.copyTo(out, overwrite = true) }
                }
                count++
            }
        }
        return count
    }
    projectCount = copyTree(projectsSrc, File(root, "projects"), skipCache = true)
    templateCount = copyTree(templatesSrc, File(root, "templates"), skipCache = false)
    lookCount = copyTree(effectsSrc, File(root, "effects"), skipCache = false)
    File(root, "manifest.json").writeText(
        "{\"version\":1,\"app\":\"CapCraft\",\"projects\":$projectCount,\"templates\":$templateCount,\"looks\":$lookCount,\"note\":\"Restore keeps original folder names; existing folders are skipped.\"}"
    )
    zipFolder(root, zip, skipTop = emptySet())
    root.deleteRecursively()
    val uri = publishZipToDownloads(context, zip, "CapCraftBackup")
    return if (uri != null) {
        "Backup saved to Downloads/CapCraftBackup/${zip.name}: $projectCount projects, $templateCount templates, $lookCount looks. Check it in Files before you uninstall."
    } else {
        "Backup built at ${zip.absolutePath} but could not be published to Downloads; share it from the cache before uninstalling."
    }
}

/** Restores a backup zip picked by Uri. Returns a summary of restored and skipped folders. */
fun restoreBackup(context: Context, uri: Uri): String {
    val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
    val zip = File(staging, "backup.zip")
    context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(zip).use { input.copyTo(it) } }
        ?: return "Could not read the backup file."
    val tree = File(staging, "tree").apply { mkdirs() }
    unzipToFolder(zip, tree)
    val restored = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    fun restoreTree(kind: String, destRoot: File) {
        val src = File(tree, kind)
        if (!src.exists()) return
        src.listFiles()?.forEach { child ->
            if (!child.isDirectory) return@forEach
            val target = File(destRoot, child.name)
            if (target.exists()) {
                skipped.add("$kind/${child.name}")
            } else {
                target.parentFile?.mkdirs()
                child.copyRecursively(target)
                restored.add("$kind/${child.name}")
            }
        }
    }
    restoreTree("projects", File(context.filesDir, "projects"))
    restoreTree("templates", File(context.filesDir, "engine/templates"))
    restoreTree("effects", File(context.filesDir, "engine/effects"))
    staging.deleteRecursively()
    return buildString {
        append("Restored ${restored.size}: ${restored.joinToString(", ")}.")
        if (skipped.isNotEmpty()) append(" Skipped (already exists, not overwritten): ${skipped.joinToString(", ")}.")
        if (restored.isEmpty() && skipped.isEmpty()) append(" Nothing inside this backup was recognised.")
    }
}

/** Exports one template bundle folder as a single zip file, published to Downloads/CapCraftTemplates. */
fun exportTemplateFile(context: Context, templatePath: String): String {
    val folder = File(templatePath)
    if (!folder.isDirectory) return "That template folder was not found."
    val staging = File(context.cacheDir, "template-export").apply { mkdirs() }
    val safe = folder.name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
    val zip = File(staging, "$safe.zip")
    zipFolder(folder, zip, skipTop = emptySet())
    // zipFolder zips a folder's children, not the folder itself: a
    // template zip must hold the bundle's files at its root, which is
    // what an import expects (template.json at the root).
    val uri = publishZipToDownloads(context, zip, "CapCraftTemplates")
    return if (uri != null) {
        "Template file saved to Downloads/CapCraftTemplates/${zip.name}. Import it on any install from Templates > Import template file."
    } else {
        "Template file built at ${zip.absolutePath} but could not be published to Downloads."
    }
}

/** Imports a template zip file picked by Uri into the template library. Returns a summary line. */
fun importTemplateFile(context: Context, uri: Uri): String {
    val staging = File(context.cacheDir, "template-import").apply { deleteRecursively(); mkdirs() }
    val zip = File(staging, "template.zip")
    context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(zip).use { input.copyTo(it) } }
        ?: return "Could not read the template file."
    val tree = File(staging, "tree").apply { mkdirs() }
    unzipToFolder(zip, tree)
    // The bundle may be at the root (template.json here) or one folder
    // deep (a zip of a folder); find the folder holding template.json.
    val bundle = if (File(tree, "template.json").exists()) tree else tree.listFiles()
        ?.firstOrNull { it.isDirectory && File(it, "template.json").exists() }
        ?: return "That zip holds no template (no template.json inside)."
    val name = bundle.name.takeIf { it != "tree" } ?: "Imported template"
    val dest = File(context.filesDir, "engine/templates/$name")
    if (dest.exists()) return "A template named $name already exists; it was not overwritten."
    bundle.copyRecursively(dest)
    staging.deleteRecursively()
    return "Template imported: $name. It should now appear in the template list."
}
