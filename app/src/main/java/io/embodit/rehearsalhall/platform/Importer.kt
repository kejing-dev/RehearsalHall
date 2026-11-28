package io.embodit.rehearsalhall.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import io.embodit.rehearsalhall.domain.Deck
import io.embodit.rehearsalhall.domain.Script
import io.embodit.rehearsalhall.domain.SlideCheck
import io.embodit.rehearsalhall.platform.office.ScriptCard
import io.embodit.rehearsalhall.platform.office.SlidePainter
import io.embodit.spatialoffice.core.docx.DocxDocument
import io.embodit.spatialoffice.core.pptx.PptxDeck
import java.io.File
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * PDF / images / .pptx / .docx -> deck pages (design `deckModel.import`, I1): PdfRenderer renders every PDF page 1600 px
 * wide (at most 60 pages); several images become pages sorted by file name; a .pptx is drawn slide by slide by
 * [SlidePainter] and its speaker notes become the lectern notes; a .docx becomes a talk script ([Script]: one section
 * per page, heading card on the screen, full text on the lectern). Runs on an IO dispatcher; [progress] gets (done, total).
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

    fun isPptx(f: File): Boolean = f.name.lowercase().endsWith(".pptx")

    fun isDocx(f: File): Boolean = f.name.lowercase().endsWith(".docx")

    fun isImage(f: File): Boolean = f.name.lowercase().let { n -> listOf(".png", ".jpg", ".jpeg", ".webp", ".bmp").any { n.endsWith(it) } }

    suspend fun import(store: Store, files: List<File>, id: String, progress: (Int, Int) -> Unit): Result {
        val dir = store.deckDir(id).also { it.deleteRecursively(); it.mkdirs() }
        try {
            val pptx = files.firstOrNull { isPptx(it) }
            val docx = files.firstOrNull { isDocx(it) }
            val pdf = files.firstOrNull { isPdf(it) }
            val images = files.filter { isImage(it) }.sortedBy { it.name.lowercase() }
            var deck: Deck
            val capped: Boolean
            when {
                pptx != null -> {
                    val r = renderPptx(store, pptx, id, progress)
                    deck = r.first
                    capped = r.second
                    // a .pptx picked together with a PDF of the same deck: pictures from the PDF, notes from the .pptx
                    if (pdf != null) {
                        val tmp = "$id-pdf"
                        val p = renderPdf(store, pdf, tmp, progress)
                        if (p != null && p.first == deck.pageCount) {
                            movePages(store, tmp, id, p.first)
                            deck = deck.copy(partial = 0)
                        } else {
                            store.deckDir(tmp).deleteRecursively()
                        }
                    }
                }
                docx != null -> renderDocx(store, docx, id, progress).let { deck = it.first; capped = it.second }
                pdf != null || images.isNotEmpty() -> {
                    val (count, cap, title) = if (pdf != null) renderPdf(store, pdf, id, progress) ?: return Result.Password else renderImages(store, images, id, progress)
                    deck = Deck(id, title, if (pdf != null) Deck.SOURCE_PDF else Deck.SOURCE_IMAGES, count, List(count) { "" }, count.coerceIn(1, 60), System.currentTimeMillis())
                    capped = cap
                }
                else -> return Result.NotSupported
            }
            if (deck.pageCount == 0) {
                dir.deleteRecursively()
                return Result.Failed("empty")
            }
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

    private suspend fun renderPptx(store: Store, file: File, id: String, progress: (Int, Int) -> Unit): Pair<Deck, Boolean> {
        val deck = PptxDeck.open(file.readBytes())
        val slides = deck.slides
        val total = slides.size.coerceAtMost(Deck.MAX_PAGES)
        val h = (WIDTH.toFloat() * deck.heightEmu / deck.widthEmu).toInt().coerceIn(1, 2400)
        val painter = SlidePainter(deck)
        progress(0, total)
        try {
            for (i in 0 until total) {
                coroutineContext.ensureActive()
                val bmp = Bitmap.createBitmap(WIDTH, h, Bitmap.Config.ARGB_8888)
                painter.draw(Canvas(bmp), i, WIDTH.toFloat(), h.toFloat())
                save(bmp, store.pageFile(id, i))
                bmp.recycle()
                progress(i + 1, total)
            }
        } finally {
            painter.recycle()
        }
        val used = slides.take(total)
        val partial = used.count { SlideCheck.partial(it) }
        HallLog.i("IMPORT pptx pages=$total partial=$partial notes=${used.count { it.notes.isNotBlank() }}")
        val d = Deck(id, SlideCheck.title(used, file.nameWithoutExtension), Deck.SOURCE_PPTX, total, used.map { it.notes.trim() }, total.coerceIn(1, 60), System.currentTimeMillis(), partial, used.map { SlideCheck.pageTitle(it) })
        return d to (slides.size > Deck.MAX_PAGES)
    }

    private suspend fun renderDocx(store: Store, file: File, id: String, progress: (Int, Int) -> Unit): Pair<Deck, Boolean> {
        val doc = DocxDocument.open(file.readBytes())
        val all = Script.sections(doc.blocks, file.nameWithoutExtension)
        val total = all.size
        val h = WIDTH * 9 / 16
        progress(0, total)
        for (i in 0 until total) {
            coroutineContext.ensureActive()
            val bmp = Bitmap.createBitmap(WIDTH, h, Bitmap.Config.ARGB_8888)
            ScriptCard.draw(Canvas(bmp), all[i], i, total, WIDTH.toFloat(), h.toFloat())
            save(bmp, store.pageFile(id, i))
            bmp.recycle()
            progress(i + 1, total)
        }
        val minutes = Script.minutes(all)
        HallLog.i("IMPORT docx sections=$total minutes=$minutes")
        val title = Script.title(doc.blocks, file.nameWithoutExtension)
        return Deck(id, title, Deck.SOURCE_DOCX, total, all.map { it.text }, minutes, System.currentTimeMillis(), titles = all.map { it.title }) to false
    }

    /**
     * Notes for an existing deck from a file (the deck row's ⋯ [导入备注]): TXT (`---` between pages), a .pptx (its
     * speaker notes, page by page) or a .docx (its sections). Null when the file cannot be read.
     */
    fun notesFrom(file: File, pages: Int): List<String>? =
        runCatching {
            val n =
                when {
                    isPptx(file) -> PptxDeck.open(file.readBytes()).slides.map { it.notes.trim() }
                    isDocx(file) -> Script.sections(DocxDocument.open(file.readBytes()).blocks, file.nameWithoutExtension).map { it.text }
                    else -> return@runCatching Deck.parseNotes(file.readText(), pages)
                }
            List(pages) { n.getOrElse(it) { "" } }
        }.onFailure { HallLog.e("IMPORT notes failed: $it", it) }.getOrNull()

    sealed interface Pictures {
        data object Ok : Pictures

        data object Password : Pictures

        data class Mismatch(val pdfPages: Int) : Pictures

        data object Failed : Pictures
    }

    /** A .pptx deck's ⋯ [换成 PDF 画面]: the same deck exported as PDF replaces the drawn pages (page counts must match). */
    suspend fun replacePictures(store: Store, deck: Deck, pdf: File): Pictures {
        val tmp = "${deck.id}-pdf"
        return try {
            val r = renderPdf(store, pdf, tmp) { _, _ -> } ?: return Pictures.Password
            if (r.first != deck.pageCount) {
                store.deckDir(tmp).deleteRecursively()
                return Pictures.Mismatch(r.first)
            }
            movePages(store, tmp, deck.id, r.first)
            store.saveDeck(deck.copy(partial = 0))
            Pictures.Ok
        } catch (t: Throwable) {
            store.deckDir(tmp).deleteRecursively()
            if (t is kotlinx.coroutines.CancellationException) throw t
            HallLog.e("IMPORT pictures failed: $t", t)
            Pictures.Failed
        }
    }

    private fun movePages(store: Store, from: String, to: String, count: Int) {
        for (i in 0 until count) {
            val dst = store.pageFile(to, i)
            dst.delete()
            store.pageFile(from, i).renameTo(dst)
        }
        store.deckDir(from).deleteRecursively()
    }

    private fun save(bmp: Bitmap, f: File) {
        f.parentFile?.mkdirs()
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
