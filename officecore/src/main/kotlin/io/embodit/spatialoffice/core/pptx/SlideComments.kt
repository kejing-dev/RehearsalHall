package io.embodit.spatialoffice.core.pptx

import io.embodit.spatialoffice.core.docx.XmlEx
import io.embodit.spatialoffice.core.pkg.ContentTypes
import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import io.embodit.spatialoffice.core.pkg.Rels
import io.embodit.spatialoffice.core.pkg.Xml
import io.embodit.spatialoffice.core.review.CommentKind
import io.embodit.spatialoffice.core.review.ReviewComment
import io.embodit.spatialoffice.core.review.ReviewReply
import io.embodit.spatialoffice.core.review.ReviewText
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * M5 (v0.7.0, p33): the comments of a deck. Two generations exist in files and both are read:
 *
 * - **threaded comments** ([CommentKind.THREAD]): one part per slide (`ppt/comments/modernComment_<slide id>_<n>.xml`,
 *   root `cmLst` in the 2018 presentation-comments namespace) holding `cm` elements with an anchor, a position, a list
 *   of replies and a text body; their authors are in one deck-level part (`ppt/authors.xml`). The slide names its
 *   comment part twice: by a relationship and by a `commentRel` entry in the slide's extension list.
 * - **old-style comments** ([CommentKind.NOTE]): `ppt/comments/comment<N>.xml` (`p:cmLst` / `p:cm authorId dt idx` with
 *   `p:pos` and `p:text`) and `ppt/commentAuthors.xml`. Text only: they can be edited and deleted, never replied to
 *   or resolved, and they are never rewritten or dropped when threaded comments are added to the same slide.
 *
 * New comments are always written in the threaded form. Every edit is a splice into the one part it concerns; a
 * comment part (and the authors part) is created only when the first comment needs it.
 */
internal class SlideComments(private val pkg: OoxmlPackage, private val presPart: String, private val host: Host) {

    /** What this needs from the deck it belongs to. */
    interface Host {
        fun slideParts(): List<String>
        val widthEmu: Long
        val heightEmu: Long
        /** The `id` of the slide's entry in the presentation's slide list. */
        fun slideId(slidePart: String): String?
        /** Box (x, y, w, h in EMU) of a top-level object of a slide; null when the slide has no such object. */
        fun shapeBox(slidePart: String, shapeId: Int): LongArray?
        /** The slide part's own XML was changed (its extension list). */
        fun slideEdited(slidePart: String)
        /** Some part of the package was changed. */
        fun edited()
    }

    private class Entry(
        val id: String, val slidePart: String, val part: String, val modern: Boolean,
        /** The `cm` / `reply` element in the current text of [part]. */
        val elem: Xml.Elem,
        /** For a reply: the id of the comment it answers. */
        val thread: String?,
    )

    private class Loaded(val list: List<ReviewComment>, val entries: Map<String, Entry>)

    private var cache: Loaded? = null

    /** Forget what was read (the slide list or a comment part changed). */
    fun invalidate() { cache = null }

    fun all(): List<ReviewComment> = loaded().list

    // ------------------------------------------------------------------------------------------------ reading

    private fun loaded(): Loaded = cache ?: load().also { cache = it }

    private fun load(): Loaded {
        val list = ArrayList<ReviewComment>()
        val entries = LinkedHashMap<String, Entry>()
        val used = HashSet<String>()
        fun unique(base: String): String { var id = base; var n = 2; while (!used.add(id)) id = "$base~${n++}"; return id }
        val newAuthors = authorNames(authorsPart(), "author")
        val oldAuthors = authorNames(oldAuthorsPart(), "cmAuthor")
        val seen = HashSet<String>()
        host.slideParts().forEachIndexed { index, slidePart ->
            for (part in commentParts(slidePart)) {
                if (!seen.add(part)) continue // a part two slides point at belongs to the first of them
                val xml = pkg.text(part) ?: continue
                val root = Xml.root(xml) ?: continue
                if (isModern(xml, root)) readModern(index, slidePart, part, xml, root, newAuthors, ::unique, list, entries)
                else readOld(index, slidePart, part, xml, root, oldAuthors, ::unique, list, entries)
            }
        }
        return Loaded(list, entries)
    }

