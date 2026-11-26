package io.embodit.rehearsalhall.platform

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import java.io.File

/**
 * "Open with → 演讲排练厅" (I1): another app (file manager, a mail app, an office app) hands us a .pptx / .docx / .pdf
 * with `ACTION_VIEW` or `ACTION_SEND`. The file is copied into `cache/import/`, parked in [pending] and the hall is
 * started (or brought forward); the controller imports it once the hall is ready (S01) or right away if it already is.
 * Nothing here talks to the sending app: no private contract, no other app required.
 */
class OpenWithActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? =
            when (intent?.action) {
                Intent.ACTION_VIEW -> intent.data
                Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
                else -> null
            }
        val file = uri?.let { copy(it) }
        HallLog.i("OPENWITH action=${intent?.action} file=${file?.name}")
        if (file != null) {
            pending = listOf(file)
            onFiles?.invoke()
        }
        runCatching { startActivity(Intent(this, LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            .onFailure { HallLog.e("OPENWITH launch failed: $it", it) }
        finish()
    }

    private fun copy(uri: Uri): File? =
        runCatching {
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            val dir = File(cacheDir, "import/open-${System.currentTimeMillis()}").also { it.mkdirs() }
            val out = File(dir, name.replace('/', '_'))
            contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return null
            out
        }.onFailure { HallLog.e("OPENWITH copy failed: $it", it) }.getOrNull()

    companion object {
        /** Files handed over by another app, waiting for the hall to be ready. Taken by [take]. */
        @Volatile var pending: List<File>? = null

        /** Set by the controller: a file just arrived (called on the main thread). */
        @Volatile var onFiles: (() -> Unit)? = null

        fun take(): List<File>? = pending.also { pending = null }
    }
}
