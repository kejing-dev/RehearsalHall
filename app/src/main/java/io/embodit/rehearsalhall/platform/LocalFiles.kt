package io.embodit.rehearsalhall.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import io.embodit.rehearsalhall.domain.FileEntry
import io.embodit.rehearsalhall.domain.FileList
import java.io.File

/**
 * The headset's own files for the lectern's "本机文件" page (2026-11-27 user: "open a local doc / ppt and load it into
 * the prompter, like Spatial Office does"): Download and Documents are listed in the app, no system picker in the way.
 * Needs "All files access" (the same permission Spatial Office uses); the user allows it on the system page that
 * [requestAllFiles] opens. Without it the page says so and offers the system picker instead.
 */
object LocalFiles {
    val hasAccess: Boolean get() = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    private val roots: List<File>
        get() {
            val shared = Environment.getExternalStorageDirectory()
            return listOf(File(shared, Environment.DIRECTORY_DOWNLOADS), File(shared, Environment.DIRECTORY_DOCUMENTS))
        }

    /** Decks and scripts in Download and Documents (two folder levels deep), newest first. Empty without access. */
    fun list(): List<FileEntry> {
        if (!hasAccess) return emptyList()
        val found = ArrayList<FileEntry>()
        for (root in roots) walk(root, root.name, 0, found)
        return FileList.sort(found)
    }

    private fun walk(dir: File, where: String, depth: Int, out: MutableList<FileEntry>) {
        val kids = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (f in kids) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                if (depth < 2) walk(f, where + "/" + f.name, depth + 1, out)
            } else if (FileList.kind(f.name) != null) {
                out += FileEntry(f.absolutePath, f.name, where, f.length(), f.lastModified())
            }
        }
    }

    /** Copies a listed file into the app's cache (the importer works on app-private copies, like the picker's). */
    fun copyIn(context: Context, path: String): File? =
        runCatching {
            val src = File(path)
            val dir = File(context.cacheDir, "import/local-${System.currentTimeMillis()}").also { it.mkdirs() }
            File(dir, src.name).also { src.copyTo(it, overwrite = true) }
        }.onFailure { HallLog.e("FILES copy failed: $it", it) }.getOrNull()

    /** Opens the system page where the user can allow "All files access". False when the device has no such page. */
    fun requestAllFiles(context: Context): Boolean {
        val pkg = Uri.parse("package:${context.packageName}")
        for (action in listOf(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) {
            val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (action == Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION) i.data = pkg
            if (i.resolveActivity(context.packageManager) != null && runCatching { context.startActivity(i) }.isSuccess) return true
        }
        return false
    }
}