    /** Comment parts of a slide (both generations), in the order of its relationships. */
    private fun commentParts(slidePart: String): List<String> =
        Rels.of(pkg, slidePart).filter { !it.external && it.type.endsWith("/comments") }
            .map { OoxmlPackage.resolve(slidePart, it.target) }
            .filter { p -> pkg.text(p)?.let { x -> Xml.root(x)?.let { Xml.local(it.name) == "cmLst" } } == true }

    private fun nsOf(xml: String, e: Xml.Elem): String? {
        val prefix = e.name.substringBefore(':', "")
        val key = if (prefix.isEmpty()) "xmlns" else "xmlns:$prefix"
        return Xml.attr(xml, e, key) ?: Xml.root(xml)?.let { Xml.attr(xml, it, key) }
    }

    private fun isModern(xml: String, root: Xml.Elem): Boolean = nsOf(xml, root) == NS_P188

    private fun kids(xml: String, e: Xml.Elem, local: String): List<Xml.Elem> = Xml.children(xml, e).filter { Xml.local(it.name) == local }
    private fun kid(xml: String, e: Xml.Elem, local: String): Xml.Elem? = Xml.children(xml, e).firstOrNull { Xml.local(it.name) == local }

    private fun findLocal(xml: String, within: Xml.Elem, locals: Set<String>): Xml.Elem? {
        for (c in Xml.children(xml, within)) {
            if (Xml.local(c.name) in locals) return c
            findLocal(xml, c, locals)?.let { return it }
        }
        return null
    }

    private fun authorNames(part: String?, local: String): Map<String, String> {
        val xml = part?.let { pkg.text(it) } ?: return emptyMap()
        val root = Xml.root(xml) ?: return emptyMap()
        val out = HashMap<String, String>()
        for (a in kids(xml, root, local)) Xml.attr(xml, a, "id")?.let { out[it] = Xml.attr(xml, a, "name") ?: "" }
        return out
    }

    private fun authorsPart(): String? =
        Rels.of(pkg, presPart).firstOrNull { !it.external && it.type.endsWith("/authors") }?.let { OoxmlPackage.resolve(presPart, it.target) }?.takeIf { pkg.has(it) }

    private fun oldAuthorsPart(): String? =
        Rels.of(pkg, presPart).firstOrNull { !it.external && it.type.endsWith("/commentAuthors") }?.let { OoxmlPackage.resolve(presPart, it.target) }?.takeIf { pkg.has(it) }

    /** Text of a comment's text body: one line per paragraph, a line break inside a paragraph is a line too. */
    private fun bodyText(xml: String, body: Xml.Elem?): String {
        if (body == null) return ""
        return kids(xml, body, "p").joinToString("\n") { p ->
            val sb = StringBuilder()
            for (c in Xml.children(xml, p)) when (Xml.local(c.name)) {
                "r", "fld" -> kid(xml, c, "t")?.let { sb.append(Xml.text(xml, it)) }
                "br" -> sb.append('\n')
            }
            sb.toString()
        }
    }

    private fun share(v: Double, of: Long): Float = if (of <= 0) 0f else (v / of).coerceIn(0.0, 1.0).toFloat()

