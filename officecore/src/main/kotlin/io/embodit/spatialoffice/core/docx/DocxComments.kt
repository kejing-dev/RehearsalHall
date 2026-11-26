package io.embodit.spatialoffice.core.docx

import io.embodit.spatialoffice.core.pkg.Xml

/**
 * M5 (d29): reading and patching the comment parts as text. Four parts can describe one comment:
 * the comments part (author, date, text), the extended part (who answers whom, resolved), and two newer parts that
 * give each comment a lasting id and a UTC date. Only the first is required; the others are kept in step when the file
 * has them. Nothing here re-serialises a part: an edit replaces or inserts one element.
 */
internal object DocxComments {
    const val NS_W14 = "http://schemas.microsoft.com/office/word/2010/wordml"
    const val NS_W15 = "http://schemas.microsoft.com/office/word/2012/wordml"
    const val NS_MC = "http://schemas.openxmlformats.org/markup-compatibility/2006"

    const val REL_COMMENTS = DocxTemplates.REL + "/comments"
    const val REL_EXTENDED = "http://schemas.microsoft.com/office/2011/relationships/commentsExtended"
    const val REL_IDS = "http://schemas.microsoft.com/office/2016/09/relationships/commentsIds"
    const val REL_EXTENSIBLE = "http://schemas.microsoft.com/office/2018/08/relationships/commentsExtensible"
    const val CT_COMMENTS = "application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml"
    const val CT_EXTENDED = "application/vnd.openxmlformats-officedocument.wordprocessingml.commentsExtended+xml"

    /** One `w:comment`. [paraIds] = the `w14:paraId` of each of its paragraphs (the last one names it in the other parts). */
    class Rec(val id: Int, val author: String, val date: String, val text: String, val paraIds: List<String>, val el: Xml.Elem) {
        val paraId: String? get() = paraIds.lastOrNull()
    }

    /** One entry of the extended part. */
    class Ex(val paraId: String, val parent: String?, val done: Boolean, val el: Xml.Elem)

    fun newCommentsXml(): String = XmlEx.XML_DECL +
        "<w:comments xmlns:w=\"${DocxTemplates.NS_W}\" xmlns:mc=\"$NS_MC\" xmlns:w14=\"$NS_W14\" mc:Ignorable=\"w14\"></w:comments>"

    fun newExtendedXml(): String = XmlEx.XML_DECL +
        "<w15:commentsEx xmlns:w15=\"$NS_W15\" xmlns:mc=\"$NS_MC\" mc:Ignorable=\"w15\"></w15:commentsEx>"

    private fun paraIdOf(xml: String, p: Xml.Elem): String? = Xml.attrs(xml, p).entries.firstOrNull { it.key.substringAfter(':') == "paraId" }?.value

    fun parse(xml: String): List<Rec> {
        val root = Xml.root(xml) ?: return emptyList()
        val out = ArrayList<Rec>()
        for (c in Xml.children(xml, root)) {
            if (Xml.local(c.name) != "comment") continue
            val id = Xml.attr(xml, c, "w:id")?.toIntOrNull() ?: continue
            val paras = Xml.findAll(xml, c, "w:p")
            val text = paras.joinToString("\n") { p ->
                val sb = StringBuilder()
                for (r in Xml.findAll(xml, p, "w:r")) for (k in Xml.children(xml, r)) when (k.name) {
                    "w:t" -> sb.append(Xml.text(xml, k))
                    "w:tab" -> sb.append('\t')
                    "w:br", "w:cr" -> sb.append('\n')
                }
                sb.toString()
            }
            out.add(Rec(id, Xml.attr(xml, c, "w:author") ?: "", Xml.attr(xml, c, "w:date") ?: "", text, paras.mapNotNull { paraIdOf(xml, it) }, c))
        }
        return out
    }

    fun parseEx(xml: String): List<Ex> {
        val root = Xml.root(xml) ?: return emptyList()
        return Xml.children(xml, root).mapNotNull { c ->
            if (Xml.local(c.name) != "commentEx") return@mapNotNull null
            val a = Xml.attrs(xml, c).mapKeys { it.key.substringAfter(':') }
            val pid = a["paraId"] ?: return@mapNotNull null
            Ex(pid, a["paraIdParent"], a["done"].let { it == "1" || it == "true" }, c)
        }
    }

    /** `w:` runs for one line of comment text. */
    private fun textRun(line: String, rPr: String): String {
        if (line.isEmpty()) return ""
        val sb = StringBuilder("<w:r>").append(rPr)
        val buf = StringBuilder()
        fun flush() {
            if (buf.isEmpty()) return
            sb.append("<w:t xml:space=\"preserve\">").append(XmlEx.esc(buf.toString())).append("</w:t>")
            buf.setLength(0)
        }
        for (ch in line) if (ch == '\t') { flush(); sb.append("<w:tab/>") } else buf.append(ch)
        flush()
        return sb.append("</w:r>").toString()
    }

    private fun lines(text: String): List<String> = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

    /**
     * A new `w:comment`. [paraId] goes on its last paragraph; [w14] is the prefix the part declares for that
     * attribute's namespace, or null when it declares none (then the paragraph declares it itself).
     * [pStyle] / [rStyle] = ids of the comment-text and comment-mark styles when the file has them.
     */
    fun commentXml(id: Int, author: String, date: String, initials: String, text: String, paraId: String, w14: String?, pStyle: String?, rStyle: String?): String {
        val pPr = if (pStyle != null) "<w:pPr><w:pStyle w:val=\"${Xml.escape(pStyle)}\"/></w:pPr>" else ""
        val ref = "<w:r>" + (if (rStyle != null) "<w:rPr><w:rStyle w:val=\"${Xml.escape(rStyle)}\"/></w:rPr>" else "") + "<w:annotationRef/></w:r>"
        val ls = lines(text)
        // no date given: no attribute (an empty one is not a date)
        val sb = StringBuilder("<w:comment w:id=\"$id\" w:author=\"${XmlEx.esc(author)}\"" + (if (date.isBlank()) "" else " w:date=\"${XmlEx.esc(date.trim())}\"") + " w:initials=\"${XmlEx.esc(initials)}\">")
        ls.forEachIndexed { i, line ->
            sb.append(if (i == ls.lastIndex) paraOpen("<w:p>", paraId, w14) else "<w:p>").append(pPr)
            if (i == 0) sb.append(ref)
            sb.append(textRun(line, "")).append("</w:p>")
        }
        return sb.append("</w:comment>").toString()
    }

