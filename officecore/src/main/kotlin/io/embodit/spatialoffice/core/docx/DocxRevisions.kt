package io.embodit.spatialoffice.core.docx

import io.embodit.spatialoffice.core.pkg.Xml
import io.embodit.spatialoffice.core.review.RevisionKind

/**
 * M5 (d30): tracked changes found in, and resolved on, the text of one body-level element (a paragraph or a table).
 * Works on the source text only, so accepting or rejecting one change rewrites nothing but that change.
 */
internal object DocxRevisions {

    /**
     * One tracked change. [members] = its elements in [seg] coordinates (several when neighbouring elements of the same
     * kind and author were joined); [wid] = the `w:id` of the first. FORMAT and MOVE groups are listed, never resolved.
     */
    class Group(val kind: RevisionKind, val author: String, val date: String, val wid: String, val members: MutableList<Xml.Elem>) {
        val text = StringBuilder()
    }

    private val PROPS = setOf("w:pPr", "w:rPr", "w:trPr", "w:tblPr", "w:tblPrEx", "w:tcPr", "w:sectPr", "w:tblGrid", "w:numPr")
    private val FORMAT_MARKS = setOf("w:ins", "w:del", "w:moveFrom", "w:moveTo", "w:cellIns", "w:cellDel", "w:cellMerge")
    private val SILENT = setOf(
        "w:commentRangeStart", "w:commentRangeEnd", "w:bookmarkStart", "w:bookmarkEnd", "w:proofErr", "w:permStart", "w:permEnd",
        "w:moveFromRangeStart", "w:moveFromRangeEnd", "w:moveToRangeStart", "w:moveToRangeEnd",
    )

    /** Every tracked change in [seg] (one element as text), in document order. */
    fun groups(seg: String): List<Group> {
        val root = Xml.root(seg) ?: return emptyList()
        val out = ArrayList<Group>()
        var last: Group? = null          // the insertion / deletion / move the next neighbour may join
        var lastFormat: Group? = null    // the format change the next one in this paragraph may join

        fun attr(e: Xml.Elem, n: String) = Xml.attr(seg, e, n) ?: ""

        fun format(e: Xml.Elem) {
            val author = attr(e, "w:author")
            val lf = lastFormat
            if (lf != null && lf.author == author) { lf.members.add(e); return }
            lastFormat = Group(RevisionKind.FORMAT, author, attr(e, "w:date"), attr(e, "w:id"), arrayListOf(e)).also { out.add(it) }
        }

        /** Tracked marks inside a properties element: `…Change`, a tracked paragraph mark, a tracked row or cell. */
        fun props(e: Xml.Elem) {
            for (c in Xml.children(seg, e)) {
                if (c.name.endsWith("Change") || c.name in FORMAT_MARKS) format(c) else props(c)
            }
        }

        fun runText(r: Xml.Elem, sb: StringBuilder) {
            for (c in Xml.children(seg, r)) when (c.name) {
                "w:t", "w:delText" -> sb.append(Xml.text(seg, c))
                "w:tab" -> sb.append('\t')
                "w:br", "w:cr" -> sb.append('\n')
            }
        }

        /** Text of the runs in [e], not looking into a nested insertion / deletion (that is a change of its own). */
        fun ownText(e: Xml.Elem, sb: StringBuilder) {
            for (c in Xml.children(seg, e)) when (c.name) {
                "w:r" -> runText(c, sb)
                "w:ins", "w:del", "w:pPr", "w:rPr" -> {}
                else -> ownText(c, sb)
            }
        }

        fun runProps(e: Xml.Elem) {
            for (r in Xml.findAll(seg, e, "w:r")) Xml.child(seg, r, "w:rPr")?.let { props(it) }
        }

        fun tracked(e: Xml.Elem, kind: RevisionKind) {
            val author = attr(e, "w:author")
            val sb = StringBuilder()
            ownText(e, sb)
            val nested = Xml.children(seg, e).filter { it.name == "w:ins" || it.name == "w:del" }
            val hasOwn = Xml.children(seg, e).any { it.name != "w:ins" && it.name != "w:del" && it.name !in SILENT }
            if (hasOwn || nested.isEmpty()) {
                val l = last
                if (l != null && l.kind == kind && l.author == author) { l.members.add(e); l.text.append(sb) }
                else last = Group(kind, author, attr(e, "w:date"), attr(e, "w:id"), arrayListOf(e)).also { it.text.append(sb); out.add(it) }
            }
            if (kind != RevisionKind.MOVE) {
                for (n in nested) { last = null; tracked(n, if (n.name == "w:ins") RevisionKind.INSERT else RevisionKind.DELETE) }
                if (nested.isNotEmpty()) last = null
            }
            runProps(e)
        }

        fun walk(e: Xml.Elem) {
            for (c in Xml.children(seg, e)) {
                when {
                    c.name == "w:p" -> { last = null; lastFormat = null; walk(c); last = null; lastFormat = null }
                    c.name in PROPS -> props(c)
                    c.name == "w:ins" -> tracked(c, RevisionKind.INSERT)
                    c.name == "w:del" -> tracked(c, RevisionKind.DELETE)
                    c.name == "w:moveFrom" || c.name == "w:moveTo" -> tracked(c, RevisionKind.MOVE)
                    c.name == "w:r" -> {
                        // ordinary text between two changes keeps them apart; a comment's reference run does not
                        if (Xml.children(seg, c).any { it.name != "w:rPr" && it.name != "w:commentReference" }) last = null
                        Xml.child(seg, c, "w:rPr")?.let { props(it) }
                    }
                    c.name in SILENT -> {}
                    else -> walk(c)
                }
            }
        }

        when {
            root.name == "w:p" -> walk(around(root))
            root.name in PROPS -> props(root)
            else -> walk(root)
        }
        return out
    }