    private fun readModern(
        index: Int, slidePart: String, part: String, xml: String, root: Xml.Elem, authors: Map<String, String>,
        unique: (String) -> String, list: MutableList<ReviewComment>, entries: MutableMap<String, Entry>,
    ) {
        for (cm in kids(xml, root, "cm")) {
            val a = Xml.attrs(xml, cm)
            val id = unique(a["id"] ?: "cm")
            entries[id] = Entry(id, slidePart, part, true, cm, null)
            val children = Xml.children(xml, cm)
            // the position is given from the top-left corner of what the comment is anchored to: the slide, or one of its objects
            val anchor = children.firstOrNull { Xml.local(it.name) !in CM_OWN }
            val box = anchor?.takeIf { Xml.local(it.name) != "sldMkLst" }?.let { findLocal(xml, it, OBJECT_MONIKERS) }
                ?.let { Xml.attr(xml, it, "id")?.toIntOrNull() }?.let { host.shapeBox(slidePart, it) }
            val pos = children.firstOrNull { Xml.local(it.name) == "pos" }
            val px = pos?.let { Xml.attr(xml, it, "x")?.toDoubleOrNull() }
            val py = pos?.let { Xml.attr(xml, it, "y")?.toDoubleOrNull() }
            val x = (box?.get(0) ?: 0L) + (px ?: (box?.get(2)?.toDouble() ?: 0.0))
            val y = (box?.get(1) ?: 0L) + (py ?: 0.0)
            val replies = kid(xml, cm, "replyLst")?.let { kids(xml, it, "reply") }.orEmpty().map { r ->
                val ra = Xml.attrs(xml, r)
                val rid = unique(ra["id"] ?: "reply")
                entries[rid] = Entry(rid, slidePart, part, true, r, id)
                ReviewReply(rid, authors[ra["authorId"]] ?: "", CommentDates.fromFile(ra["created"]), bodyText(xml, kid(xml, r, "txBody")))
            }
            list.add(
                ReviewComment(
                    id = id, kind = CommentKind.THREAD, author = authors[a["authorId"]] ?: "", date = CommentDates.fromFile(a["created"]),
                    text = bodyText(xml, kid(xml, cm, "txBody")), resolved = a["status"] == "resolved" || a["status"] == "closed",
                    replies = replies, slide = index, x = share(x, host.widthEmu), y = share(y, host.heightEmu),
                ),
            )
        }
    }

    private fun readOld(
        index: Int, slidePart: String, part: String, xml: String, root: Xml.Elem, authors: Map<String, String>,
        unique: (String) -> String, list: MutableList<ReviewComment>, entries: MutableMap<String, Entry>,
    ) {
        for (cm in kids(xml, root, "cm")) {
            val a = Xml.attrs(xml, cm)
            val id = unique("note:${a["authorId"] ?: ""}:${a["idx"] ?: ""}")
            entries[id] = Entry(id, slidePart, part, false, cm, null)
            val pos = kid(xml, cm, "pos")
            // old-style positions count 576 units to the inch
            val x = (pos?.let { Xml.attr(xml, it, "x")?.toDoubleOrNull() } ?: 0.0) * OLD_UNIT_EMU
            val y = (pos?.let { Xml.attr(xml, it, "y")?.toDoubleOrNull() } ?: 0.0) * OLD_UNIT_EMU
            list.add(
                ReviewComment(
                    id = id, kind = CommentKind.NOTE, author = authors[a["authorId"]] ?: "", date = CommentDates.fromFile(a["dt"]),
                    text = kid(xml, cm, "text")?.let { Xml.text(xml, it) } ?: "", slide = index,
                    x = share(x, host.widthEmu), y = share(y, host.heightEmu),
                ),
            )
        }
    }

    // ------------------------------------------------------------------------------------------------ editing

    private fun entry(id: String): Entry = loaded().entries[id] ?: throw IllegalArgumentException("no comment $id")

    private fun put(part: String, xml: String) {
        pkg.putText(part, xml)
        invalidate()
        host.edited()
    }

    private fun qn(e: Xml.Elem): String = e.name.substringBefore(':', "").let { if (it.isEmpty()) "" else "$it:" }

    private fun clean(s: String): String = XmlEx.clean(s.replace("\r\n", "\n").replace('\r', '\n').replace('\u000B', '\n'))

    /** Paragraphs of a comment text: one `a:p` per line, each run with [rPr] (an `a:rPr` as text, or ""). */
    private fun paras(text: String, rPr: String = ""): String = clean(text).split('\n').joinToString("") { line ->
        if (line.isEmpty()) "<a:p/>" else "<a:p><a:r>$rPr<a:t>${Xml.escape(line)}</a:t></a:r></a:p>"
    }

    /** A text body for [text] in a part whose own elements are written with prefix [q]. */
    private fun body(xml: String, q: String, text: String): String {
        val aDecl = if (Xml.root(xml)?.let { Xml.attr(xml, it, "xmlns:a") } == PptxTemplates.NS_A) "" else " xmlns:a=\"${PptxTemplates.NS_A}\""
        return "<${q}txBody$aDecl><a:bodyPr/><a:lstStyle/>${paras(text)}</${q}txBody>"
    }

