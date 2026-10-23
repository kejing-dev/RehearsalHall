package io.embodit.rehearsalhall.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import io.embodit.rehearsalhall.domain.Deck
import java.io.File
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * PDF / images -> deck pages (design `deckModel.import`): PdfRenderer renders every page 1600 px wide (at most 60
 * pages); several images become pages sorted by file name. Runs on an IO dispatcher; [progress] gets (done, total).
 */
object Importer {
    const val WIDTH = 1600

    sealed interface Result {
        data class Ok(val deck: Deck, val capped: Boolean) : Result

        data object Password : Result

        data object NotSupported : Result

        data class Failed(val why: String) : Result
    }

    fun isPdf(f: File): Boolean = f.name.lowercase().endsWith(".pdf") || runCatching { f.inputStream().use { s -> String(ByteArray(5).also { s.read(it) }) == "%PDF-" } }.getOrDefault(false)

    fun isImage(f: File): Boolean = f.name.lowercase().let { n -> listOf(".png", ".jpg", ".jpeg", ".webp", ".bmp").any { n.endsWith(it) } }

    suspend fun import(store: Store, files: List<File>, id: String, progress: (Int, Int) -> Unit): Result {
        val dir = store.deckDir(id).also { it.deleteRecursively(); it.mkdirs() }
        try {
            val pdf = files.firstOrNull { isPdf(it) }
            val images = files.filter { isImage(it) }.sortedBy { it.name.lowercase() }
            val (count, capped, title) =
                when {
                    pdf != null -> renderPdf(store, pdf, id, progress) ?: return Result.Password
                    images.isNotEmpty() -> renderImages(store, images, id, progress)
                    else -> return Result.NotSupported
                }
            val deck = Deck(id, title, if (pdf != null) Deck.SOURCE_PDF else Deck.SOURCE_IMAGES, count, List(count) { "" }, count.coerceIn(1, 60), System.currentTimeMillis())
            store.saveDeck(deck)
            return Result.Ok(deck, capped)
        } catch (t: Throwable) {
            dir.deleteRecursively()
            if (t is kotlinx.coroutines.CancellationException) throw t
            HallLog.e("IMPORT failed: $t", t)
            return Result.Failed(t.toString())
        }
    }

    /** Null when the PDF is password protected (PdfRenderer throws SecurityException). */
    private suspend fun renderPdf(store: Store, pdf: File, id: String, progress: (Int, Int) -> Unit): Triple<Int, Boolean, String>? {
        val fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer =
            try {
                PdfRenderer(fd)
            } catch (e: SecurityException) {
                fd.close()
                return null
            }
        renderer.use { r ->
            val total = r.pageCount.coerceAtMost(Deck.MAX_PAGES)
            progress(0, total)
            for (i in 0 until total) {
                coroutineContext.ensureActive()
                r.openPage(i).use { page ->
                    val h = (WIDTH.toFloat() * page.height / page.width).toInt().coerceIn(1, 2400)
                    val bmp = Bitmap.createBitmap(WIDTH, h, Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    save(bmp, store.pageFile(id, i))
                    bmp.recycle()
                }
                progress(i + 1, total)
            }
            return Triple(total, r.pageCount > Deck.MAX_PAGES, pdf.nameWithoutExtension)
        }
    }

    private suspend fun renderImages(store: Store, images: List<File>, id: String, progress: (Int, Int) -> Unit): Triple<Int, Boolean, String> {
        val total = images.size.coerceAtMost(Deck.MAX_PAGES)
        progress(0, total)
        for (i in 0 until total) {
            coroutineContext.ensureActive()
            val src = BitmapFactory.decodeFile(images[i].absolutePath) ?: error("not an image: ${images[i].name}")
            val k = WIDTH.toFloat() / src.width
            val bmp = if (src.width > WIDTH) Bitmap.createScaledBitmap(src, WIDTH, (src.height * k).toInt().coerceAtLeast(1), true) else src
            save(bmp, store.pageFile(id, i))
            progress(i + 1, total)
        }
        return Triple(total, images.size > Deck.MAX_PAGES, images.first().nameWithoutExtension)
    }

    private fun save(bmp: Bitmap, f: File) {
        f.parentFile?.mkdirs()
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