    /** The start tag [open] of a paragraph with `paraId` set, declaring the namespace on the tag when the part does not. */
    fun paraOpen(open: String, paraId: String, w14: String?): String =
        if (w14 != null) XmlEx.tagWithAttr(open, "$w14:paraId", paraId)
        else XmlEx.tagWithAttr(XmlEx.tagWithAttr(open, "xmlns:w14", NS_W14), "w14:paraId", paraId)

    /** The prefix [xml]'s root element binds to namespace [ns]; null when it binds none. */
    fun prefixFor(xml: String, ns: String): String? {
        val root = Xml.root(xml) ?: return null
        return Xml.attrs(xml, root).entries.firstOrNull { it.key.startsWith("xmlns:") && it.value == ns }?.key?.substringAfter(':')
    }

    /**
     * The comment [rec] with its text replaced. The first paragraph keeps its properties and the run holding the
     * comment mark, the text keeps the first text run's formatting, and the last paragraph keeps its start tag — its
     * `paraId` is what the other parts know the comment by.
     */
    fun withText(xml: String, rec: Rec, text: String): String {
        val c = rec.el
        val paras = Xml.children(xml, c).filter { it.name == "w:p" }
        if (paras.isEmpty()) {
            val body = lines(text).joinToString("") { "<w:p>" + textRun(it, "") + "</w:p>" }
            return xml.substring(0, c.start) + XmlEx.openTag(xml, c) + body + "</w:comment>" + xml.substring(c.end)
        }
        val first = paras.first()
        val last = paras.last()
        val pPr = Xml.child(xml, first, "w:pPr")?.let { XmlEx.src(xml, it) } ?: ""
        val runs = Xml.children(xml, first).filter { it.name == "w:r" }
        val mark = runs.filter { Xml.child(xml, it, "w:annotationRef") != null }.joinToString("") { XmlEx.src(xml, it) }
        val rPr = paras.asSequence().flatMap { p -> Xml.findAll(xml, p, "w:r").asSequence() }
            .firstOrNull { Xml.child(xml, it, "w:t") != null }?.let { Xml.child(xml, it, "w:rPr") }?.let { XmlEx.src(xml, it) } ?: ""
        val ls = lines(text)
        val sb = StringBuilder()
        ls.forEachIndexed { i, line ->
            val open = when {
                i == ls.lastIndex -> XmlEx.openTag(xml, last)
                i == 0 && first !== last -> XmlEx.openTag(xml, first)
                else -> "<w:p>"
            }
            sb.append(open).append(pPr)
            if (i == 0) sb.append(mark)
            sb.append(textRun(line, rPr)).append("</w:p>")
        }
        return xml.substring(0, paras.first().start) + sb + xml.substring(paras.last().end)
    }

    /** Sets `paraId` on the last paragraph of [rec] (which has none). */
    fun withParaId(xml: String, rec: Rec, paraId: String): String {
        val last = Xml.findAll(xml, rec.el, "w:p").lastOrNull()
        if (last == null) {
            // a comment without a paragraph gets an empty one to carry the id
            val p = paraOpen("<w:p>", paraId, prefixFor(xml, NS_W14)) + "</w:p>"
            return if (rec.el.selfClosing) xml.substring(0, rec.el.start) + XmlEx.openTag(xml, rec.el) + p + "</w:comment>" + xml.substring(rec.el.end)
            else xml.substring(0, rec.el.closeStart) + p + xml.substring(rec.el.closeStart)
        }
        val open = paraOpen(xml.substring(last.start, last.openEnd), paraId, prefixFor(xml, NS_W14))
        return xml.substring(0, last.start) + open + xml.substring(last.openEnd)
    }

    /** [xml] (any of the four parts) without the direct children of its root that [drop] names. */
    fun without(xml: String, drop: (Xml.Elem) -> Boolean): String {
        val root = Xml.root(xml) ?: return xml
        return Xml.splice(xml, Xml.children(xml, root).filter(drop).map { Triple(it.start, it.end, "") })
    }

    /** Prefix of [xml]'s root element name (`w15:commentsEx` → `w15`). */
    fun rootPrefix(xml: String): String = Xml.root(xml)?.name?.substringBefore(':', "") ?: ""

    /** An 8-digit upper-case hex id below 0x80000000 that [used] does not hold yet; it is added to [used]. */
    fun freshHexId(used: MutableSet<String>): String {
        var n = used.size + 1
        while (true) {
            val v = ((0x1F3D5B79L + n * 0x9E3779B1L) and 0x7FFFFFFFL).coerceAtLeast(1L)
            val s = "%08X".format(v)
            if (used.add(s)) return s
            n++
        }
    }

    private val HEX_ID = Regex("(?:paraId|durableId|textId)=\"([0-9A-Fa-f]{8})\"")

    /** Every paragraph / durable id in [xml], upper-cased. */
    fun hexIds(xml: String?): Set<String> = if (xml == null) emptySet() else HEX_ID.findAll(xml).map { it.groupValues[1].uppercase() }.toSet()
}