    /** The author's id in the deck's authors part; the part and the author's entry are made when missing. */
    private fun ensureAuthor(name: String): String {
        var part = authorsPart()
        if (part == null) {
            part = OoxmlPackage.resolve(presPart, "authors.xml")
            val have = pkg.text(part)?.let { x -> Xml.root(x)?.let { Xml.local(it.name) == "authorLst" } } == true
            if (!have) {
                var n = 1
                while (pkg.has(part!!)) part = OoxmlPackage.resolve(presPart, "authors${++n}.xml")
                pkg.putText(part, XmlEx.XML_DECL + "<p188:authorLst xmlns:a=\"${PptxTemplates.NS_A}\" xmlns:r=\"${PptxTemplates.NS_R}\" xmlns:p188=\"$NS_P188\"></p188:authorLst>")
            }
            Rels.add(pkg, presPart, REL_AUTHORS, XmlEx.relativize(presPart, part))
            ContentTypes.addOverride(pkg, part, CT_AUTHORS)
        }
        val xml = pkg.text(part)!!
        val root = Xml.root(xml) ?: throw IllegalStateException("the authors part is not readable")
        kids(xml, root, "author").firstOrNull { Xml.attr(xml, it, "name") == name }?.let { a -> Xml.attr(xml, a, "id")?.let { return it } }
        val id = guid()
        val n = Xml.escape(XmlEx.clean(name))
        val tag = "<${qn(root)}author id=\"$id\" name=\"$n\" initials=\"${Xml.escape(ReviewText.initials(name))}\" userId=\"$n\" providerId=\"None\"/>"
        val ext = kid(xml, root, "extLst")
        pkg.putText(part, if (ext != null) xml.substring(0, ext.start) + tag + xml.substring(ext.start) else Xml.appendInto(xml, root, tag))
        return id
    }

    private fun modernPartOf(slidePart: String): String? =
        commentParts(slidePart).firstOrNull { p -> pkg.text(p)!!.let { x -> isModern(x, Xml.root(x)!!) } }

    /** The slide's creation id (kept in its extension list), "0" when it has none. */
    private fun creationId(slidePart: String): String =
        pkg.text(slidePart)?.let { CREATION_ID.find(it)?.groupValues?.get(1) } ?: "0"

    private fun createPart(slidePart: String): String {
        val sldId = host.slideId(slidePart)?.toLongOrNull() ?: 256L
        var n = creationId(slidePart).toLongOrNull() ?: 0L
        val dir = OoxmlPackage.resolve(presPart, "comments/x").substringBeforeLast('/')
        fun name() = "$dir/modernComment_${java.lang.Long.toHexString(sldId).uppercase()}_${java.lang.Long.toHexString(n).uppercase()}.xml"
        while (pkg.has(name())) n++
        val part = name()
        pkg.putText(part, XmlEx.XML_DECL + "<p188:cmLst xmlns:a=\"${PptxTemplates.NS_A}\" xmlns:r=\"${PptxTemplates.NS_R}\" xmlns:p188=\"$NS_P188\"></p188:cmLst>")
        val rid = Rels.add(pkg, slidePart, REL_COMMENTS, XmlEx.relativize(slidePart, part))
        ContentTypes.addOverride(pkg, part, CT_COMMENTS)
        // the slide also names its comment part in its own extension list
        val sx = pkg.text(slidePart)!!
        val root = Xml.root(sx) ?: throw IllegalStateException("slide part $slidePart is not readable")
        val q = qn(root)
        val rPrefix = Xml.attrs(sx, root).entries.firstOrNull { it.key.startsWith("xmlns:") && it.value == PptxTemplates.NS_R }?.key?.substringAfter(':')
        val rDecl = if (rPrefix == null) " xmlns:r=\"${PptxTemplates.NS_R}\"" else ""
        val ext = "<${q}ext uri=\"$EXT_COMMENT_REL\"><p188:commentRel xmlns:p188=\"$NS_P188\"$rDecl ${rPrefix ?: "r"}:id=\"$rid\"/></${q}ext>"
        val lst = Xml.children(sx, root).lastOrNull { Xml.local(it.name) == "extLst" }
        pkg.putText(slidePart, if (lst != null) Xml.appendInto(sx, lst, ext) else Xml.appendInto(sx, root, "<${q}extLst>$ext</${q}extLst>"))
        host.slideEdited(slidePart)
        return part
    }