    /** A stand-in parent so that a paragraph at the root is walked like any other paragraph. */
    private fun around(root: Xml.Elem): Xml.Elem = Xml.Elem("", root.start, root.start, root.end, root.end, false)

    /**
     * [seg] with change [g] accepted or rejected. An accepted insertion and a rejected deletion become ordinary text
     * (the wrapper goes, `w:delText` becomes `w:t` again); a rejected insertion and an accepted deletion go, leaving
     * behind only the comment anchors and bookmark ends they held.
     */
    fun resolve(seg: String, g: Group, accept: Boolean): String {
        if (g.kind != RevisionKind.INSERT && g.kind != RevisionKind.DELETE) return seg
        val unwrap = (g.kind == RevisionKind.INSERT) == accept
        val edits = g.members.map { el ->
            if (el.selfClosing) Triple(el.start, el.end, "")
            else if (unwrap) {
                var inner = seg.substring(el.openEnd, el.closeStart)
                if (g.kind == RevisionKind.DELETE) inner = inner.replace("<w:delText", "<w:t").replace("</w:delText>", "</w:t>")
                    .replace("<w:delInstrText", "<w:instrText").replace("</w:delInstrText>", "</w:instrText>")
                Triple(el.start, el.end, inner)
            } else Triple(el.start, el.end, anchorsOf(seg, el))
        }
        var out = Xml.splice(seg, edits)
        if (EMPTY_WRAPPER.containsMatchIn(seg)) return out
        // a wrapper left with nothing in it (its only content was the change just resolved) goes too
        while (true) {
            val next = EMPTY_WRAPPER.replace(out, "")
            if (next == out) break
            out = next
        }
        return out
    }

    private val EMPTY_WRAPPER = Regex("<w:(ins|del)\\b[^>]*[^/>]>\\s*</w:\\1>")

    /** The comment anchors and bookmark ends inside [el], as text, in order. */
    private fun anchorsOf(seg: String, el: Xml.Elem): String {
        val sb = StringBuilder()
        fun walk(e: Xml.Elem) {
            for (c in Xml.children(seg, e)) when (c.name) {
                "w:commentRangeStart", "w:commentRangeEnd", "w:bookmarkStart", "w:bookmarkEnd" -> sb.append(seg, c.start, c.end)
                "w:r" -> if (Xml.child(seg, c, "w:commentReference") != null) {
                    val kids = Xml.children(seg, c)
                    // the reference alone: text that shared its run goes with the change
                    if (kids.all { it.name == "w:rPr" || it.name == "w:commentReference" }) sb.append(seg, c.start, c.end)
                    else sb.append("<w:r>").append(kids.filter { it.name == "w:rPr" || it.name == "w:commentReference" }.joinToString("") { seg.substring(it.start, it.end) }).append("</w:r>")
                }
                else -> walk(c)
            }
        }
        walk(el)
        return sb.toString()
    }
}
