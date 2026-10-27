package io.embodit.rehearsalhall.platform

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import java.io.File

/**
 * Transparent helper behind "导入 PDF 或图片…" and "导入备注" (design T3 / T4): a Stage has no Activity of its own to
 * receive a result, so this one opens the system picker (`ACTION_OPEN_DOCUMENT`), copies the chosen files into
 * `cache/import/<request>/` and finishes; [onResult] tells the controller. Whether the picker opens from a Full
 * Stage is a device check (design N4); without one the result is "none" and the debug `importPath` hook remains.
 */
class PickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val notes = intent.getBooleanExtra(EXTRA_NOTES, false)
        val pick =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, if (notes) arrayOf("text/plain") else arrayOf("application/pdf", "image/*"))
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, !notes)
            }
        val ok = runCatching { startActivityForResult(pick, REQUEST) }.isSuccess
        HallLog.i("IMPORT picker started=$ok notes=$notes")
        if (!ok) {
            deliver(PickResult.NoPicker)
            finish()
        }
    }

    @Deprecated("Activity result API of the platform Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST) return finish()
        val uris = ArrayList<Uri>()
        data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri }
        if (uris.isEmpty()) data?.data?.let { uris += it }
        if (resultCode != RESULT_OK || uris.isEmpty()) {
            deliver(PickResult.Cancelled)
            return finish()
        }
        val dir = File(cacheDir, "import/${System.currentTimeMillis()}").also { it.mkdirs() }
        val files =
            uris.mapNotNull { uri ->
                runCatching {
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
                    val out = File(dir, name.replace('/', '_'))
                    contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                    out
                }.onFailure { HallLog.e("IMPORT copy failed: $it", it) }.getOrNull()
            }
        HallLog.i("IMPORT picked ${files.map { it.name }}")
        deliver(if (files.isEmpty()) PickResult.Cancelled else PickResult.Files(files))
        finish()
    }

    sealed interface PickResult {
        data class Files(val files: List<File>) : PickResult

        data object Cancelled : PickResult

        data object NoPicker : PickResult
    }

    companion object {
        private const val REQUEST = 51
        const val EXTRA_NOTES = "notes"

        /** Set by the controller; called on the main thread. */
        @Volatile var onResult: ((PickResult) -> Unit)? = null

        private fun deliver(r: PickResult) {
            onResult?.invoke(r)
        }

        fun open(context: Context, notes: Boolean): Boolean =
            runCatching {
                context.startActivity(Intent(context, PickerActivity::class.java).putExtra(EXTRA_NOTES, notes).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
    }
}

/**
 * Transparent helper for the RECORD_AUDIO request (design S13 → system dialog). Whether the system dialog shows over
 * a Full Stage is a device check (N3); a refusal or no dialog leaves the switch off (M1) with a hint.
 */
class MicPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onResult?.invoke(true)
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        HallLog.i("MIC permission granted=$ok")
        onResult?.invoke(ok)
        finish()
    }

    companion object {
        private const val REQUEST = 52

        @Volatile var onResult: ((Boolean) -> Unit)? = null

        fun granted(context: Context): Boolean = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun open(context: Context): Boolean = runCatching { context.startActivity(Intent(context, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}

/**
 * Transparent helper for the eye-tracking permission `com.picovr.permission.EYE_TRACKING` (SDK doc "Eye tracking":
 * a runtime permission). Refused or no dialog: the gaze falls back to the head direction and the lectern panel says so.
 */
class EyePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (granted(this)) {
            onResult?.invoke(true)
            finish()
            return
        }
        requestPermissions(arrayOf(PERMISSION), REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        HallLog.i("EYE permission granted=$ok")
        onResult?.invoke(ok)
        finish()
    }

    companion object {
        const val PERMISSION = "com.picovr.permission.EYE_TRACKING"
        private const val REQUEST = 53

        @Volatile var onResult: ((Boolean) -> Unit)? = null

        fun granted(context: Context): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

        fun open(context: Context): Boolean = runCatching { context.startActivity(Intent(context, EyePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}