    /** A new thread on slide [slide] at ([x], [y]) (shares of the slide, 0–1), anchored to the slide. Returns its id. */
    fun add(slide: Int, x: Float, y: Float, author: String, text: String, dateIso: String): String {
        val parts = host.slideParts()
        if (slide !in parts.indices) throw IndexOutOfBoundsException("slide $slide of ${parts.size}")
        val slidePart = parts[slide]
        val authorId = ensureAuthor(author)
        val part = modernPartOf(slidePart) ?: createPart(slidePart)
        val xml = pkg.text(part)!!
        val root = Xml.root(xml)!!
        val q = qn(root)
        val id = guid()
        val px = Math.round(x.coerceIn(0f, 1f).toDouble() * host.widthEmu)
        val py = Math.round(y.coerceIn(0f, 1f).toDouble() * host.heightEmu)
        val cm = "<${q}cm id=\"$id\" authorId=\"${Xml.escape(authorId)}\" created=\"${CommentDates.toFile(dateIso)}\">" +
            "<pc:sldMkLst xmlns:pc=\"$NS_PC\"><pc:docMk/><pc:sldMk cId=\"${creationId(slidePart)}\" sldId=\"${host.slideId(slidePart) ?: "256"}\"/></pc:sldMkLst>" +
            "<${q}pos x=\"$px\" y=\"$py\"/>" + body(xml, q, text) + "</${q}cm>"
        val ext = kid(xml, root, "extLst")
        put(part, if (ext != null) xml.substring(0, ext.start) + cm + xml.substring(ext.start) else Xml.appendInto(xml, root, cm))
        return id
    }

    /** A reply at the end of the thread [id] belongs to ([id] may be the comment or one of its replies). Returns the reply's id. */
    fun reply(id: String, author: String, text: String, dateIso: String): String {
        val e = entry(id)
        if (!e.modern) throw IllegalStateException("comment $id is an old-style comment: it takes no replies")
        val cm = (e.thread?.let { entry(it) } ?: e).elem
        val authorId = ensureAuthor(author)
        val xml = pkg.text(e.part)!!
        val q = qn(cm)
        val rid = guid()
        val reply = "<${q}reply id=\"$rid\" authorId=\"${Xml.escape(authorId)}\" created=\"${CommentDates.toFile(dateIso)}\">${body(xml, q, text)}</${q}reply>"
        val lst = kid(xml, cm, "replyLst")
        val out = when {
            lst != null -> Xml.appendInto(xml, lst, reply)
            cm.selfClosing -> Xml.appendInto(xml, cm, "<${q}replyLst>$reply</${q}replyLst>")
            else -> {
                // schema order inside a comment: anchor, position, replies, text, extensions
                val at = Xml.children(xml, cm).firstOrNull { Xml.local(it.name) == "txBody" || Xml.local(it.name) == "extLst" }?.start ?: cm.closeStart
                xml.substring(0, at) + "<${q}replyLst>$reply</${q}replyLst>" + xml.substring(at)
            }
        }
        put(e.part, out)
        return rid
    }

    /** Marks the thread [id] belongs to as resolved / open again. Only the `status` attribute of its `cm` changes. */
    fun setResolved(id: String, resolved: Boolean) {
        val e = entry(id)
        if (!e.modern) throw IllegalStateException("comment $id is an old-style comment: it cannot be resolved")
        val cm = (e.thread?.let { entry(it) } ?: e).elem
        val xml = pkg.text(e.part)!!
        val now = Xml.attr(xml, cm, "status").let { it == "resolved" || it == "closed" }
        if (now == resolved) return
        put(e.part, xml.substring(0, cm.start) + XmlEx.withAttr(xml, cm, "status", if (resolved) "resolved" else null) + xml.substring(cm.openEnd))
    }

