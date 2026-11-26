package io.embodit.spatialoffice.core.text

import io.embodit.spatialoffice.core.docx.ParaStyle
import io.embodit.spatialoffice.core.pkg.Xml

/**
 * A `.txt` or `.md` file as paragraphs. Each paragraph remembers the exact source text it came from; saving
 * re-emits that text for paragraphs that were not edited, so only edited lines change in the file.
 */
class PlainDoc private constructor(val markdown: Boolean, private val paras: MutableList<Para>, private val eol: String) {
    /**
     * [lead] = blank lines before the block (kept through edits), [body] = the block's own source line(s) including the
     * line terminator, or null once the paragraph was edited or created here; [term] = its line terminator.
     */
    class Para(val id: Int, var style: ParaStyle, var text: String, internal var lead: String, internal var body: String?, internal var term: String)

    private var nextId = (paras.maxOfOrNull { it.id } ?: -1) + 1
    private var tail = ""
    var dirty = false
        private set
    var changedCount = 0
        private set

    val blocks: List<Para> get() = paras

    fun setParagraph(id: Int, text: String, style: ParaStyle) {
        val p = paras.firstOrNull { it.id == id } ?: return
        val st = if (markdown) style else ParaStyle.BODY
        if (p.text == text && p.style == st) return
        if (p.body != null) changedCount++
        p.text = text; p.style = st; p.body = null
        dirty = true
    }

    fun insertParagraphAfter(id: Int, text: String, style: ParaStyle): Int {
        val at = (if (id < 0) 0 else paras.indexOfFirst { it.id == id } + 1).coerceIn(0, paras.size)
        val prev = paras.getOrNull(at - 1)
        val st = if (markdown) style else ParaStyle.BODY
        // Markdown: a blank line keeps blocks apart, except between neighbouring list items and at the very start
        val lead = if (!markdown || prev == null || (st == ParaStyle.BULLET && prev.style == ParaStyle.BULLET)) "" else eol
        val p = Para(nextId++, st, text, lead, null, prev?.term ?: eol)
        if (prev != null && prev.term.isEmpty()) { prev.term = eol; if (prev.body != null) prev.body = prev.body + eol }
        paras.add(at, p)
        dirty = true; changedCount++
        return p.id
    }

    fun deleteBlock(id: Int) {
        if (paras.removeAll { it.id == id }) { dirty = true; changedCount++ }
    }

    fun firstLine(): String = paras.firstOrNull { it.text.isNotBlank() }?.text?.trim() ?: ""

    fun plainText(): String = paras.joinToString("\n") { it.text }

    fun save(): String {
        val sb = StringBuilder()
        for (p in paras) {
            sb.append(p.lead)
            sb.append(p.body ?: ((if (markdown) prefix(p.style) else "") + p.text + p.term))
        }
        return sb.append(tail).toString()
    }

    companion object {
        fun open(text: String, markdown: Boolean): PlainDoc {
            val eol = if (text.contains("\r\n")) "\r\n" else "\n"
            val paras = ArrayList<Para>()
            var id = 0
            // split keeping line terminators
            val lines = ArrayList<String>()
            var i = 0
            while (i < text.length) {
                val n = text.indexOf('\n', i)
                if (n < 0) { lines.add(text.substring(i)); break }
                lines.add(text.substring(i, n + 1)); i = n + 1
            }
            var pending = StringBuilder()
            for (l in lines) {
                val content = l.trimEnd('\n', '\r')
                val term = l.substring(content.length)
                if (markdown && content.isBlank()) { pending.append(l); continue }
                val (style, shown) = if (markdown) classify(content) else ParaStyle.BODY to content
                paras.add(Para(id++, style, shown, pending.toString(), l, term))
                pending = StringBuilder()
            }
            return PlainDoc(markdown, paras, eol).also { it.tail = pending.toString() }
        }

        fun blank(markdown: Boolean): PlainDoc = PlainDoc(markdown, ArrayList(), "\n")

        private fun classify(line: String): Pair<ParaStyle, String> = when {
            line.startsWith("### ") -> ParaStyle.H3 to line.substring(4)
            line.startsWith("## ") -> ParaStyle.H2 to line.substring(3)
            line.startsWith("# ") -> ParaStyle.H1 to line.substring(2)
            line.startsWith("- ") || line.startsWith("* ") -> ParaStyle.BULLET to line.substring(2)
            else -> ParaStyle.BODY to line
        }

        private fun prefix(style: ParaStyle): String = when (style) {
            ParaStyle.TITLE, ParaStyle.H1 -> "# "
            ParaStyle.H2 -> "## "
            ParaStyle.H3 -> "### "
            ParaStyle.BULLET -> "- "
            ParaStyle.NUMBERED -> "1. "
            ParaStyle.BODY -> ""
        }
    }
}

/** Read-only text view of a web page: headings, paragraphs and list items in reading order. Scripts and styles are skipped. */
object HtmlText {
    fun blocks(html: String): List<Pair<ParaStyle, String>> {
        val cleaned = html
            .replace(Regex("(?is)<!--.*?-->"), "")
            .replace(Regex("(?is)<(script|style|head|svg|noscript|template)\\b.*?</\\1\\s*>"), "")
        val out = ArrayList<Pair<ParaStyle, String>>()
        val block = Regex("(?is)<(h[1-6]|p|li|td|th|blockquote|pre|title|figcaption|dt|dd)\\b[^>]*>(.*?)</\\1\\s*>")
        var covered = 0
        for (m in block.findAll(cleaned)) {
            val tag = m.groupValues[1].lowercase()
            val text = strip(m.groupValues[2])
            covered += m.value.length
            if (text.isEmpty()) continue
            val style = when (tag) {
                "h1" -> ParaStyle.H1
                "h2" -> ParaStyle.H2
                "h3", "h4", "h5", "h6" -> ParaStyle.H3
                "li" -> ParaStyle.BULLET
                else -> ParaStyle.BODY
            }
            out.add(style to text)
        }
        if (out.isEmpty()) strip(cleaned).split('\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { out.add(ParaStyle.BODY to it) }
        return out
    }

    private fun strip(s: String): String =
        Xml.unescape(s.replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("(?s)<[^>]+>"), "").replace("&nbsp;", " ")).replace(Regex("[ \\t\\r\\f]+"), " ").replace(Regex(" ?\\n ?"), "\n").trim()
}
