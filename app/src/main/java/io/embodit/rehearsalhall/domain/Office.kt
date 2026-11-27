package io.embodit.rehearsalhall.domain

import io.embodit.spatialoffice.core.docx.DocBlock
import io.embodit.spatialoffice.core.docx.ParaStyle
import io.embodit.spatialoffice.core.docx.Paragraph
import io.embodit.spatialoffice.core.docx.TableBlock
import io.embodit.spatialoffice.core.pptx.Shape
import io.embodit.spatialoffice.core.pptx.ShapeKind
import io.embodit.spatialoffice.core.pptx.Slide

/** What the slide painter can and cannot show (pure: unit-tested on the JVM). */
object SlideCheck {
    /** A slide holds something drawn as a dashed placeholder (chart, SmartArt / other graphic frame without fill or text). */
    fun partial(slide: Slide): Boolean = slide.shapes.any { partial(it) }

    private fun partial(s: Shape): Boolean =
        when {
            s.children != null -> s.children!!.any { partial(it) }
            s.table != null -> false
            s.kind == ShapeKind.CHART -> true
            s.kind == ShapeKind.PICTURE -> s.imagePart == null
            s.kind == ShapeKind.OTHER -> s.fillRgb == null && s.lineRgb == null && s.paras.none { it.text.isNotBlank() }
            else -> false
        }

    fun luma(rgb: Int): Float = (0.299f * (rgb shr 16 and 0xFF) + 0.587f * (rgb shr 8 and 0xFF) + 0.114f * (rgb and 0xFF)) / 255f

    /** Deck title: the first slide's title text, else the file name. */
    fun title(slides: List<Slide>, fileName: String): String =
        slides.firstOrNull()?.shapes?.firstOrNull { it.kind == ShapeKind.TITLE }?.paras?.joinToString(" ") { it.text.trim() }?.trim()?.takeIf { it.isNotEmpty() }?.take(60)
            ?: fileName
}

/**
 * A .docx as a talk script (design I1 「讲稿」): split into sections, one section = one "page" of the talk. The
 * projection shows the section's heading card; the lectern shows the whole section as the speaker's text.
 */
object Script {
    data class Section(val title: String, val lines: List<String>) {
        val text: String get() = lines.joinToString("\n")
    }

    const val CHUNK_CHARS = 600

    /** Speaking pace for the target time: ~250 Chinese characters or ~130 English words a minute. */
    fun minutes(sections: List<Section>): Int {
        val text = sections.joinToString("\n") { it.title + "\n" + it.text }
        val cjk = text.count { it.code in 0x3400..0x9FFF }
        val words = Regex("[A-Za-z0-9']+").findAll(text).count()
        val m = cjk / 250f + words / 130f
        return kotlin.math.ceil(m).toInt().coerceIn(1, 60)
    }

    /**
     * Rules: a new section starts at every TITLE / H1 paragraph (H2 when the document has no H1), and after a page break.
     * A document without headings or page breaks is cut at paragraph ends roughly every [CHUNK_CHARS] characters.
     * Tables become tab-separated lines; pictures and other objects are skipped. At most [Deck.MAX_PAGES] sections.
     */
    fun sections(blocks: List<DocBlock>, fileName: String): List<Section> {
        val paras = blocks.filterIsInstance<Paragraph>()
        val top = when {
            paras.any { it.style == ParaStyle.H1 } -> setOf(ParaStyle.TITLE, ParaStyle.H1)
            paras.any { it.style == ParaStyle.H2 } -> setOf(ParaStyle.TITLE, ParaStyle.H2)
            else -> setOf(ParaStyle.TITLE)
        }
        val hasBreaks = paras.any { it.pageBreak || it.text.contains('\u000C') }
        val headed = paras.any { it.style in top }
        val out = ArrayList<Section>()
        var title: String? = null
        var lines = ArrayList<String>()
        var chars = 0
        fun flush() {
            if (title != null || lines.any { it.isNotBlank() }) {
                val body = lines.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
                out += Section(title ?: body.firstOrNull()?.take(40) ?: fileName, body)
            }
            title = null
            lines = ArrayList()
            chars = 0
        }
        for (b in blocks) {
            when (b) {
                is Paragraph -> {
                    val t = b.cleanText
                    if (b.pageBreak) { flush(); continue }
                    if (b.style in top && t.isNotBlank()) { flush(); title = t.replace('\u000C', ' ').trim(); continue }
                    val parts = t.split('\u000C')
                    parts.forEachIndexed { i, part ->
                        if (i > 0) flush()
                        val line = part.trim()
                        if (line.isNotEmpty() || lines.isNotEmpty()) lines += line
                        chars += line.length
                    }
                    if (!headed && !hasBreaks && chars >= CHUNK_CHARS) flush()
                }
                is TableBlock -> b.rows.forEach { row -> lines += row.joinToString("\t").also { chars += it.length } }
                else -> {}
            }
        }
        flush()
        return out.filter { it.title.isNotBlank() || it.lines.isNotEmpty() }.take(Deck.MAX_PAGES)
    }

    /** Deck title: the document's Title paragraph, else its first H1, else the file name (never a body sentence). */
    fun title(blocks: List<DocBlock>, fileName: String): String {
        val paras = blocks.filterIsInstance<Paragraph>()
        return (paras.firstOrNull { it.style == ParaStyle.TITLE && it.cleanText.isNotBlank() } ?: paras.firstOrNull { it.style == ParaStyle.H1 && it.cleanText.isNotBlank() })
            ?.cleanText?.trim()?.take(60) ?: fileName
    }

    /** The few short lines a heading card shows under the title: the first sentence of the first paragraphs. */
    fun cardLines(s: Section, max: Int = 5): List<String> =
        s.lines.filter { it.isNotBlank() }.take(max).map { l ->
            val cut = l.indexOfFirst { it in "。！？.!?" }.takeIf { it in 8..60 } ?: l.length
            l.substring(0, minOf(cut + 1, l.length)).let { if (it.length > 60) it.take(58) + "…" else it }
        }
}

/** One file on the headset that the hall can open (the "本机文件" page). */
data class FileEntry(val path: String, val name: String, val where: String, val bytes: Long, val modified: Long) {
    val kind: FileList.Kind get() = FileList.kind(name) ?: FileList.Kind.PDF
}

object FileList {
    enum class Kind { PPT, DOC, PDF }

    /** What the hall opens from the file list: .pptx, .docx, .pdf (images come through the system picker). */
    fun kind(name: String): Kind? {
        val n = name.lowercase()
        return when {
            n.startsWith("~$") -> null // Office lock files
            n.endsWith(".pptx") -> Kind.PPT
            n.endsWith(".docx") -> Kind.DOC
            n.endsWith(".pdf") -> Kind.PDF
            else -> null
        }
    }

    /** Newest first; presentations and documents before PDFs of the same minute; at most [MAX] rows. */
    fun sort(files: List<FileEntry>): List<FileEntry> =
        files.sortedWith(compareByDescending<FileEntry> { it.modified / 60_000 }.thenBy { it.kind.ordinal }.thenBy { it.name.lowercase() }).take(MAX)

    /** "1.2 MB" / "340 KB". */
    fun size(bytes: Long): String =
        if (bytes >= 1_000_000) String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000f) else String.format(java.util.Locale.US, "%d KB", (bytes / 1000).coerceAtLeast(1))

    const val MAX = 200
}