    /** Replaces the text of a comment, a reply or an old-style comment; nothing else of it changes. */
    fun edit(id: String, text: String) {
        val e = entry(id)
        val xml = pkg.text(e.part)!!
        val el = e.elem
        if (!e.modern) {
            val t = kid(xml, el, "text")
            val want = clean(text)
            if ((t?.let { Xml.text(xml, it) } ?: "") == want) return
            val q = qn(el)
            val out = when {
                t != null -> xml.substring(0, t.start) + "<${t.name}>${Xml.escape(want)}</${t.name}>" + xml.substring(t.end)
                else -> {
                    val at = kid(xml, el, "extLst")?.start ?: if (el.selfClosing) -1 else el.closeStart
                    if (at < 0) Xml.appendInto(xml, el, "<${q}text>${Xml.escape(want)}</${q}text>")
                    else xml.substring(0, at) + "<${q}text>${Xml.escape(want)}</${q}text>" + xml.substring(at)
                }
            }
            put(e.part, out)
            return
        }
        val tb = kid(xml, el, "txBody")
        if (bodyText(xml, tb) == clean(text) && tb != null) return
        val out = if (tb == null || tb.selfClosing) {
            val fresh = body(xml, qn(el), text)
            when {
                tb != null -> xml.substring(0, tb.start) + fresh + xml.substring(tb.end)
                el.selfClosing -> Xml.appendInto(xml, el, fresh)
                else -> { val at = kid(xml, el, "extLst")?.start ?: el.closeStart; xml.substring(0, at) + fresh + xml.substring(at) }
            }
        } else {
            val ps = kids(xml, tb, "p")
            // the formatting of the first run goes on: a comment is one plain text
            val rPr = ps.firstNotNullOfOrNull { p -> kid(xml, p, "r")?.let { kid(xml, it, "rPr") } }?.let { XmlEx.src(xml, it) } ?: ""
            val fresh = paras(text, rPr)
            if (ps.isEmpty()) Xml.appendInto(xml, tb, fresh) else xml.substring(0, ps.first().start) + fresh + xml.substring(ps.last().end)
        }
        put(e.part, out)
    }

    /**
     * Deletes a reply, a whole thread (with its replies) or an old-style comment. The part that held the slide's last
     * comment goes with it: the part, the slide's relationship to it, its content type and, for threaded comments, the
     * slide's `commentRel` entry. Authors stay listed.
     */
    fun delete(id: String) {
        val e = entry(id)
        val xml = pkg.text(e.part)!!
        val root = Xml.root(xml)!!
        if (e.thread != null) {
            val lst = kid(xml, entry(e.thread).elem, "replyLst")
            val cut = if (lst != null && kids(xml, lst, "reply").size == 1 && Xml.children(xml, lst).size == 1) lst else e.elem
            put(e.part, xml.substring(0, cut.start) + xml.substring(cut.end))
            return
        }
        val all = kids(xml, root, "cm")
        val gone = ArrayList<Xml.Elem>()
        gone.add(e.elem)
        if (!e.modern) {
            // old-style answers name the comment they answer by author and index; they go with it
            val a = Xml.attrs(xml, e.elem)
            val parent = Regex("parentCm\\b[^>]*\\bauthorId=\"" + Regex.escape(a["authorId"] ?: "") + "\"[^>]*\\bidx=\"" + Regex.escape(a["idx"] ?: "") + "\"")
            for (c in all) if (c != e.elem && parent.containsMatchIn(XmlEx.src(xml, c))) gone.add(c)
        }
        if (gone.size < all.size) {
            put(e.part, Xml.splice(xml, gone.map { Triple(it.start, it.end, "") }))
            return
        }
        // the slide's last comment of this kind: the part goes
        val rid = Rels.of(pkg, e.slidePart).firstOrNull { !it.external && OoxmlPackage.resolve(e.slidePart, it.target) == e.part }?.id
        if (rid != null) XmlEx.removeRel(pkg, e.slidePart, rid)
        val stillUsed = e.part in XmlEx.referencedParts(pkg)
        if (!stillUsed) {
            pkg.remove(e.part)
            pkg.remove(OoxmlPackage.relsOf(e.part))
            ContentTypes.removeOverride(pkg, e.part)
        } else {
            put(e.part, Xml.splice(xml, gone.map { Triple(it.start, it.end, "") }))
        }
        if (e.modern && rid != null) {
            val sx = pkg.text(e.slidePart)!!
            val out = withoutCommentRel(sx, rid)
            if (out != sx) { pkg.putText(e.slidePart, out); host.slideEdited(e.slidePart) }
        }
        invalidate()
        host.edited()
    }

    companion object {
        const val NS_P188 = "http://schemas.microsoft.com/office/powerpoint/2018/8/main"
        const val NS_PC = "http://schemas.microsoft.com/office/powerpoint/2013/main/command"
        const val REL_COMMENTS = "http://schemas.microsoft.com/office/2018/10/relationships/comments"
        const val REL_AUTHORS = "http://schemas.microsoft.com/office/2018/10/relationships/authors"
        const val CT_COMMENTS = "application/vnd.ms-powerpoint.comments+xml"
        const val CT_AUTHORS = "application/vnd.ms-powerpoint.authors+xml"
        /** The slide extension that names the slide's threaded-comment part. */
        const val EXT_COMMENT_REL = "{6950BFC3-D8DA-4A85-94F7-54DA5524770B}"
        /** EMU per unit of an old-style comment position (576 units to the inch). */
        const val OLD_UNIT_EMU = 914400.0 / 576.0

        private val CM_OWN = setOf("pos", "replyLst", "txBody", "extLst")
        private val OBJECT_MONIKERS = setOf("spMk", "grpSpMk", "graphicFrameMk", "cxnSpMk", "picMk", "inkMk")
        private val CREATION_ID = Regex("<(?:[\\w.-]+:)?creationId\\b[^>]*?\\sval=\"(\\d+)\"")

        fun guid(): String = "{" + UUID.randomUUID().toString().uppercase() + "}"

        /**
         * [slideXml] without the `commentRel` entry of its extension list ([rid] = only the one with that relationship
         * id, null = every one); an extension list left empty goes too.
         */
        fun withoutCommentRel(slideXml: String, rid: String?): String {
            var sx = slideXml
            while (true) {
                val root = Xml.root(sx) ?: return sx
                val lst = Xml.children(sx, root).lastOrNull { Xml.local(it.name) == "extLst" } ?: return sx
                val exts = Xml.children(sx, lst)
                val hit = exts.firstOrNull { ext ->
                    Xml.children(sx, ext).any { c ->
                        Xml.local(c.name) == "commentRel" && (rid == null || Xml.attrs(sx, c).any { (k, v) -> (k == "id" || k.endsWith(":id")) && v == rid })
                    }
                } ?: return sx
                val cut = if (exts.size == 1) lst else hit
                sx = sx.substring(0, cut.start) + sx.substring(cut.end)
            }
        }
    }
}

/** Comment dates: files keep `2026-11-22T10:00:00.000` (UTC, no zone letter); the review model gets `2026-11-22T10:00:00Z`. */
internal object CommentDates {
    private val FILE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    private val NO_ZONE = Regex("^(\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d)(:\\d\\d)?(\\.\\d+)?$")

    /** An ISO-8601 date-time as the file form (UTC). Text that is no date-time gives the time of the call. */
    fun toFile(iso: String): String {
        val t = iso.trim()
        val utc: LocalDateTime = runCatching { OffsetDateTime.parse(t).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(t) }.getOrNull()
            ?: LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)
        return FILE.format(utc)
    }

    /** The file's date-time as ISO-8601 with a zone; "" when there is none, the text itself when it is no date-time. */
    fun fromFile(v: String?): String {
        val t = v?.trim().orEmpty()
        if (t.isEmpty()) return ""
        NO_ZONE.find(t)?.let { m ->
            val frac = m.groupValues[3].trimEnd('0').let { if (it == ".") "" else it }
            return m.groupValues[1] + m.groupValues[2].ifEmpty { ":00" } + frac + "Z"
        }
        return runCatching { OffsetDateTime.parse(t).toInstant().toString() }.getOrDefault(t)
    }
}
