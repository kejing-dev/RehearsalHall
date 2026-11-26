package io.embodit.spatialoffice.core.docx

import io.embodit.spatialoffice.core.pkg.ContentTypes
import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import io.embodit.spatialoffice.core.pkg.Rels
import io.embodit.spatialoffice.core.pkg.Xml
import io.embodit.spatialoffice.core.review.CommentKind
import io.embodit.spatialoffice.core.review.ReviewComment
import io.embodit.spatialoffice.core.review.ReviewReply
import io.embodit.spatialoffice.core.review.ReviewText
import io.embodit.spatialoffice.core.review.Revision
import io.embodit.spatialoffice.core.review.RevisionKind
import io.embodit.spatialoffice.core.text.RunRev

/**
 * A word-processing document opened for patch-only editing.
 *
 * The main document part is held as text. Every body-level element is one block with a character range into that
 * text; an edit replaces exactly that range (or inserts / removes one), so everything else in the part — and every
 * other part of the package — stays byte-for-byte what the file had.
 */
class DocxDocument private constructor(private val pkg: OoxmlPackage, private var source: ByteArray?) {

    private class Entry(
        val id: Int,
        var start: Int,
        var end: Int,
        var block: DocBlock,
        /** `<w:rPr>` text of each original run ("" = none), as opened; what [Run.src] points into. Only ever added to. */
        var srcRPr: List<String>,
        var isNew: Boolean,
        /** M5: comment anchors and bookmark ends of an editable paragraph, by character offset, in document order. */
        var marks: List<Mark> = emptyList(),
        /** M5: `w:id` of each tracked insertion / deletion the paragraph has, used again when it is rewritten. */
        var revIds: List<Pair<RunRev, Int>> = emptyList(),
    )

    private val docPart: String =
        Rels.of(pkg, "").firstOrNull { !it.external && it.type.endsWith("/officeDocument") }
            ?.let { OoxmlPackage.resolve("", it.target) }?.takeIf { pkg.has(it) } ?: "word/document.xml"

    private var xml: String = pkg.text(docPart) ?: throw IllegalArgumentException("not a document: no $docPart")
    private var xmlChanged = false
    private var auxChanged = false
    private val bodyStart: Int
    private val entries = ArrayList<Entry>()
    private var nextId = 0
    private var stylesCache: DocxStyles? = null
    private var numberingCache: DocxNumbering? = null
    private var blocksCache: List<DocBlock>? = null
    private var compatCache: List<CompatNote>? = null
    private var ownBulletNumId: Int? = null
    private val changedIds = HashSet<Int>()
    private var deletedCount = 0

    init {
        val root = Xml.root(xml)
        val body = root?.let { Xml.child(xml, it, "w:body") }
        bodyStart = if (body != null && !body.selfClosing) body.openEnd else -1
        if (body != null) {
            for (c in Xml.children(xml, body)) {
                when {
                    c.name == "w:p" -> entries.add(parseParagraph(c, nextId++))
                    c.name == "w:tbl" -> entries.add(Entry(nextId, c.start, c.end, parseTable(nextId, c), emptyList(), false).also { nextId++ })
                    c.name == "w:sectPr" || isRangeMarker(c.name) -> {}
                    else -> entries.add(Entry(nextId, c.start, c.end, OpaqueBlock(nextId, "other"), emptyList(), false).also { nextId++ })
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ public API

    /** Blocks in body order; ids are stable while this object lives. */
    val blocks: List<DocBlock>
        get() = blocksCache ?: entries.map { it.block }.also { blocksCache = it }

    /** Current bytes of a package part (e.g. an [ImageBlock.part]); null when the part does not exist. */
    fun partBytes(part: String): ByteArray? {
        val n = part.trimStart('/')
        if (n == docPart && xmlChanged) return xml.toByteArray(Charsets.UTF_8)
        return pkg.bytes(n)
    }

    val dirty: Boolean get() = xmlChanged || auxChanged

    /** Blocks edited, inserted or deleted since the document was opened or last saved. */
    val changedBlockCount: Int get() = changedIds.size + deletedCount

    /** First non-empty paragraph text, trimmed; "" when there is none. */
    fun firstLine(): String {
        for (e in entries) {
            val p = e.block as? Paragraph ?: continue
            val t = p.cleanText.trim()
            if (t.isNotEmpty()) return t
        }
        return ""
    }

    /** Paragraphs one per line, table rows as tab-separated lines; pictures and opaque blocks are skipped. */
    fun plainText(): String {
        val lines = ArrayList<String>()
        for (e in entries) {
            when (val b = e.block) {
                is Paragraph -> lines.add(b.cleanText)
                is TableBlock -> b.rows.forEach { lines.add(it.joinToString("\t")) }
                else -> {}
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * Replaces the text and style of an editable paragraph. The paragraph's start tag is kept, its
     * `w:pPr` is kept unless [style] differs from the current one, and each run keeps the `w:rPr` of the original
     * run its [Run.src] names, with only bold / italic / underline switched.
     *
     * M5: bookmarks and comment anchors keep their place in the text: the old and the new text are compared (shared
     * start, shared end, one changed stretch between) and every anchor moves with the characters around it — the
     * caller does nothing. Runs with a [Run.rev] are written as tracked insertions / deletions (`w:ins` / `w:del`
     * with `w:delText`). Nothing here records a change by itself: see [Tracking.merge].
     * @throws IllegalStateException when the block is not an editable paragraph.
     */
    fun setParagraph(id: Int, runs: List<Run>, style: ParaStyle, align: Int? = null) {
        val idx = indexOf(id)
        val e = entries[idx]
        val old = e.block as? Paragraph ?: throw IllegalStateException("block $id is not a paragraph")
        if (!old.editable) throw IllegalStateException("paragraph $id is read-only (${old.readOnlyWhy})")
        val p = Xml.elemAt(xml, e.start) ?: throw IllegalStateException("paragraph $id is not readable")
        val kids = Xml.children(xml, p)
        val oldPPr = kids.firstOrNull { it.name == "w:pPr" }?.let { XmlEx.src(xml, it) } ?: ""
        var newPPr = if (style == old.style) oldPPr else restylePPr(oldPPr, style, idx - 1, idx + 1)
        val newAlign = align ?: old.align
        if (newAlign != alignOf(newPPr)) newPPr = withAlign(newPPr, newAlign)

        val clean = normRuns(runs)
        // M5: comment anchors and bookmark ends follow the text they sit in
        val marks = Mark.shift(e.marks, old.text, clean.joinToString("") { it.text })
        writePara(idx, clean, marks, style, newPPr)
    }

    /**
     * Writes paragraph [idx] again from [runs] and [marks]: its start tag and `w:pPr` stay ([newPPr] replaces the
     * latter), each run keeps the `w:rPr` its [Run.src] names, tracked runs keep their `w:id`.
     */
    private fun writePara(idx: Int, runs: List<Run>, marks: List<Mark>, style: ParaStyle? = null, newPPr: String? = null) {
        val e = entries[idx]
        val old = e.block as Paragraph
        val p = Xml.elemAt(xml, e.start) ?: throw IllegalStateException("paragraph ${e.id} is not readable")
        val pPr = newPPr ?: (Xml.child(xml, p, "w:pPr")?.let { XmlEx.src(xml, it) } ?: "")
        val ids = RevIds(e.revIds)
        val newXml = XmlEx.openTag(xml, p) + pPr + runsXml(runs, e.srcRPr, marks, ids) + "</w:p>"
        replaceEntry(idx, newXml)
        e.block = paraOf(e.id, style ?: old.style, runs, true, null, pPr)
        e.marks = marks
        e.revIds = ids.used
        changedIds.add(e.id)
    }

    /** Inserts a picture (its own paragraph) after block [id]; [ext] is "png" / "jpeg" / "jpg" / "gif". Returns its id. */
    fun insertImageAfter(id: Int, bytes: ByteArray, ext: String, widthEmu: Long, heightEmu: Long): Int {
        if (bodyStart < 0) throw IllegalStateException("document has no body")
        val e = ext.lowercase().let { if (it == "jpg") "jpeg" else it }
        var n = 1
        while (pkg.has("word/media/image$n.$e") || pkg.has("word/media/image$n.${if (e == "jpeg") "jpg" else e}")) n++
        val part = "word/media/image$n.$e"
        pkg.put(part, bytes)
        ContentTypes.addDefault(pkg, e, "image/$e")
        val rid = Rels.add(pkg, docPart, DocxTemplates.REL + "/image", XmlEx.relativize(docPart, part))
        auxChanged = true
        val docPrId = (Regex("<wp:docPr\\b[^>]*\\bid=\"(\\d+)\"").findAll(xml).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0) + 1
        val newXml = "<w:p>" + DocxTemplates.inlinePicture(rid, docPrId, "Picture $docPrId", widthEmu, heightEmu) + "</w:p>"
        return insertRaw(id, newXml) { nid -> ImageBlock(nid, part, widthEmu, heightEmu) }
    }

    /** Inserts an empty table of [rows] × [cols] after block [id] and returns its id. */
    fun insertTableAfter(id: Int, rows: Int, cols: Int): Int {
        require(rows in 1..200 && cols in 1..30) { "table size $rows × $cols" }
        val newXml = DocxTemplates.table(rows, cols, 9000)
        val nid = insertRaw(id, newXml) { nid -> TableBlock(nid, List(rows) { List(cols) { "" } }, 100) }
        // Word needs a paragraph between a table and whatever follows it only at the end of the body; add one when the table is last
        val idx = indexOf(nid)
        if (idx == entries.lastIndex) insertParagraphAfter(nid, emptyList(), ParaStyle.BODY)
        return nid
    }

    /**
     * Sets the text of one table cell ([col] counts grid columns, as [TableBlock.rows] does). The cell keeps its
     * properties and the first paragraph's properties and run formatting; its paragraphs become one per line of [text].
     */
    fun setTableCell(id: Int, row: Int, col: Int, text: String) {
        val idx = indexOf(id)
        val e = entries[idx]
        if (e.block !is TableBlock) throw IllegalStateException("block $id is not a table")
        val tbl = Xml.elemAt(xml, e.start) ?: throw IllegalStateException("table $id is not readable")
        val tr = Xml.children(xml, tbl, "w:tr").getOrNull(row) ?: throw IndexOutOfBoundsException("row $row")
        var grid = 0
        var tc: Xml.Elem? = null
        for (c in Xml.children(xml, tr, "w:tc")) {
            val span = Xml.child(xml, c, "w:tcPr")?.let { Xml.child(xml, it, "w:gridSpan") }?.let { Xml.attr(xml, it, "w:val")?.toIntOrNull() } ?: 1
            if (col >= grid && col < grid + span) { tc = c; break }
            grid += span
        }
        val cell = tc ?: throw IndexOutOfBoundsException("col $col")
        val kids = Xml.children(xml, cell)
        val tcPr = kids.firstOrNull { it.name == "w:tcPr" }?.let { XmlEx.src(xml, it) } ?: ""
        val firstP = kids.firstOrNull { it.name == "w:p" }
        val pPr = firstP?.let { Xml.child(xml, it, "w:pPr") }?.let { XmlEx.src(xml, it) } ?: ""
        val rPr = firstP?.let { p -> Xml.children(xml, p, "w:r").firstOrNull()?.let { Xml.child(xml, it, "w:rPr") } }?.let { XmlEx.src(xml, it) } ?: ""
        if (kids.any { it.name != "w:tcPr" && it.name != "w:p" }) throw IllegalStateException("cell holds more than text")
        // M5: comments anchored in the cell stay: ranges open at its start and close at its end
        val held = anchorsIn(xml.substring(cell.start, cell.end))
        val opening = held.filter { it.kind == Mark.C_START }.joinToString("") { it.raw }
        val closing = held.filter { it.kind != Mark.C_START }.joinToString("") { it.raw }
        val lines = text.replace("\r\n", "\n").split('\n')
        val paras = lines.withIndex().joinToString("") { (i, line) ->
            "<w:p>$pPr" + (if (i == 0) opening else "") + (if (line.isEmpty()) "" else runsXml(normRuns(listOf(Run(line))), listOf(rPr))) + (if (i == lines.lastIndex) closing else "") + "</w:p>"
        }
        val newCell = XmlEx.openTag(xml, cell) + tcPr + paras + "</w:tc>"
        val delta = newCell.length - (cell.end - cell.start)
        xml = xml.substring(0, cell.start) + newCell + xml.substring(cell.end)
        e.end += delta
        for (k in idx + 1 until entries.size) { entries[k].start += delta; entries[k].end += delta }
        touched()
        e.block = parseTable(id, Xml.elemAt(xml, e.start)!!)
        changedIds.add(id)
    }

    /**
     * Resizes a picture (v0.2.5): the inline extent and the picture's own `a:ext`, nothing else of the drawing.
     * [widthEmu] is kept within the text width by the caller.
     */
    fun setImageSize(id: Int, widthEmu: Long, heightEmu: Long) {
        val idx = indexOf(id)
        val e = entries[idx]
        val img = e.block as? ImageBlock ?: throw IllegalStateException("block $id is not a picture")
        val w = widthEmu.coerceAtLeast(9525L); val h = heightEmu.coerceAtLeast(9525L)
        if (w == img.widthEmu && h == img.heightEmu) return
        val p = Xml.elemAt(xml, e.start) ?: throw IllegalStateException("picture $id is not readable")
        val d = Xml.find(xml, p, "w:drawing") ?: throw IllegalStateException("picture $id has no drawing")
        val edits = ArrayList<Triple<Int, Int, String>>()
        fun resized(el: Xml.Elem) {
            var open = xml.substring(el.start, el.openEnd)
            open = Regex("\\bcx=\"\\d+\"").replace(open, "cx=\"$w\"")
            open = Regex("\\bcy=\"\\d+\"").replace(open, "cy=\"$h\"")
            edits.add(Triple(el.start, el.openEnd, open))
        }
        Xml.find(xml, d, "wp:extent")?.let { resized(it) }
        Xml.find(xml, d, "pic:spPr")?.let { sp -> Xml.find(xml, sp, "a:ext")?.let { resized(it) } }
        if (edits.isEmpty()) throw IllegalStateException("picture $id has no size to change")
        val seg = Xml.splice(xml.substring(e.start, e.end), edits.map { Triple(it.first - e.start, it.second - e.start, it.third) })
        replaceEntry(idx, seg)
        e.block = img.copy(widthEmu = w, heightEmu = h)
        changedIds.add(id)
    }

    /** Sets a table's width as a share of the text width ([pct] 10–100), as `w:tblW w:type="pct"`; cells keep their proportions. */
    fun setTableWidth(id: Int, pct: Int) {
        val idx = indexOf(id)
        val e = entries[idx]
        val tb = e.block as? TableBlock ?: throw IllegalStateException("block $id is not a table")
        val want = pct.coerceIn(10, 100)
        if (want == tb.widthPct) return
        val tbl = Xml.elemAt(xml, e.start) ?: throw IllegalStateException("table $id is not readable")
        val tblW = "<w:tblW w:w=\"${want * 50}\" w:type=\"pct\"/>"
        val pr = Xml.child(xml, tbl, "w:tblPr")
        val seg = when {
            pr == null -> xml.substring(e.start, tbl.openEnd) + "<w:tblPr>$tblW</w:tblPr>" + xml.substring(tbl.openEnd, e.end)
            else -> {
                val old = Xml.child(xml, pr, "w:tblW")
                val newPr = when {
                    old != null -> xml.substring(pr.start, old.start) + tblW + xml.substring(old.end, pr.end)
                    pr.selfClosing -> "<w:tblPr>$tblW</w:tblPr>"
                    else -> XmlEx.insertChild(XmlEx.src(xml, pr), tblW, "w:tblW", TBL_PR_ORDER)
                }
                xml.substring(e.start, pr.start) + newPr + xml.substring(pr.end, e.end)
            }
        }
        replaceEntry(idx, seg)
        e.block = parseTable(id, Xml.elemAt(xml, e.start)!!)
        changedIds.add(id)
    }

    // ---- M3 (d21): horizontal line, page break ----

    /** Inserts an empty paragraph with a bottom border (Word's horizontal line) after block [id]; returns its id. */
    fun insertRuleAfter(id: Int): Int {
        if (bodyStart < 0) throw IllegalStateException("document has no body")
        val pPr = "<w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"auto\"/></w:pBdr></w:pPr>"
        return insertRaw(id, "<w:p>$pPr</w:p>") { nid -> paraOf(nid, ParaStyle.BODY, emptyList(), true, null, pPr) }
    }

    /** Inserts a paragraph holding one page break (`w:br w:type="page"`) after block [id]; returns its id. */
    fun insertPageBreakAfter(id: Int): Int {
        if (bodyStart < 0) throw IllegalStateException("document has no body")
        return insertRaw(id, "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>") { nid -> paraOf(nid, ParaStyle.BODY, listOf(Run("\u000C", src = 0)), true, null, "") }
    }

    // ---- M3 (d42): table rows and columns, merging ----

    private class Tc(val el: Xml.Elem, val grid: Int, val span: Int, val vMerge: String?)

    /** The cells of row [tr] of table text [t] with their grid column, `w:gridSpan` and `w:vMerge` ("restart" / "continue"). */
    private fun tcs(t: String, tr: Xml.Elem): List<Tc> {
        var g = 0
        val out = ArrayList<Tc>()
        for (tc in Xml.children(t, tr, "w:tc")) {
            val pr = Xml.child(t, tc, "w:tcPr")
            val span = pr?.let { Xml.child(t, it, "w:gridSpan") }?.let { Xml.attr(t, it, "w:val")?.toIntOrNull() }?.coerceAtLeast(1) ?: 1
            val vm = pr?.let { Xml.child(t, it, "w:vMerge") }?.let { if (Xml.attr(t, it, "w:val") == "restart") "restart" else "continue" }
            out.add(Tc(tc, g, span, vm))
            g += span
        }
        return out
    }

    private fun covering(cells: List<Tc>, col: Int): Tc? = cells.firstOrNull { col >= it.grid && col < it.grid + it.span }

    /** `w:tcPr` text of a cell ("" = none). */
    private fun tcPrOf(t: String, tc: Xml.Elem): String = Xml.child(t, tc, "w:tcPr")?.let { XmlEx.src(t, it) } ?: ""

    /** [tcPr] with child [name] replaced by [el] (null = removed), in schema order; "" when nothing is left. */
    private fun tcPrSet(tcPr: String, name: String, el: String?): String {
        var out = if (tcPr.isEmpty()) "<w:tcPr></w:tcPr>" else XmlEx.removeChildren(tcPr, setOf(name))
        if (el != null) out = XmlEx.insertChild(out, el, name, TCPR_ORDER)
        return if (XmlEx.isEmpty(out)) "" else out
    }

    private fun spanEl(n: Int): String? = if (n > 1) "<w:gridSpan w:val=\"$n\"/>" else null
    private fun vMergeEl(v: String?): String? = when (v) { "restart" -> "<w:vMerge w:val=\"restart\"/>"; "continue" -> "<w:vMerge/>"; else -> null }

    /** Everything inside a cell after its `w:tcPr`. */
    private fun cellBody(t: String, tc: Xml.Elem): String {
        if (tc.selfClosing) return ""
        val pr = Xml.child(t, tc, "w:tcPr")
        return t.substring(pr?.end ?: tc.openEnd, tc.closeStart)
    }

    private fun cellHasText(t: String, tc: Xml.Elem): Boolean = Xml.findAll(t, tc, "w:t").any { Xml.text(t, it).isNotBlank() }

    /** A cell with [tcPr] and the same body. */
    private fun cellWith(t: String, tc: Xml.Elem, tcPr: String, body: String = cellBody(t, tc)): String = XmlEx.openTag(t, tc) + tcPr + body.ifEmpty { "<w:p/>" } + "</w:tc>"

    /** An empty cell made like [tc]: its properties without the merge marks (unless given), one empty paragraph with the first paragraph's properties. */
    private fun blankCell(t: String, tc: Xml.Elem, span: Int, vMerge: String?): String {
        var pr = tcPrSet(tcPrOf(t, tc), "w:gridSpan", spanEl(span))
        pr = tcPrSet(pr, "w:vMerge", vMergeEl(vMerge))
        val pPr = Xml.child(t, tc, "w:p")?.let { Xml.child(t, it, "w:pPr") }?.let { XmlEx.src(t, it) } ?: ""
        return "<w:tc>$pr<w:p>$pPr</w:p></w:tc>"
    }

    /** Rewrites table [id] with [f] (table text → new table text, null = not possible); true when it changed. */
    private fun editTable(id: Int, f: (String) -> String?): Boolean {
        val idx = indexOf(id)
        val e = entries[idx]
        if (e.block !is TableBlock) throw IllegalStateException("block $id is not a table")
        val seg = xml.substring(e.start, e.end)
        val out = f(seg) ?: return false
        if (out == seg) return false
        replaceEntry(idx, out)
        e.block = parseTable(id, Xml.elemAt(xml, e.start)!!)
        changedIds.add(id)
        // M5: a comment whose cell went with a row or column goes with it
        if (seg.contains("<w:comment")) {
            val left = anchorsIn(out).map { it.cid }.toSet()
            val lost = anchorsIn(seg).map { it.cid }.filter { it !in left }.toSet()
            if (lost.isNotEmpty()) dropComments(withReplies(lost))
        }
        return true
    }

    private fun rowsOf(t: String): List<Xml.Elem> = Xml.children(t, Xml.root(t)!!, "w:tr")

    /** Inserts an empty row above or below row [row] (cells shaped like that row's; a vertical merge running through the gap goes on). */
    fun insertTableRow(id: Int, row: Int, below: Boolean): Boolean = editTable(id) { t ->
        val trs = rowsOf(t)
        val tr = trs.getOrNull(row) ?: return@editTable null
        val next = trs.getOrNull(row + 1)?.let { tcs(t, it) }
        val sb = StringBuilder(XmlEx.openTag(t, tr))
        Xml.child(t, tr, "w:trPr")?.let { sb.append(XmlEx.src(t, it)) }
        for (c in tcs(t, tr)) {
            val goesOn = if (below) c.vMerge != null && next?.any { it.grid == c.grid && it.vMerge == "continue" } == true else c.vMerge == "continue"
            sb.append(blankCell(t, c.el, c.span, if (goesOn) "continue" else null))
        }
        sb.append("</w:tr>")
        val pos = if (below) tr.end else tr.start
        t.substring(0, pos) + sb + t.substring(pos)
    }

    /** Deletes row [row]; a vertical merge that started there starts on the next row instead. The last row takes the table with it. */
    fun deleteTableRow(id: Int, row: Int): Boolean {
        val tb = entries[indexOf(id)].block as? TableBlock ?: throw IllegalStateException("block $id is not a table")
        if (row !in tb.rows.indices) return false
        if (tb.rows.size == 1) { deleteBlock(id); return true }
        return editTable(id) { t ->
            val trs = rowsOf(t)
            val tr = trs[row]
            val edits = ArrayList<Triple<Int, Int, String>>()
            edits.add(Triple(tr.start, tr.end, ""))
            val next = trs.getOrNull(row + 1)
            if (next != null) {
                val below = tcs(t, next)
                for (c in tcs(t, tr)) if (c.vMerge == "restart") below.firstOrNull { it.grid == c.grid && it.vMerge == "continue" }?.let { b ->
                    // the row after continued this one: is it the last of the merge, or does the merge go on below it?
                    val after = trs.getOrNull(row + 2)?.let { tcs(t, it) }?.any { it.grid == c.grid && it.vMerge == "continue" } == true
                    edits.add(Triple(b.el.start, b.el.end, cellWith(t, b.el, tcPrSet(tcPrOf(t, b.el), "w:vMerge", if (after) vMergeEl("restart") else null))))
                }
            }
            Xml.splice(t, edits.sortedBy { it.first })
        }
    }

    /** Grid column widths (`w:gridCol w:w`), null when one has none. */
    private fun gridWidths(t: String): List<Int>? {
        val grid = Xml.child(t, Xml.root(t)!!, "w:tblGrid") ?: return null
        return Xml.children(t, grid, "w:gridCol").map { Xml.attr(t, it, "w:w")?.toIntOrNull() ?: return null }
    }

    /** [t] with the grid widths [w] (rescaled to keep the table's total) and every cell's `dxa` width matching the columns it covers. */
    private fun withGrid(t: String, w: List<Int>, oldTotal: Int?): String {
        val total = oldTotal ?: w.sum()
        val sum = w.sum().coerceAtLeast(1)
        val scaled = w.map { Math.round(it.toDouble() * total / sum).toInt().coerceAtLeast(1) }
        val root = Xml.root(t)!!
        val grid = Xml.child(t, root, "w:tblGrid") ?: return t
        val newGrid = XmlEx.openTag(t, grid) + scaled.joinToString("") { "<w:gridCol w:w=\"$it\"/>" } + "</w:tblGrid>"
        var out = t.substring(0, grid.start) + newGrid + t.substring(grid.end)
        // cell widths follow
        val edits = ArrayList<Triple<Int, Int, String>>()
        for (tr in rowsOf(out)) for (c in tcs(out, tr)) {
            val pr = Xml.child(out, c.el, "w:tcPr") ?: continue
            val tcW = Xml.child(out, pr, "w:tcW") ?: continue
            val type = Xml.attr(out, tcW, "w:type")
            if (type != null && type != "dxa") continue
            val want = (c.grid until c.grid + c.span).sumOf { scaled.getOrElse(it) { 0 } }
            if (Xml.attr(out, tcW, "w:w") != want.toString()) edits.add(Triple(tcW.start, tcW.openEnd, XmlEx.withAttr(out, tcW, "w:w", want.toString())))
        }
        out = Xml.splice(out, edits)
        return out
    }

    /** Inserts an empty column left or right of grid column [col]; a cell merged across the gap just gets wider. */
    fun insertTableColumn(id: Int, col: Int, right: Boolean): Boolean = editTable(id) { t ->
        val widths = gridWidths(t)
        if (widths != null && col !in widths.indices) return@editTable null
        val edits = ArrayList<Triple<Int, Int, String>>()
        for (tr in rowsOf(t)) {
            val c = covering(tcs(t, tr), col) ?: continue
            val inside = if (right) col < c.grid + c.span - 1 else col > c.grid
            if (inside) edits.add(Triple(c.el.start, c.el.end, cellWith(t, c.el, tcPrSet(tcPrOf(t, c.el), "w:gridSpan", spanEl(c.span + 1)))))
            else {
                val cell = blankCell(t, c.el, 1, c.vMerge)
                val pos = if (right) c.el.end else c.el.start
                edits.add(Triple(pos, pos, cell))
            }
        }
        var out = Xml.splice(t, edits.sortedBy { it.first })
        if (widths != null) {
            val w = widths.toMutableList()
            w.add(if (right) col + 1 else col, widths[col])
            out = withGrid(out, w, widths.sum())
        }
        out
    }

    /** Deletes grid column [col]; a merged cell across it gets narrower. The last column takes the table with it. */
    fun deleteTableColumn(id: Int, col: Int): Boolean {
        val tb = entries[indexOf(id)].block as? TableBlock ?: throw IllegalStateException("block $id is not a table")
        val cols = tb.rows.maxOfOrNull { it.size } ?: 0
        if (col !in 0 until cols) return false
        if (cols <= 1) { deleteBlock(id); return true }
        return editTable(id) { t ->
            val widths = gridWidths(t)
            val edits = ArrayList<Triple<Int, Int, String>>()
            for (tr in rowsOf(t)) {
                val cells = tcs(t, tr)
                val c = covering(cells, col) ?: continue
                if (c.span > 1) edits.add(Triple(c.el.start, c.el.end, cellWith(t, c.el, tcPrSet(tcPrOf(t, c.el), "w:gridSpan", spanEl(c.span - 1)))))
                else if (cells.size > 1) edits.add(Triple(c.el.start, c.el.end, ""))
                else edits.add(Triple(tr.start, tr.end, ""))
            }
            var out = Xml.splice(t, edits.sortedBy { it.first })
            if (widths != null && col in widths.indices) out = withGrid(out, widths.filterIndexed { i, _ -> i != col }, widths.sum())
            out
        }
    }

    /**
     * Merges the cell at ([row], [col]) with the cell to its right (`w:gridSpan` = both spans); the right cell's text
     * goes after the left one's. False when there is no cell to the right or either is part of a vertical merge.
     */
    fun mergeTableRight(id: Int, row: Int, col: Int): Boolean = editTable(id) { t ->
        val tr = rowsOf(t).getOrNull(row) ?: return@editTable null
        val cells = tcs(t, tr)
        val a = covering(cells, col) ?: return@editTable null
        val b = cells.getOrNull(cells.indexOf(a) + 1) ?: return@editTable null
        if (a.vMerge != null || b.vMerge != null) return@editTable null
        val body = cellBody(t, a.el) + (if (cellHasText(t, b.el)) cellBody(t, b.el) else "")
        val merged = cellWith(t, a.el, tcPrSet(tcPrOf(t, a.el), "w:gridSpan", spanEl(a.span + b.span)), body)
        val out = t.substring(0, a.el.start) + merged + t.substring(b.el.end)
        gridWidths(out)?.let { withGrid(out, it, null) } ?: out
    }

    /**
     * Merges the cell at ([row], [col]) with the cell below it (`w:vMerge` restart / continue); the lower cell's text
     * goes after the upper one's. False when there is no row below or the cell below is not the same width.
     */
    fun mergeTableDown(id: Int, row: Int, col: Int): Boolean = editTable(id) { t ->
        val trs = rowsOf(t)
        val tr = trs.getOrNull(row) ?: return@editTable null
        val nx = trs.getOrNull(row + 1) ?: return@editTable null
        val a = covering(tcs(t, tr), col) ?: return@editTable null
        val b = tcs(t, nx).firstOrNull { it.grid == a.grid && it.span == a.span } ?: return@editTable null
        val move = cellHasText(t, b.el)
        val aPr = if (a.vMerge == null) tcPrSet(tcPrOf(t, a.el), "w:vMerge", vMergeEl("restart")) else tcPrOf(t, a.el)
        val newA = cellWith(t, a.el, aPr, cellBody(t, a.el) + (if (move) cellBody(t, b.el) else ""))
        val pPr = Xml.child(t, b.el, "w:p")?.let { Xml.child(t, it, "w:pPr") }?.let { XmlEx.src(t, it) } ?: ""
        val newB = cellWith(t, b.el, tcPrSet(tcPrOf(t, b.el), "w:vMerge", vMergeEl("continue")), if (move) "<w:p>$pPr</w:p>" else cellBody(t, b.el))
        Xml.splice(t, listOf(Triple(a.el.start, a.el.end, newA), Triple(b.el.start, b.el.end, newB)))
    }

    /** Width of a table as a share of the text width: `pct` as written, `dxa` against a 6.5 in text column, else 100. */
    private fun tableWidthPct(tbl: Xml.Elem): Int {
        val w = Xml.child(xml, tbl, "w:tblPr")?.let { Xml.child(xml, it, "w:tblW") } ?: return 100
        val v = Xml.attr(xml, w, "w:w")?.removeSuffix("%")?.toDoubleOrNull() ?: return 100
        return when (Xml.attr(xml, w, "w:type")) {
            "pct" -> (if (Xml.attr(xml, w, "w:w")?.endsWith("%") == true) v else v / 50.0).toInt().coerceIn(10, 100)
            "dxa" -> Math.round(v * 100.0 / TEXT_WIDTH_TWIPS).toInt().coerceIn(10, 100)
            else -> 100
        }
    }

    private fun insertRaw(id: Int, newXml: String, block: (Int) -> DocBlock): Int {
        val anchorIdx = if (id == -1) -1 else indexOf(id)
        val pos = if (anchorIdx < 0) bodyStart else entries[anchorIdx].end
        xml = xml.substring(0, pos) + newXml + xml.substring(pos)
        for (k in anchorIdx + 1 until entries.size) { entries[k].start += newXml.length; entries[k].end += newXml.length }
        val newId = nextId++
        entries.add(anchorIdx + 1, Entry(newId, pos, pos + newXml.length, block(newId), emptyList(), true))
        changedIds.add(newId)
        touched()
        return newId
    }

    private fun alignOf(pPr: String): Int {
        if (pPr.isEmpty()) return 0
        val e = XmlEx.childOf(pPr, "w:jc") ?: return 0
        return when (Xml.attr(pPr, e, "w:val")) { "center" -> 1; "right", "end" -> 2; "both", "distribute" -> 3; else -> 0 }
    }

    private fun withAlign(pPr: String, align: Int): String {
        var out = if (pPr.isEmpty()) "<w:pPr></w:pPr>" else XmlEx.removeChildren(pPr, setOf("w:jc"))
        val v = when (align) { 1 -> "center"; 2 -> "right"; 3 -> "both"; else -> null }
        if (v != null) out = XmlEx.insertChild(out, "<w:jc w:val=\"$v\"/>", "w:jc", PPR_ORDER)
        return if (XmlEx.isEmpty(out)) "" else out
    }

    /**
     * Inserts a new paragraph after block [id] (-1 = at the start of the body) and returns its id.
     * When the block before it is an editable paragraph of the same [style], the new paragraph continues it: it
     * copies that paragraph's `w:pPr` (so a list goes on) and the formatting of its last run.
     */
    fun insertParagraphAfter(id: Int, runs: List<Run>, style: ParaStyle): Int {
        if (bodyStart < 0) throw IllegalStateException("document has no body")
        val anchorIdx = if (id == -1) -1 else indexOf(id)
        val pos = if (anchorIdx < 0) bodyStart else entries[anchorIdx].end
        val anchor = entries.getOrNull(anchorIdx)
        val anchorPara = anchor?.block as? Paragraph
        var pPr = ""
        var srcRPr: List<String> = emptyList()
        if (anchor != null && anchorPara != null && anchorPara.editable && anchorPara.style == style) {
            val p = Xml.elemAt(xml, anchor.start)
            if (p != null) {
                val kids = Xml.children(xml, p)
                pPr = kids.firstOrNull { it.name == "w:pPr" }?.let { XmlEx.src(xml, it) } ?: ""
                kids.lastOrNull { it.name == "w:r" }?.let { r -> Xml.child(xml, r, "w:rPr") }?.let { srcRPr = listOf(XmlEx.src(xml, it)) }
            }
        } else if (style != ParaStyle.BODY) {
            pPr = restylePPr("", style, anchorIdx, anchorIdx + 1)
        }
        val clean = normRuns(runs)
        val ids = RevIds(emptyList())
        val newXml = "<w:p>" + pPr + runsXml(clean, srcRPr, emptyList(), ids) + "</w:p>"
        xml = xml.substring(0, pos) + newXml + xml.substring(pos)
        for (k in anchorIdx + 1 until entries.size) { entries[k].start += newXml.length; entries[k].end += newXml.length }
        val newId = nextId++
        entries.add(anchorIdx + 1, Entry(newId, pos, pos + newXml.length, paraOf(newId, style, clean, true, null, pPr), srcRPr, true, revIds = ids.used))
        changedIds.add(newId)
        touched()
        return newId
    }

    /**
     * Removes an editable paragraph, a table or a picture.
     * @throws IllegalStateException for read-only paragraphs and opaque blocks, which this model cannot safely remove.
     */
    fun deleteBlock(id: Int) {
        val idx = indexOf(id)
        val e = entries[idx]
        when (val b = e.block) {
            is Paragraph -> if (!b.editable) throw IllegalStateException("paragraph $id is read-only (${b.readOnlyWhy})")
            is OpaqueBlock -> throw IllegalStateException("block $id (${b.kind}) is read-only")
            else -> {}
        }
        val len = e.end - e.start
        val seg = xml.substring(e.start, e.end)
        xml = xml.substring(0, e.start) + xml.substring(e.end)
        entries.removeAt(idx)
        for (k in idx until entries.size) { entries[k].start -= len; entries[k].end -= len }
        if (e.isNew) changedIds.remove(id) else { changedIds.remove(id); deletedCount++ }
        touched()
        if (seg.contains("<w:comment")) rehome(idx, anchorsIn(seg))
    }

    /**
     * M5: comment anchors of a block that was just removed (it was at [idx]) move to the end of the editable
     * paragraph before it, else to the start of the one after it, as empty ranges — so text that was only moved to a
     * neighbour (joining paragraphs) keeps its comments. With no such neighbour the comments go with the block.
     */
    private fun rehome(idx: Int, anchors: List<Mark>) {
        if (anchors.isEmpty()) return
        fun editableAt(i: Int): Entry? = entries.getOrNull(i)?.takeIf { (it.block as? Paragraph)?.editable == true }
        val prev = editableAt(idx - 1)
        val next = editableAt(idx)
        when {
            prev != null -> { val p = prev.block as Paragraph; val len = p.text.length; writePara(idx - 1, p.runs, prev.marks + anchors.map { it.at(len) }) }
            next != null -> writePara(idx, (next.block as Paragraph).runs, anchors.map { it.at(0) } + next.marks)
            else -> dropComments(withReplies(anchors.map { it.cid }.toSet()))
        }
    }

    /**
     * M2 (d17): sets the list level (0–8) of a bulleted or numbered paragraph: `w:ilvl` in its `w:numPr`. When the
     * list comes from the paragraph style only, a direct `w:numPr` with the style's numId is added. Only `w:pPr` changes.
     */
    fun setListLevel(id: Int, level: Int) {
        val old = editablePara(id)
        if (old.style != ParaStyle.BULLET && old.style != ParaStyle.NUMBERED) throw IllegalStateException("paragraph $id is not in a list")
        val lvl = level.coerceIn(0, 8)
        editPPr(id) { pPrIn ->
            val pPr = pPrIn.ifEmpty { "<w:pPr></w:pPr>" }
            val numPr = XmlEx.childOf(pPr, "w:numPr")
            val ilvl = "<w:ilvl w:val=\"$lvl\"/>"
            if (numPr != null) {
                val np = XmlEx.insertChild(XmlEx.removeChildren(XmlEx.src(pPr, numPr), setOf("w:ilvl")), ilvl, "w:ilvl", NUMPR_ORDER)
                pPr.substring(0, numPr.start) + np + pPr.substring(numPr.end)
            } else {
                val numId = styles().num(pStyleOf(pPr))?.first?.takeIf { it != 0 } ?: ownNumId(old.style == ParaStyle.BULLET)
                XmlEx.insertChild(pPr, "<w:numPr>$ilvl<w:numId w:val=\"$numId\"/></w:numPr>", "w:numPr", PPR_ORDER)
            }
        }
    }

    /** M2 (d17): direct left indent in twips (`w:ind w:left`); 0 removes it. Other `w:ind` attributes stay. */
    fun setIndent(id: Int, twips: Int) {
        editablePara(id)
        val v = twips.coerceAtLeast(0)
        editPPr(id) { withAttrs(it, "w:ind", listOf("w:start" to null, "w:left" to (if (v == 0) null else v.toString()))) }
    }

    /** M2 (d19): line spacing in 240ths of a line, auto rule (240 single, 360 one and a half); null removes it. */
    fun setLineSpacing(id: Int, line: Int?) {
        editablePara(id)
        editPPr(id) { withAttrs(it, "w:spacing", listOf("w:line" to line?.coerceAtLeast(1)?.toString(), "w:lineRule" to line?.let { "auto" })) }
    }

    /** M2 (d19): space before / after the paragraph in twips; null removes that attribute. */
    fun setParaSpacing(id: Int, beforeTw: Int?, afterTw: Int?) {
        editablePara(id)
        editPPr(id) { withAttrs(it, "w:spacing", listOf("w:before" to beforeTw?.coerceAtLeast(0)?.toString(), "w:after" to afterTw?.coerceAtLeast(0)?.toString())) }
    }

    private fun editablePara(id: Int): Paragraph {
        val p = entries[indexOf(id)].block as? Paragraph ?: throw IllegalStateException("block $id is not a paragraph")
        if (!p.editable) throw IllegalStateException("paragraph $id is read-only (${p.readOnlyWhy})")
        return p
    }

    /** Rewrites only the `w:pPr` of paragraph [id] ("" = none) and keeps its runs and style. */
    private fun editPPr(id: Int, f: (String) -> String) {
        val idx = indexOf(id)
        val e = entries[idx]
        val old = e.block as Paragraph
        val p = Xml.elemAt(xml, e.start) ?: throw IllegalStateException("paragraph $id is not readable")
        val pPrEl = Xml.child(xml, p, "w:pPr")
        val oldPPr = pPrEl?.let { XmlEx.src(xml, it) } ?: ""
        var newPPr = f(oldPPr)
        if (newPPr.isNotEmpty() && XmlEx.isEmpty(newPPr)) newPPr = ""
        if (newPPr == oldPPr) return
        val seg = when {
            pPrEl != null -> xml.substring(e.start, pPrEl.start) + newPPr + xml.substring(pPrEl.end, e.end)
            p.selfClosing -> XmlEx.openTag(xml, p) + newPPr + "</w:p>"
            else -> xml.substring(e.start, p.openEnd) + newPPr + xml.substring(p.openEnd, e.end)
        }
        replaceEntry(idx, seg)
        e.block = paraOf(id, old.style, old.runs, true, null, newPPr)
        changedIds.add(id)
    }

    /**
     * [pPrIn] with attributes of its child [name] set (null = removed). A missing child is added in schema order;
     * one left with no attributes is dropped.
     */
    private fun withAttrs(pPrIn: String, name: String, attrs: List<Pair<String, String?>>): String {
        val el = if (pPrIn.isEmpty()) null else XmlEx.childOf(pPrIn, name)
        if (el == null) {
            val set = attrs.filter { it.second != null }
            if (set.isEmpty()) return pPrIn
            val tag = "<$name" + set.joinToString("") { " ${it.first}=\"${Xml.escape(it.second!!)}\"" } + "/>"
            return XmlEx.insertChild(pPrIn.ifEmpty { "<w:pPr></w:pPr>" }, tag, name, PPR_ORDER)
        }
        var tag = pPrIn.substring(el.start, el.openEnd)
        for ((a, v) in attrs) tag = XmlEx.tagWithAttr(tag, a, v)
        val bare = BARE_TAG.matches(tag)
        val out = if (bare && el.selfClosing) pPrIn.substring(0, el.start) + pPrIn.substring(el.end)
        else pPrIn.substring(0, el.start) + tag + pPrIn.substring(el.openEnd)
        return if (XmlEx.isEmpty(out)) "" else out
    }

    /** What is shown approximately or kept read-only. Counts are occurrences in the document. */
    fun compat(): List<CompatNote> {
        compatCache?.let { return it }
        val out = ArrayList<CompatNote>()
        fun add(kind: CompatKind, code: String, n: Int) { if (n > 0) out.add(CompatNote(kind, code, n)) }

        // M5: comments, tracked insertions and deletions are part of the model now. What is still read-only is a
        // paragraph with a tracked move, format change or paragraph mark; the count is such paragraphs.
        val opaque = LinkedHashMap<String, Int>()
        var fields = 0
        var complex = 0
        var tracked = 0
        for (e in entries) {
            when (val b = e.block) {
                is OpaqueBlock -> opaque[b.kind] = (opaque[b.kind] ?: 0) + 1
                is Paragraph -> if (!b.editable) { when (b.readOnlyWhy) { "field" -> fields++; "complex" -> complex++; "tracked-changes" -> tracked++ } }
                else -> {}
            }
        }
        add(CompatKind.READ_ONLY, "tracked-changes", tracked)
        for (k in listOf("text-box", "smart-graphic", "chart", "section", "other")) add(CompatKind.READ_ONLY, k, opaque[k] ?: 0)
        add(CompatKind.READ_ONLY, "field", fields)
        add(CompatKind.READ_ONLY, "complex", complex)

        add(CompatKind.APPROX, "columns", Regex("<w:cols\\b[^>]*\\bw:num=\"(\\d+)\"").findAll(xml).count { (it.groupValues[1].toIntOrNull() ?: 1) > 1 })
        add(CompatKind.APPROX, "merged-cells", Regex("<w:(?:gridSpan|vMerge)[\\s/>]").findAll(xml).count())
        add(CompatKind.APPROX, "footnotes", Regex("<w:(?:footnoteReference|endnoteReference)[\\s/>]").findAll(xml).count())
        add(CompatKind.APPROX, "header-footer", Rels.of(pkg, docPart).count { it.type.endsWith("/header") || it.type.endsWith("/footer") })

        val fonts = LinkedHashMap<String, Int>()
        for (tag in Regex("<w:rFonts\\b[^>]*>").findAll(xml)) {
            val names = Regex("w:(?:ascii|hAnsi|eastAsia|cs)=\"([^\"]+)\"").findAll(tag.value).map { Xml.unescape(it.groupValues[1]) }.toSet()
            for (n in names) fonts[n] = (fonts[n] ?: 0) + 1
        }
        for ((n, c) in fonts) add(CompatKind.APPROX, "font:$n", c)
        compatCache = out
        return out
    }

    /**
     * The package as bytes. Untouched since open / the last save: exactly the bytes it was opened from. Otherwise
     * only the main document part differs, plus the parts an edit needed: numbering / styles, the comment parts,
     * a header / footer, and — when a part was added — the relationships and content types.
     */
    fun save(): ByteArray {
        val src = source
        if (!dirty && src != null) return src.copyOf()
        if (xmlChanged) pkg.putText(docPart, xml)
        val out = pkg.write()
        source = out
        xmlChanged = false
        auxChanged = false
        changedIds.clear()
        deletedCount = 0
        entries.forEach { it.isNew = false }
        return out.copyOf()
    }

    // ------------------------------------------------------------------------------------------------ M5 (d29): comments

    private var commentsCache: List<ReviewComment>? = null

    /** Who answers whom, read from the comments part and the extended part. */
    private class Threads(
        val recs: List<DocxComments.Rec>,
        val byId: Map<Int, DocxComments.Rec>,
        /** comment id → id of the first comment of its thread (itself for one). */
        val rootOf: Map<Int, Int>,
        /** first comment id → ids of its replies, in file order. */
        val replies: Map<Int, List<Int>>,
        val done: Set<Int>,
    )

    private fun threads(): Threads {
        val recs = (relPart("/comments")?.let { pkg.text(it) }?.let { DocxComments.parse(it) } ?: emptyList()).distinctBy { it.id }
        val exs = relPart("/commentsExtended")?.let { pkg.text(it) }?.let { DocxComments.parseEx(it) } ?: emptyList()
        val exBy = exs.associateBy { it.paraId.uppercase() }
        val byPara = HashMap<String, DocxComments.Rec>()
        for (r in recs) for (pid in r.paraIds) byPara[pid.uppercase()] = r
        fun exOf(r: DocxComments.Rec): DocxComments.Ex? = r.paraIds.asReversed().firstNotNullOfOrNull { exBy[it.uppercase()] }
        val rootOf = HashMap<Int, Int>()
        val replies = LinkedHashMap<Int, MutableList<Int>>()
        val done = HashSet<Int>()
        for (r in recs) {
            var cur = r
            var hops = 0
            while (hops++ < 50) {
                val parent = exOf(cur)?.parent?.let { byPara[it.uppercase()] } ?: break
                if (parent === cur || parent === r) break
                cur = parent
            }
            rootOf[r.id] = cur.id
            if (cur !== r) replies.getOrPut(cur.id) { ArrayList() }.add(r.id)
            if (exOf(r)?.done == true) done.add(r.id)
        }
        return Threads(recs, recs.associateBy { it.id }, rootOf, replies, done)
    }

    /** [ids] plus the replies of every first comment among them. */
    private fun withReplies(ids: Set<Int>): Set<Int> {
        val t = threads()
        return ids + ids.flatMap { t.replies[it] ?: emptyList() }
    }

    /** One comment anchor found in source text: [start] until [end] is what to remove to take it out. */
    private class Found(val kind: Int, val cid: Int, val start: Int, val end: Int, val raw: String, /** where its run ends */ val after: Int = end)

    /** Every comment anchor in [seg] (one element as text), in order; a reference counts as its whole run. */
    private fun anchorElems(seg: String): List<Found> {
        if (!seg.contains("<w:comment")) return emptyList()
        val root = Xml.root(seg) ?: return emptyList()
        val out = ArrayList<Found>()
        fun id(e: Xml.Elem) = Xml.attr(seg, e, "w:id")?.toIntOrNull() ?: -1
        fun walk(e: Xml.Elem) {
            for (c in Xml.children(seg, e)) when (c.name) {
                "w:commentRangeStart" -> out.add(Found(Mark.C_START, id(c), c.start, c.end, seg.substring(c.start, c.end)))
                "w:commentRangeEnd" -> out.add(Found(Mark.C_END, id(c), c.start, c.end, seg.substring(c.start, c.end)))
                "w:r" -> {
                    val kids = Xml.children(seg, c)
                    val ref = kids.firstOrNull { it.name == "w:commentReference" }
                    if (ref != null) {
                        val alone = kids.all { it.name == "w:rPr" || it === ref }
                        // a reference that shares its run with something else is taken out (and kept) on its own
                        if (alone) out.add(Found(Mark.C_REF, id(ref), c.start, c.end, seg.substring(c.start, c.end)))
                        else out.add(Found(Mark.C_REF, id(ref), ref.start, ref.end, "<w:r>" + seg.substring(ref.start, ref.end) + "</w:r>", c.end))
                    }
                }
                "w:pPr", "w:rPr", "w:tblPr", "w:tcPr", "w:trPr", "w:tblGrid" -> {}
                else -> walk(c)
            }
        }
        walk(Xml.Elem("", root.start, root.start, root.end, root.end, false))
        return out
    }

    /** The comment anchors in [seg] as marks (offset 0). */
    private fun anchorsIn(seg: String): List<Mark> = anchorElems(seg).map { Mark(0, it.kind, it.cid, it.raw) }

    /** Text of one block for quoting, with the comment anchors found in it: (kind, comment id, offset in the text). */
    private class Flat(val text: String, val anchors: List<Triple<Int, Int, Int>>)

    private fun flatOf(e: Entry): Flat {
        val b = e.block
        if (b is Paragraph && b.editable) return Flat(b.text, e.marks.filter { it.kind <= Mark.C_REF }.map { Triple(it.kind, it.cid, it.off) })
        val el = Xml.elemAt(xml, e.start) ?: return Flat("", emptyList())
        val sb = StringBuilder()
        val out = ArrayList<Triple<Int, Int, Int>>()
        val deep = b !is Paragraph
        var paras = 0
        fun walk(parent: Xml.Elem, mute: Boolean) {
            for (c in Xml.children(xml, parent)) when (c.name) {
                "w:commentRangeStart" -> out.add(Triple(Mark.C_START, idAttr(c), sb.length))
                "w:commentRangeEnd" -> out.add(Triple(Mark.C_END, idAttr(c), sb.length))
                "w:r" -> {
                    Xml.child(xml, c, "w:commentReference")?.let { out.add(Triple(Mark.C_REF, idAttr(it), sb.length)) }
                    if (!mute) sb.append(runText(c))
                }
                "w:ins", "w:moveTo", "w:hyperlink", "w:smartTag", "w:fldSimple", "w:sdt", "w:sdtContent", "w:customXml", "w:dir", "w:bdo" -> walk(c, mute)
                "w:del", "w:moveFrom" -> walk(c, true)
                "w:pPr", "w:rPr", "w:tblPr", "w:tcPr", "w:trPr", "w:tblGrid" -> {}
                "w:p" -> if (deep) { if (paras++ > 0 && !mute) sb.append('\n'); walk(c, mute) }
                else -> if (deep) walk(c, mute)
            }
        }
        if (deep) walk(Xml.Elem("", el.start, el.start, el.end, el.end, false), false) else walk(el, false)
        return Flat(sb.toString(), out)
    }

    /** Characters [a, b) of block [e] as the document says them (tracked deletions left out). */
    private fun quotePart(e: Entry, flat: Flat, a: Int, b: Int): String {
        val p = e.block as? Paragraph
        val lo = a.coerceIn(0, flat.text.length)
        val hi = b.coerceIn(lo, flat.text.length)
        if (p == null || !p.editable || p.runs.none { it.rev?.kind == RevisionKind.DELETE }) return flat.text.substring(lo, hi)
        return Tracking.slice(p.runs, lo, hi).filter { it.rev?.kind != RevisionKind.DELETE }.joinToString("") { it.text }
    }

    /**
     * M5 (d29): the comment threads of the document, in the order the file lists them. Each is a THREAD:
     * [ReviewComment.block] = id of the paragraph its range starts in, [ReviewComment.start] / [ReviewComment.end] =
     * character offsets in that paragraph's [Paragraph.text] (a range that goes on into later paragraphs ends at the
     * end of the first), [ReviewComment.quote] = the commented text, replies nested, resolved as the file says.
     * A comment anchored where there is no paragraph block (a table cell) or whose anchor is missing has block -1;
     * it can still be answered, resolved, edited and deleted.
     */
    fun comments(): List<ReviewComment> {
        commentsCache?.let { return it }
        val t = threads()
        if (t.recs.isEmpty()) return emptyList<ReviewComment>().also { commentsCache = it }
        // where each comment is anchored: entry index and offset of its range start, range end and reference
        val flats = HashMap<Int, Flat>()
        fun flat(i: Int): Flat = flats.getOrPut(i) { flatOf(entries[i]) }
        val sAt = HashMap<Int, Pair<Int, Int>>()
        val eAt = HashMap<Int, Pair<Int, Int>>()
        val rAt = HashMap<Int, Pair<Int, Int>>()
        var next = xml.indexOf("<w:comment")
        for ((i, e) in entries.withIndex()) {
            if (next < 0) break
            if (next < e.start) next = xml.indexOf("<w:comment", e.start)
            if (next < 0) break
            if (next >= e.end) continue
            for ((kind, cid, off) in flat(i).anchors) {
                val m = when (kind) { Mark.C_START -> sAt; Mark.C_END -> eAt; else -> rAt }
                if (cid !in m) m[cid] = i to off
            }
        }
        val out = ArrayList<ReviewComment>()
        for (r in t.recs) {
            if (t.rootOf[r.id] != r.id) continue
            val ref = rAt[r.id]
            val s = sAt[r.id] ?: ref
            val en = (eAt[r.id] ?: ref)?.takeIf { s != null && (it.first > s.first || (it.first == s.first && it.second >= s.second)) } ?: s
            var block = -1
            var start = 0
            var end = 0
            var quote = ""
            if (s != null && en != null) {
                val para = entries[s.first].block as? Paragraph
                if (para != null) {
                    block = para.id
                    start = s.second
                    end = if (en.first == s.first) en.second else flat(s.first).text.length
                }
                quote = if (en.first == s.first) quotePart(entries[s.first], flat(s.first), s.second, en.second)
                else {
                    val parts = ArrayList<String>()
                    parts.add(quotePart(entries[s.first], flat(s.first), s.second, Int.MAX_VALUE))
                    for (k in s.first + 1 until en.first) parts.add(quotePart(entries[k], flat(k), 0, Int.MAX_VALUE))
                    parts.add(quotePart(entries[en.first], flat(en.first), 0, en.second))
                    parts.joinToString("\n")
                }
            }
            val replies = (t.replies[r.id] ?: emptyList()).mapNotNull { t.byId[it] }.map { ReviewReply(it.id.toString(), it.author, it.date, it.text) }
            out.add(ReviewComment(r.id.toString(), CommentKind.THREAD, r.author, r.date, r.text, quote, r.id in t.done, replies, block, start, end))
        }
        commentsCache = out
        return out
    }

    private fun commentsChanged() { auxChanged = true; commentsCache = null; compatCache = null }

    /** The part of relationship type [relType] next to the main part, created (with relationship and content type) when missing. */
    private fun ensurePart(typeSuffix: String, fileName: String, relType: String, contentType: String, initial: () -> String): String {
        relPart(typeSuffix)?.let { return it }
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.')
        var name = OoxmlPackage.resolve(docPart, fileName)
        var n = 1
        while (pkg.has(name)) name = OoxmlPackage.resolve(docPart, "$base${n++}.$ext")
        pkg.putText(name, initial())
        Rels.add(pkg, docPart, relType, XmlEx.relativize(docPart, name))
        ContentTypes.addOverride(pkg, name, contentType)
        auxChanged = true
        return name
    }

    /** Every 8-digit paragraph / durable id the document and its comment parts use. */
    private fun usedHexIds(): MutableSet<String> {
        val out = HashSet<String>(DocxComments.hexIds(xml))
        for (t in listOf("/comments", "/commentsExtended", "/commentsIds", "/commentsExtensible")) out.addAll(DocxComments.hexIds(relPart(t)?.let { pkg.text(it) }))
        return out
    }

    /** Id of the style named [name] (any type), when the file has it. */
    private fun styleIdByName(name: String): String? = styles().byId.values.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id

    /** A new `w:id` for a comment: above every comment the part has and every anchor in the text. */
    private fun newCommentId(recs: List<DocxComments.Rec>): Int {
        val inText = Regex("<w:comment(?:RangeStart|RangeEnd|Reference)\\b[^>]*\\bw:id=\"(\\d+)\"").findAll(xml).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: -1
        return maxOf(recs.maxOfOrNull { it.id } ?: -1, inText) + 1
    }

    /** Adds an entry for comment paragraph [paraId] to the extended part (created when missing). */
    private fun addEx(paraId: String, parent: String?) {
        val part = ensurePart("/commentsExtended", "commentsExtended.xml", DocxComments.REL_EXTENDED, DocxComments.CT_EXTENDED) { DocxComments.newExtendedXml() }
        val x = pkg.text(part) ?: return
        val el = DocxComments.rootPrefix(x).let { if (it.isEmpty()) "" else "$it:" }
        val ap = DocxComments.prefixFor(x, DocxComments.NS_W15)
        val a = ap ?: "w15"
        val tag = "<${el}commentEx" + (if (ap == null) " xmlns:w15=\"${DocxComments.NS_W15}\"" else "") + " $a:paraId=\"$paraId\"" +
            (if (parent != null) " $a:paraIdParent=\"$parent\"" else "") + " $a:done=\"0\"/>"
        pkg.putText(part, Xml.appendChild(x, tag))
    }

    /** Keeps the two newer comment parts in step when the file has them: a lasting id (and UTC date) for [paraId]. */
    private fun addDurable(paraId: String, dateIso: String) {
        val idsPart = relPart("/commentsIds") ?: return
        val x = pkg.text(idsPart) ?: return
        if (Regex("paraId=\"$paraId\"", RegexOption.IGNORE_CASE).containsMatchIn(x)) return
        val pre = DocxComments.rootPrefix(x)
        if (pre.isEmpty()) return
        val durable = DocxComments.freshHexId(usedHexIds())
        pkg.putText(idsPart, Xml.appendChild(x, "<$pre:commentId $pre:paraId=\"$paraId\" $pre:durableId=\"$durable\"/>"))
        val cexPart = relPart("/commentsExtensible") ?: return
        val cx = pkg.text(cexPart) ?: return
        val cp = DocxComments.rootPrefix(cx)
        if (cp.isEmpty()) return
        val utc = if (dateIso.endsWith("Z")) " $cp:dateUtc=\"${XmlEx.esc(dateIso)}\"" else ""
        pkg.putText(cexPart, Xml.appendChild(cx, "<$cp:commentExtensible $cp:durableId=\"$durable\"$utc/>"))
    }

    /** Writes a new comment into the comment parts and returns its `w:id`. [parentParaId] makes it a reply. */
    private fun newComment(author: String, text: String, dateIso: String, parentParaId: String?): Int {
        val part = ensurePart("/comments", "comments.xml", DocxComments.REL_COMMENTS, DocxComments.CT_COMMENTS) { DocxComments.newCommentsXml() }
        val cx = pkg.text(part)!!
        val id = newCommentId(DocxComments.parse(cx))
        val paraId = DocxComments.freshHexId(usedHexIds())
        val el = DocxComments.commentXml(
            id, author, dateIso, ReviewText.initials(author), XmlEx.clean(text), paraId, DocxComments.prefixFor(cx, DocxComments.NS_W14),
            styleIdByName("annotation text"), styleIdByName("annotation reference"),
        )
        pkg.putText(part, Xml.appendChild(cx, el))
        addEx(paraId, parentParaId)
        addDurable(paraId, dateIso)
        commentsChanged()
        return id
    }

    private fun refRun(cid: Int): String =
        "<w:r>" + (styleIdByName("annotation reference")?.let { "<w:rPr><w:rStyle w:val=\"${Xml.escape(it)}\"/></w:rPr>" } ?: "") + "<w:commentReference w:id=\"$cid\"/></w:r>"

    /**
     * M5 (d29): a new comment by [author] on characters [start, end) of paragraph [blockId] ([start] == [end]: the
     * whole paragraph; offsets count in [Paragraph.text]). Returns its id. A read-only paragraph can be commented too,
     * always as a whole. The comment parts, their relationships and content types are added when the file has none.
     * @throws IllegalStateException when the block is not a paragraph.
     */
    fun addComment(blockId: Int, start: Int, end: Int, author: String, text: String, dateIso: String): String {
        val idx = indexOf(blockId)
        val e = entries[idx]
        val p = e.block as? Paragraph ?: throw IllegalStateException("block $blockId is not a paragraph")
        val cid = newComment(author, text, dateIso, null)
        val sRaw = "<w:commentRangeStart w:id=\"$cid\"/>"
        val eRaw = "<w:commentRangeEnd w:id=\"$cid\"/>"
        if (p.editable) {
            val len = p.text.length
            var a = minOf(start, end).coerceIn(0, len)
            var b = maxOf(start, end).coerceIn(0, len)
            if (a == b) { a = 0; b = len }
            val ms = e.marks.toMutableList()
            // the range opens after whatever sits at its first character and closes before a range that opens at its end
            val i = ms.indexOfLast { it.off <= a } + 1
            ms.add(i, Mark(a, Mark.C_START, cid, sRaw))
            val j = (i + 1 until ms.size).firstOrNull { ms[it].off > b || (ms[it].off == b && ms[it].opens) } ?: ms.size
            ms.add(j, Mark(b, Mark.C_END, cid, eRaw))
            ms.add(j + 1, Mark(b, Mark.C_REF, cid, refRun(cid)))
            writePara(idx, p.runs, ms)
        } else {
            val el = Xml.elemAt(xml, e.start) ?: throw IllegalStateException("paragraph $blockId is not readable")
            val seg = if (el.selfClosing) XmlEx.openTag(xml, el) + sRaw + eRaw + refRun(cid) + "</w:p>" else {
                val at = Xml.child(xml, el, "w:pPr")?.end ?: el.openEnd
                xml.substring(e.start, at) + sRaw + xml.substring(at, el.closeStart) + eRaw + refRun(cid) + "</w:p>"
            }
            replaceEntry(idx, seg)
            reparse(idx)
            changedIds.add(blockId)
        }
        return cid.toString()
    }

    /** The `paraId` the extended part knows comment [id] by, adding the id and the entry when the comment has none yet. */
    private fun ensureEx(id: Int): String {
        val part = relPart("/comments") ?: throw IllegalArgumentException("no comment $id")
        val cx = pkg.text(part)!!
        val rec = DocxComments.parse(cx).firstOrNull { it.id == id } ?: throw IllegalArgumentException("no comment $id")
        val exs = relPart("/commentsExtended")?.let { pkg.text(it) }?.let { DocxComments.parseEx(it) } ?: emptyList()
        rec.paraIds.asReversed().firstOrNull { pid -> exs.any { it.paraId.equals(pid, ignoreCase = true) } }?.let { return it }
        val pid = rec.paraId ?: DocxComments.freshHexId(usedHexIds()).also { pkg.putText(part, DocxComments.withParaId(cx, rec, it)) }
        addEx(pid, null)
        addDurable(pid, rec.date)
        commentsChanged()
        return pid
    }

    /**
     * M5 (d29): answers comment [id] (a reply id answers that reply's thread). Returns the reply's id. The reply gets
     * its own anchors in the text, next to the thread's.
     * @throws IllegalArgumentException when there is no such comment.
     */
    fun replyComment(id: String, author: String, text: String, dateIso: String): String {
        val t = threads()
        val root = t.rootOf[id.toIntOrNull() ?: -1] ?: throw IllegalArgumentException("no comment $id")
        val members = setOf(root) + (t.replies[root] ?: emptyList())
        val parent = ensureEx(root)
        val cid = newComment(author, text, dateIso, parent)
        // anchors: range start after the thread's last one, range end and reference after the thread's last reference
        var sHit: Pair<Int, Int>? = null
        var rHit: Pair<Int, Int>? = null
        for ((i, e) in entries.withIndex()) {
            val seg = xml.substring(e.start, e.end)
            for (f in anchorElems(seg)) {
                if (f.cid !in members) continue
                if (f.kind == Mark.C_START) sHit = i to f.end
                if (f.kind == Mark.C_REF) rHit = i to f.after
            }
        }
        val r = rHit
        if (r != null) {
            val sRaw = "<w:commentRangeStart w:id=\"$cid\"/>"
            val tail = "<w:commentRangeEnd w:id=\"$cid\"/>" + refRun(cid)
            val s = sHit
            val edits = HashMap<Int, MutableList<Triple<Int, Int, String>>>()
            if (s == null || s.first > r.first || (s.first == r.first && s.second > r.second)) edits.getOrPut(r.first) { ArrayList() }.add(Triple(r.second, r.second, sRaw + tail))
            else {
                edits.getOrPut(s.first) { ArrayList() }.add(Triple(s.second, s.second, sRaw))
                edits.getOrPut(r.first) { ArrayList() }.add(Triple(r.second, r.second, tail))
            }
            for ((i, list) in edits) {
                val e = entries[i]
                replaceEntry(i, Xml.splice(xml.substring(e.start, e.end), list))
                reparse(i)
                changedIds.add(e.id)
            }
        }
        return cid.toString()
    }

    /**
     * M5 (d29): marks the thread of comment [id] resolved or open again (`w15:done` on every comment of the thread;
     * the first one is what [comments] reads).
     */
    fun setCommentResolved(id: String, resolved: Boolean) {
        val t = threads()
        val root = t.rootOf[id.toIntOrNull() ?: -1] ?: throw IllegalArgumentException("no comment $id")
        ensureEx(root)
        val t2 = threads()
        val paraIds = (listOf(root) + (t2.replies[root] ?: emptyList())).flatMap { t2.byId[it]?.paraIds ?: emptyList() }.map { it.uppercase() }.toSet()
        val part = relPart("/commentsExtended") ?: return
        val x = pkg.text(part) ?: return
        val v = if (resolved) "1" else "0"
        val edits = DocxComments.parseEx(x).filter { it.paraId.uppercase() in paraIds }.mapNotNull { ex ->
            val keys = Xml.attrs(x, ex.el).keys
            val key = keys.firstOrNull { it.substringAfter(':') == "done" } ?: (keys.first { it.substringAfter(':') == "paraId" }.substringBeforeLast("paraId") + "done")
            val tag = XmlEx.withAttr(x, ex.el, key, v)
            if (tag == x.substring(ex.el.start, ex.el.openEnd)) null else Triple(ex.el.start, ex.el.openEnd, tag)
        }
        if (edits.isEmpty()) return
        pkg.putText(part, Xml.splice(x, edits))
        commentsChanged()
    }

    /** M5 (d29): replaces the text of comment or reply [id] (author, date and place stay). */
    fun editComment(id: String, text: String) {
        val part = relPart("/comments") ?: throw IllegalArgumentException("no comment $id")
        val cx = pkg.text(part)!!
        val rec = DocxComments.parse(cx).firstOrNull { it.id == id.toIntOrNull() } ?: throw IllegalArgumentException("no comment $id")
        val clean = XmlEx.clean(text)
        if (rec.text == clean) return
        pkg.putText(part, DocxComments.withText(cx, rec, clean))
        commentsChanged()
    }

    /**
     * M5 (d29): deletes comment [id]. The first comment of a thread takes its replies and every anchor of the thread
     * with it; a reply id deletes only that reply. An unknown id does nothing.
     */
    fun deleteComment(id: String) {
        val t = threads()
        val cid = id.toIntOrNull() ?: return
        if (cid !in t.byId) return
        dropComments(if (t.rootOf[cid] == cid) setOf(cid) + (t.replies[cid] ?: emptyList()) else setOf(cid))
    }

    /** Takes the comments [ids] out of the comment parts and their anchors out of the text. */
    private fun dropComments(ids: Set<Int>) {
        if (ids.isEmpty()) return
        var next = xml.indexOf("<w:comment")
        var i = 0
        while (i < entries.size && next >= 0) {
            val e = entries[i]
            if (next < e.start) next = xml.indexOf("<w:comment", e.start)
            if (next < 0) break
            if (next < e.end) {
                val seg = xml.substring(e.start, e.end)
                val edits = anchorElems(seg).filter { it.cid in ids }.map { Triple(it.start, it.end, "") }
                if (edits.isNotEmpty()) {
                    replaceEntry(i, Xml.splice(seg, edits))
                    reparse(i)
                    changedIds.add(e.id)
                    next = xml.indexOf("<w:comment", e.end)
                }
            }
            i++
        }
        val part = relPart("/comments") ?: return
        val cx = pkg.text(part) ?: return
        val gone = DocxComments.parse(cx).filter { it.id in ids }
        if (gone.isEmpty()) return
        pkg.putText(part, DocxComments.without(cx) { c -> Xml.local(c.name) == "comment" && Xml.attr(cx, c, "w:id")?.toIntOrNull() in ids })
        val paraIds = gone.flatMap { it.paraIds }.map { it.uppercase() }.toSet()
        fun local(x: String, c: Xml.Elem, n: String): String? = Xml.attrs(x, c).entries.firstOrNull { it.key.substringAfter(':') == n }?.value?.uppercase()
        relPart("/commentsExtended")?.let { p ->
            val x = pkg.text(p) ?: return@let
            val out = DocxComments.without(x) { local(x, it, "paraId") in paraIds }
            if (out != x) pkg.putText(p, out)
        }
        val durable = HashSet<String>()
        relPart("/commentsIds")?.let { p ->
            val x = pkg.text(p) ?: return@let
            val out = DocxComments.without(x) { c -> (local(x, c, "paraId") in paraIds).also { hit -> if (hit) local(x, c, "durableId")?.let { durable.add(it) } } }
            if (out != x) pkg.putText(p, out)
        }
        if (durable.isNotEmpty()) relPart("/commentsExtensible")?.let { p ->
            val x = pkg.text(p) ?: return@let
            val out = DocxComments.without(x) { local(x, it, "durableId") in durable }
            if (out != x) pkg.putText(p, out)
        }
        commentsChanged()
    }

    // ------------------------------------------------------------------------------------------------ M5 (d30): tracked changes

    private class RevRef(val rev: Revision, val entryId: Int, val ordinal: Int)
    private var revCache: List<RevRef>? = null

    private fun revRefs(): List<RevRef> {
        revCache?.let { return it }
        val out = ArrayList<RevRef>()
        val seen = HashMap<String, Int>()
        fun add(g: DocxRevisions.Group, block: Int, entryId: Int, ordinal: Int) {
            val base = "r" + g.wid.ifEmpty { "x" }
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            out.add(RevRef(Revision(if (n == 1) base else "$base.$n", g.kind, g.author, g.date, if (g.kind == RevisionKind.FORMAT) "" else g.text.toString(), block), entryId, ordinal))
        }
        var m = TRACKED.find(xml)
        for (e in entries) {
            if (m == null) break
            if (m.range.first < e.start) m = TRACKED.find(xml, e.start)
            if (m == null) break
            if (m.range.first >= e.end) continue
            DocxRevisions.groups(xml.substring(e.start, e.end)).forEachIndexed { k, g -> add(g, e.id, e.id, k) }
        }
        // a tracked change of the page setup sits after the last block
        bodySectPr()?.let { sp -> if (!sp.selfClosing) DocxRevisions.groups(xml.substring(sp.start, sp.end)).forEachIndexed { k, g -> add(g, -1, -1, k) } }
        revCache = out
        return out
    }

    /**
     * M5 (d30): the tracked changes of the document in reading order. Neighbouring insertions (or deletions) by one
     * author in one paragraph are one revision. INSERT and DELETE can be accepted / rejected; FORMAT (a tracked
     * format change, paragraph mark, table row or cell) and MOVE are listed only. Ids are valid until the next change.
     */
    fun revisions(): List<Revision> = revRefs().map { it.rev }

    private fun resolveRevision(id: String, accept: Boolean): Boolean {
        val ref = revRefs().firstOrNull { it.rev.id == id } ?: return false
        if (!ref.rev.canResolve || ref.entryId < 0) return false
        val idx = entries.indexOfFirst { it.id == ref.entryId }
        if (idx < 0) return false
        val e = entries[idx]
        val seg = xml.substring(e.start, e.end)
        val g = DocxRevisions.groups(seg).getOrNull(ref.ordinal) ?: return false
        val out = DocxRevisions.resolve(seg, g, accept)
        if (out == seg) return false
        replaceEntry(idx, out)
        reparse(idx)
        changedIds.add(e.id)
        return true
    }

    /**
     * M5 (d30): accepts revision [id]: an insertion becomes ordinary text, a deletion's text goes. Only that
     * change's own elements are rewritten. False for an unknown id and for FORMAT / MOVE.
     */
    fun acceptRevision(id: String): Boolean = resolveRevision(id, true)

    /** M5 (d30): rejects revision [id]: an insertion's text goes, a deletion's text is ordinary text again. */
    fun rejectRevision(id: String): Boolean = resolveRevision(id, false)

    private fun resolveAll(accept: Boolean): Int {
        var n = 0
        var m = TRACKED.find(xml)
        for (idx in entries.indices) {
            val e = entries[idx]
            if (m == null) break
            if (m.range.first < e.start) m = TRACKED.find(xml, e.start)
            if (m == null) break
            if (m.range.first >= e.end) continue
            var changed = false
            var open = -1
            while (true) {
                val seg = xml.substring(e.start, e.end)
                val gs = DocxRevisions.groups(seg).filter { it.kind == RevisionKind.INSERT || it.kind == RevisionKind.DELETE }
                // counted: the changes that are gone afterwards (rejecting an insertion takes a deletion nested in it along)
                if (open < 0) { open = gs.size; n += open }
                val g = gs.firstOrNull() ?: break
                val out = DocxRevisions.resolve(seg, g, accept)
                if (out == seg) { n -= gs.size; break }
                replaceEntry(idx, out)
                changed = true
            }
            if (changed) { reparse(idx); changedIds.add(e.id); m = TRACKED.find(xml, e.end) }
        }
        return n
    }

    /** M5 (d30): accepts every insertion and deletion; FORMAT / MOVE stay. Returns how many it resolved. */
    fun acceptAll(): Int = resolveAll(true)

    /** M5 (d30): rejects every insertion and deletion; FORMAT / MOVE stay. Returns how many it resolved. */
    fun rejectAll(): Int = resolveAll(false)

    // ------------------------------------------------------------------------------------------------ M5 (d31): header and footer

    /** The body-level `w:sectPr` (the last section's page setup); null when the body has none. */
    private fun bodySectPr(): Xml.Elem? {
        if (bodyStart < 0) return null
        var i = entries.lastOrNull()?.end ?: bodyStart
        var hit: Xml.Elem? = null
        while (true) {
            val e = Xml.nextElem(xml, i, xml.length) ?: break
            if (e.name == "w:sectPr") hit = e
            i = e.end
        }
        return hit
    }

    private fun hfRef(kind: String): Xml.Elem? {
        val sp = bodySectPr() ?: return null
        return Xml.children(xml, sp, "w:${kind}Reference").firstOrNull { (Xml.attr(xml, it, "w:type") ?: "default") == "default" }
    }

    /** The part holding the default header / footer ([kind] "header" | "footer") of the last section. */
    private fun hfPart(kind: String): String? {
        val ref = hfRef(kind) ?: return null
        val rid = Xml.attrs(xml, ref).entries.firstOrNull { it.key.substringAfter(':') == "id" && it.key != "w:id" }?.value ?: return null
        val rel = Rels.of(pkg, docPart).firstOrNull { it.id == rid && !it.external } ?: return null
        return OoxmlPackage.resolve(docPart, rel.target).takeIf { pkg.has(it) }
    }

    private fun hfParaText(x: String, p: Xml.Elem): String {
        val sb = StringBuilder()
        fun walk(e: Xml.Elem) {
            for (c in Xml.children(x, e)) when (c.name) {
                "w:r" -> for (k in Xml.children(x, c)) when (k.name) {
                    "w:t" -> sb.append(Xml.text(x, k))
                    "w:tab" -> sb.append('\t')
                    "w:br", "w:cr" -> sb.append('\n')
                }
                "w:pPr", "w:del", "w:moveFrom", "w:drawing", "w:pict", "w:object", "mc:AlternateContent" -> {}
                else -> walk(c)
            }
        }
        walk(p)
        return sb.toString()
    }

    /** True when a header / footer part holds nothing but paragraphs of plain runs: what [setHeaderFooter] can rewrite. */
    private fun hfPlain(x: String): Boolean {
        val root = Xml.root(x) ?: return false
        for (p in Xml.children(x, root)) {
            if (p.name != "w:p") return false
            for (k in Xml.children(x, p)) when (k.name) {
                "w:pPr", "w:proofErr", "w:bookmarkStart", "w:bookmarkEnd" -> {}
                "w:r" -> for (c in Xml.children(x, k)) when (c.name) {
                    "w:rPr", "w:t", "w:tab", "w:cr", "w:lastRenderedPageBreak", "w:noBreakHyphen", "w:softHyphen" -> {}
                    "w:br" -> if (Xml.attrs(x, c).isNotEmpty()) return false
                    else -> return false
                }
                else -> return false
            }
        }
        return true
    }

    private fun hfText(kind: String): String? {
        val x = hfPart(kind)?.let { pkg.text(it) } ?: return null
        val root = Xml.root(x) ?: return ""
        return Xml.findAll(x, root, "w:p").joinToString("\n") { hfParaText(x, it) }
    }

    /**
     * M5 (d31): text of the default header of the last section (the body-level `w:sectPr`), paragraphs one per line,
     * tabs as `\t`; null when the document has none. First-page and even-page headers are not looked at.
     */
    fun headerText(): String? = hfText("header")

    /** M5 (d31): the same for the default footer. */
    fun footerText(): String? = hfText("footer")

    /**
     * M5 (d31): false when the existing header holds more than plain paragraphs (a field such as a page number,
     * a table, a picture): it is shown and left alone. True also when there is none yet.
     */
    val headerEditable: Boolean get() = hfPart("header")?.let { pkg.text(it) }?.let { hfPlain(it) } ?: true

    /** M5 (d31): the same for the default footer. */
    val footerEditable: Boolean get() = hfPart("footer")?.let { pkg.text(it) }?.let { hfPlain(it) } ?: true

    /**
     * M5 (d31): sets the default header and footer of the last section. null = leave as it is; "" = keep the part,
     * empty its text. A missing part is created (not for ""), with its relationship, content type and the reference
     * in the body-level `w:sectPr`. An existing plain one keeps its paragraph properties and the first run's
     * formatting; one that is not editable is left alone. Returns true when something changed.
     */
    fun setHeaderFooter(header: String?, footer: String?): Boolean {
        var changed = false
        if (header != null && setHf("header", header)) changed = true
        if (footer != null && setHf("footer", footer)) changed = true
        return changed
    }

    private fun hfRuns(line: String, rPr: String): String {
        if (line.isEmpty()) return ""
        val sb = StringBuilder("<w:r>").append(rPr)
        val buf = StringBuilder()
        fun flush() {
            if (buf.isEmpty()) return
            val t = buf.toString()
            val keep = t.first().isWhitespace() || t.last().isWhitespace() || t.contains("  ")
            sb.append(if (keep) "<w:t xml:space=\"preserve\">" else "<w:t>").append(Xml.escape(t)).append("</w:t>")
            buf.setLength(0)
        }
        for (c in line) if (c == '\t') { flush(); sb.append("<w:tab/>") } else buf.append(c)
        flush()
        return sb.append("</w:r>").toString()
    }

    private fun setHf(kind: String, textIn: String): Boolean {
        val text = XmlEx.clean(textIn.replace("\r\n", "\n").replace('\r', '\n'))
        val lines = text.split('\n')
        val part = hfPart(kind)
        if (part != null) {
            val x = pkg.text(part) ?: return false
            if (!hfPlain(x) || hfText(kind) == text) return false
            val root = Xml.root(x) ?: return false
            val paras = Xml.children(x, root)
            val rPr = paras.firstNotNullOfOrNull { p -> Xml.children(x, p, "w:r").firstOrNull()?.let { Xml.child(x, it, "w:rPr") } }?.let { XmlEx.src(x, it) } ?: ""
            val sb = StringBuilder()
            lines.forEachIndexed { i, line ->
                val src = paras.getOrNull(i) ?: paras.lastOrNull()
                sb.append(if (src != null && i < paras.size) XmlEx.openTag(x, src) else "<w:p>")
                src?.let { Xml.child(x, it, "w:pPr") }?.let { sb.append(x, it.start, it.end) }
                val own = src?.let { p -> Xml.children(x, p, "w:r").firstOrNull()?.let { Xml.child(x, it, "w:rPr") } }?.let { XmlEx.src(x, it) } ?: rPr
                sb.append(hfRuns(line, own)).append("</w:p>")
            }
            val out = when {
                root.selfClosing -> x.substring(0, root.start) + XmlEx.openTag(x, root) + sb + "</" + root.name + ">" + x.substring(root.end)
                paras.isEmpty() -> x.substring(0, root.openEnd) + sb + x.substring(root.openEnd)
                else -> x.substring(0, paras.first().start) + sb + x.substring(paras.last().end)
            }
            pkg.putText(part, out)
            auxChanged = true
            return true
        }
        if (text.isEmpty()) return false
        if (bodyStart < 0) throw IllegalStateException("document has no body")
        // a new part
        var n = 1
        while (pkg.has(OoxmlPackage.resolve(docPart, "$kind$n.xml"))) n++
        val name = OoxmlPackage.resolve(docPart, "$kind$n.xml")
        val tag = if (kind == "header") "w:hdr" else "w:ftr"
        val pPr = styles().idForName(kind)?.let { "<w:pPr><w:pStyle w:val=\"${Xml.escape(it)}\"/></w:pPr>" } ?: ""
        pkg.putText(name, XmlEx.XML_DECL + "<$tag xmlns:w=\"${DocxTemplates.NS_W}\" xmlns:r=\"${DocxTemplates.NS_R}\">" +
            lines.joinToString("") { "<w:p>$pPr" + hfRuns(it, "") + "</w:p>" } + "</$tag>")
        val rid = Rels.add(pkg, docPart, DocxTemplates.REL + "/" + kind, XmlEx.relativize(docPart, name))
        ContentTypes.addOverride(pkg, name, "application/vnd.openxmlformats-officedocument.wordprocessingml.$kind+xml")
        auxChanged = true
        // the reference, first among the children of the body-level w:sectPr
        val rp = Xml.root(xml)?.let { r -> Xml.attrs(xml, r).entries.firstOrNull { it.key.startsWith("xmlns:") && it.value == DocxTemplates.NS_R }?.key?.substringAfter(':') }
        val ref = "<w:${kind}Reference w:type=\"default\" " + (if (rp != null) "$rp:id=\"$rid\"" else "xmlns:r=\"${DocxTemplates.NS_R}\" r:id=\"$rid\"") + "/>"
        val sp = bodySectPr()
        val stale = hfRef(kind)
        xml = when {
            sp == null -> {
                val at = xml.lastIndexOf("</w:body>").takeIf { it >= 0 } ?: throw IllegalStateException("document has no body")
                xml.substring(0, at) + "<w:sectPr>$ref</w:sectPr>" + xml.substring(at)
            }
            sp.selfClosing -> xml.substring(0, sp.start) + XmlEx.openTag(xml, sp) + ref + "</w:sectPr>" + xml.substring(sp.end)
            stale != null -> xml.substring(0, stale.start) + ref + xml.substring(stale.end)
            else -> {
                val refs = Xml.children(xml, sp).filter { it.name == "w:headerReference" || it.name == "w:footerReference" }
                val at = (if (kind == "header") refs.lastOrNull { it.name == "w:headerReference" } else refs.lastOrNull())?.end ?: sp.openEnd
                xml.substring(0, at) + ref + xml.substring(at)
            }
        }
        touched()
        return true
    }

    // ------------------------------------------------------------------------------------------------ M5 (d24): format painter

    /**
     * M5 (d24): the character formatting picked up by [runFormatAt]: the complete `w:rPr` of a run. It is a copy —
     * it stays good when its source is edited, and can be applied any number of times (also to a document reopened
     * from saved bytes of the same file). The fields say what the formatting is, for showing it.
     */
    class RunFormat internal constructor(
        internal val rPr: String,
        val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean,
        val color: Int?, val highlight: String?, val size: Int?, val vertAlign: String?,
    )

    /**
     * M5 (d24): picks up the formatting of the character at [offset] of paragraph [blockId] (at or past the end: the
     * last character's; an empty paragraph: none).
     * @throws IllegalStateException when the block is not a paragraph.
     */
    fun runFormatAt(blockId: Int, offset: Int): RunFormat {
        val e = entries[indexOf(blockId)]
        val p = e.block as? Paragraph ?: throw IllegalStateException("block $blockId is not a paragraph")
        var rPr = ""
        if (p.editable) {
            (Tracking.runAt(p.runs, offset.coerceAtLeast(0)) ?: p.runs.lastOrNull())?.let { rPr = effectiveRPr(it, e.srcRPr) }
        } else Xml.elemAt(xml, e.start)?.let { el ->
            val found = ArrayList<Xml.Elem>()
            visibleRuns(el, found)
            var pos = 0
            var hit: Xml.Elem? = null
            for (r in found) {
                val len = runText(r).length
                if (len == 0) continue
                hit = r
                if (offset < pos + len) break
                pos += len
            }
            hit?.let { rPr = rPrOf(it) }
        }
        if (rPr.isNotEmpty()) {
            rPr = XmlEx.removeChildren(rPr, setOf("w:rPrChange"))
            if (XmlEx.isEmpty(rPr)) rPr = ""
        }
        return RunFormat(rPr, runToggle(rPr, "w:b"), runToggle(rPr, "w:i"), runToggle(rPr, "w:u"), runToggle(rPr, "w:strike"), colorOf(rPr), highlightOf(rPr), sizeOf(rPr), vertAlignOf(rPr))
    }

    /**
     * M5 (d24): gives characters [start, end) of paragraph [toBlockId] ([start] == [end]: the whole paragraph) the
     * formatting [format] — the complete `w:rPr` it was picked up with (font, size, colour, bold …), in place of
     * their own. Links and tracked-change marks of the target stay. False when there is nothing to paint.
     * @throws IllegalStateException when the block is not an editable paragraph.
     */
    fun paintFormat(format: RunFormat, toBlockId: Int, start: Int, end: Int): Boolean {
        val p = editablePara(toBlockId)
        val idx = indexOf(toBlockId)
        val e = entries[idx]
        val len = p.text.length
        var a = minOf(start, end).coerceIn(0, len)
        var b = maxOf(start, end).coerceIn(0, len)
        if (a == b) { a = 0; b = len }
        if (a == b) return false
        val k = e.srcRPr.size
        e.srcRPr = e.srcRPr + format.rPr
        val painted = Tracking.slice(p.runs, a, b).map {
            Run(it.text, format.bold, format.italic, format.underline, k, format.strike, format.color, format.highlight, format.size, it.link, format.vertAlign, it.rev)
        }
        writePara(idx, Tracking.normalize(Tracking.slice(p.runs, 0, a) + painted + Tracking.slice(p.runs, b, len)), e.marks)
        return true
    }

    /** M5 (d24): the two steps in one: the formatting at [fromOffset] of [fromBlockId] onto [start, end) of [toBlockId]. */
    fun paintFormat(fromBlockId: Int, fromOffset: Int, toBlockId: Int, start: Int, end: Int): Boolean =
        paintFormat(runFormatAt(fromBlockId, fromOffset), toBlockId, start, end)

    // ------------------------------------------------------------------------------------------------ reading

    private fun indexOf(id: Int): Int {
        val i = entries.indexOfFirst { it.id == id }
        if (i < 0) throw IllegalArgumentException("no block with id $id")
        return i
    }

    private fun relPart(typeSuffix: String): String? =
        Rels.of(pkg, docPart).firstOrNull { !it.external && it.type.endsWith(typeSuffix) }
            ?.let { OoxmlPackage.resolve(docPart, it.target) }?.takeIf { pkg.has(it) }

    private fun styles(): DocxStyles = stylesCache ?: DocxStyles(relPart("/styles")?.let { pkg.text(it) }).also { stylesCache = it }

    private fun numbering(): DocxNumbering = numberingCache ?: DocxNumbering(relPart("/numbering")?.let { pkg.text(it) }).also { numberingCache = it }

    private fun isRangeMarker(name: String): Boolean =
        name == "w:proofErr" || name.endsWith("RangeStart") || name.endsWith("RangeEnd") ||
            name == "w:bookmarkStart" || name == "w:bookmarkEnd" || name == "w:permStart" || name == "w:permEnd"

    private fun pStyleOf(pPr: String): String? {
        if (pPr.isEmpty()) return null
        val e = XmlEx.childOf(pPr, "w:pStyle") ?: return null
        return Xml.attr(pPr, e, "w:val")
    }

    private fun styleOf(pPr: String): ParaStyle {
        var pStyle: String? = null
        var outline: Int? = null
        var directNum: Int? = null
        var directLvl: Int? = null
        val root = if (pPr.isEmpty()) null else Xml.root(pPr)
        if (root != null) {
            for (c in Xml.children(pPr, root)) {
                when (c.name) {
                    "w:pStyle" -> pStyle = Xml.attr(pPr, c, "w:val")
                    "w:outlineLvl" -> outline = Xml.attr(pPr, c, "w:val")?.toIntOrNull()
                    "w:numPr" -> {
                        directNum = Xml.child(pPr, c, "w:numId")?.let { Xml.attr(pPr, it, "w:val")?.toIntOrNull() }
                        directLvl = Xml.child(pPr, c, "w:ilvl")?.let { Xml.attr(pPr, it, "w:val")?.toIntOrNull() }
                    }
                }
            }
        }
        val st = styles()
        st.heading(pStyle)?.let { return it }
        outline?.let { if (it in 0..8) return DocxStyles.levelStyle(it) }
        val styleNum = st.num(pStyle)
        val numId = directNum ?: styleNum?.first
        if (numId != null) {
            if (numId == 0) return ParaStyle.BODY
            return when (numbering().isBullet(numId, directLvl ?: styleNum?.second ?: 0)) {
                true -> ParaStyle.BULLET
                false -> ParaStyle.NUMBERED
                null -> st.listHint(pStyle) ?: ParaStyle.NUMBERED
            }
        }
        return st.listHint(pStyle) ?: ParaStyle.BODY
    }

    /** Text of one run: tabs, line breaks and page breaks become `\t`, `\n`, `\u000C`. */
    private fun runText(r: Xml.Elem): String {
        val sb = StringBuilder()
        for (c in Xml.children(xml, r)) {
            when (c.name) {
                "w:t", "w:delText" -> sb.append(Xml.text(xml, c))
                "w:tab" -> sb.append('\t')
                "w:br" -> sb.append(if (Xml.attr(xml, c, "w:type") == "page") '\u000C' else '\n')
                "w:cr" -> sb.append('\n')
                "w:noBreakHyphen" -> sb.append('‑')
                "w:softHyphen" -> sb.append('­')
            }
        }
        return sb.toString()
    }

    private fun rPrOf(r: Xml.Elem): String = Xml.child(xml, r, "w:rPr")?.let { XmlEx.src(xml, it) } ?: ""

    /**
     * A run as the model shows it. Bold / italic / underline are the run's own formatting (set on the run or by
     * its character style); what the paragraph style adds on top is not a run flag — the style is.
     */
    private fun modelRun(r: Xml.Elem, src: Int, link: String? = null): Run {
        val rPr = rPrOf(r)
        return Run(runText(r), runToggle(rPr, "w:b"), runToggle(rPr, "w:i"), runToggle(rPr, "w:u"), src, runToggle(rPr, "w:strike"), colorOf(rPr), highlightOf(rPr), sizeOf(rPr), link, vertAlignOf(rPr))
    }

    /** M3 d20: URL of an external hyperlink element (`r:id` → a TargetMode="External" relationship); null for anchors and broken ids. */
    private fun linkUrl(h: Xml.Elem): String? {
        val rid = Xml.attr(xml, h, "r:id") ?: return null
        return Rels.of(pkg, docPart).firstOrNull { it.id == rid && it.external }?.target
    }

    /** M3 d20: id of the external hyperlink relationship to [url], adding one when the document has none yet. */
    private fun linkRid(url: String): String {
        Rels.of(pkg, docPart).firstOrNull { it.external && it.type == HYPERLINK_REL && it.target == url }?.let { return it.id }
        auxChanged = true
        return Rels.add(pkg, docPart, HYPERLINK_REL, url, external = true)
    }

    /**
     * A paragraph model with its M2 properties read from [pPr]. Read-only paragraphs were always shown with
     * alignment 0 ([align0]); that stays so.
     */
    private fun paraOf(id: Int, style: ParaStyle, runs: List<Run>, editable: Boolean, why: String?, pPr: String, align0: Boolean = false): Paragraph {
        val numPr = if (pPr.isEmpty()) null else XmlEx.childOf(pPr, "w:numPr")
        val direct = numPr?.let { Xml.child(pPr, it, "w:ilvl") }?.let { Xml.attr(pPr, it, "w:val")?.toIntOrNull() }
        val pStyle = pStyleOf(pPr)
        val level = (direct ?: styles().num(pStyle)?.second ?: 0).coerceIn(0, 8)
        val ind = if (pPr.isEmpty()) null else XmlEx.childOf(pPr, "w:ind")
        val indent = ind?.let { (Xml.attr(pPr, it, "w:left") ?: Xml.attr(pPr, it, "w:start"))?.toIntOrNull() } ?: 0
        val sp = if (pPr.isEmpty()) null else XmlEx.childOf(pPr, "w:spacing")
        val rule = sp?.let { Xml.attr(pPr, it, "w:lineRule") }
        val line = sp?.takeIf { rule == null || rule == "auto" }?.let { Xml.attr(pPr, it, "w:line")?.toIntOrNull() }
        return Paragraph(
            id, style, runs, editable, why, if (align0) 0 else alignOf(pPr),
            level = level, indentTw = indent.coerceAtLeast(0), lineSpacing = line,
            spaceBefore = sp?.let { Xml.attr(pPr, it, "w:before")?.toIntOrNull() },
            spaceAfter = sp?.let { Xml.attr(pPr, it, "w:after")?.toIntOrNull() },
            baseSizeHp = styles().sizeHp(pStyle),
            rule = (if (pPr.isEmpty()) null else XmlEx.childOf(pPr, "w:pBdr"))?.let { b -> Xml.child(pPr, b, "w:bottom") }
                ?.let { Xml.attr(pPr, it, "w:val") }?.let { it != "none" && it != "nil" } ?: false,
        )
    }

    private fun rPrVal(rPr: String, prop: String): String? {
        if (rPr.isEmpty()) return null
        val e = XmlEx.childOf(rPr, prop) ?: return null
        return Xml.attr(rPr, e, "w:val")
    }

    private fun colorOf(rPr: String): Int? = rPrVal(rPr, "w:color")?.takeIf { it.length == 6 && it != "auto" }?.toIntOrNull(16)
    private fun highlightOf(rPr: String): String? = rPrVal(rPr, "w:highlight")?.takeIf { it.isNotEmpty() && it != "none" }
    private fun sizeOf(rPr: String): Int? = rPrVal(rPr, "w:sz")?.toIntOrNull()?.takeIf { it > 0 }
    private fun vertAlignOf(rPr: String): String? = rPrVal(rPr, "w:vertAlign")?.takeIf { it == "superscript" || it == "subscript" }

    private fun runToggle(rPr: String, prop: String): Boolean =
        DocxStyles.toggle(rPr, prop) ?: styles().charToggle(prop, DocxStyles.rStyle(rPr)) ?: false

    /** Runs that carry visible text, looking through insertions, hyperlinks, fields and inline containers. */
    private fun visibleRuns(within: Xml.Elem, out: MutableList<Xml.Elem>) {
        for (c in Xml.children(xml, within)) {
            when (c.name) {
                "w:r" -> out.add(c)
                "w:ins", "w:moveTo", "w:hyperlink", "w:smartTag", "w:fldSimple", "w:sdt", "w:sdtContent", "w:customXml", "w:dir", "w:bdo" -> visibleRuns(c, out)
            }
        }
    }

    /** What [scanInline] collects from an editable paragraph. */
    private class Scan {
        val runs = ArrayList<Run>()
        val srcRPr = ArrayList<String>()
        val marks = ArrayList<Mark>()
        val revIds = ArrayList<Pair<RunRev, Int>>()
        var pos = 0
    }

    private fun idAttr(e: Xml.Elem): Int = Xml.attr(xml, e, "w:id")?.toIntOrNull() ?: -1

    /** A run of plain text: properties, text, tabs, breaks. [deleted] = inside a tracked deletion, where the text is `w:delText`. */
    private fun isPlainRun(rc: List<Xml.Elem>, deleted: Boolean): Boolean {
        for (c in rc) {
            when (c.name) {
                "w:rPr", "w:t", "w:tab", "w:cr", "w:noBreakHyphen", "w:softHyphen", "w:lastRenderedPageBreak" -> {}
                "w:delText" -> if (!deleted) return false
                "w:br" -> {
                    val a = Xml.attrs(xml, c)
                    if (!(a.isEmpty() || (a.size == 1 && a["w:type"] == "page"))) return false
                }
                else -> return false
            }
        }
        return true
    }

    /**
     * Reads the children of a paragraph (or of a link / tracked insertion / deletion in it) into [out]; false when
     * something is there that this model cannot write back. What it can: plain runs, external links, tracked
     * insertions and deletions of plain runs (M5, not nested), bookmark ends and comment anchors (M5).
     */
    private fun scanInline(kids: List<Xml.Elem>, link: String?, rev: RunRev?, out: Scan): Boolean {
        for (k in kids) {
            when (k.name) {
                "w:pPr", "w:proofErr" -> {}
                "w:bookmarkStart" -> out.marks.add(Mark(out.pos, Mark.B_START, idAttr(k), XmlEx.src(xml, k)))
                "w:bookmarkEnd" -> out.marks.add(Mark(out.pos, Mark.B_END, idAttr(k), XmlEx.src(xml, k)))
                "w:commentRangeStart" -> out.marks.add(Mark(out.pos, Mark.C_START, idAttr(k), XmlEx.src(xml, k)))
                "w:commentRangeEnd" -> out.marks.add(Mark(out.pos, Mark.C_END, idAttr(k), XmlEx.src(xml, k)))
                "w:hyperlink" -> {
                    if (link != null || rev != null) return false
                    val url = linkUrl(k) ?: return false
                    val inner = Xml.children(xml, k)
                    if (inner.isEmpty() || !scanInline(inner, url, null, out)) return false
                }
                "w:ins", "w:del" -> {
                    if (rev != null || k.selfClosing) return false
                    val r = RunRev(if (k.name == "w:ins") RevisionKind.INSERT else RevisionKind.DELETE, Xml.attr(xml, k, "w:author") ?: "", Xml.attr(xml, k, "w:date") ?: "")
                    Xml.attr(xml, k, "w:id")?.toIntOrNull()?.let { out.revIds.add(r to it) }
                    if (!scanInline(Xml.children(xml, k), link, r, out)) return false
                }
                "w:r" -> {
                    val rc = Xml.children(xml, k)
                    val ref = rc.firstOrNull { it.name == "w:commentReference" }
                    if (ref != null) {
                        // the run that holds a comment's mark: kept as it is, at its place
                        if (rc.any { it.name != "w:rPr" && it !== ref }) return false
                        out.marks.add(Mark(out.pos, Mark.C_REF, idAttr(ref), XmlEx.src(xml, k)))
                    } else {
                        if (!isPlainRun(rc, rev?.kind == RevisionKind.DELETE)) return false
                        out.srcRPr.add(rPrOf(k))
                        val r = modelRun(k, out.srcRPr.size - 1, link).let { if (rev != null) it.copy(rev = rev) else it }
                        if (r.text.isNotEmpty()) { out.runs.add(r); out.pos += r.text.length }
                    }
                }
                else -> return false
            }
        }
        return true
    }

    private fun parseParagraph(p: Xml.Elem, id: Int): Entry {
        val kids = Xml.children(xml, p)
        val pPr = kids.firstOrNull { it.name == "w:pPr" }?.let { XmlEx.src(xml, it) } ?: ""
        val seg = xml.substring(p.start, p.end)
        val style = styleOf(pPr)

        val tracked = TRACKED.containsMatchIn(seg)
        val graphic = GRAPHIC.containsMatchIn(seg)
        val field = FIELD.containsMatchIn(seg)
        val section = pPr.isNotEmpty() && XmlEx.childOf(pPr, "w:sectPr") != null
        // M5: text inserted or deleted with tracking is modelled; a tracked move, format change or paragraph mark is not
        val otherTracked = tracked && (TRACKED_OTHER.containsMatchIn(seg) || (pPr.isNotEmpty() && TRACKED_MARK.containsMatchIn(pPr)))

        if (!otherTracked && !graphic && !field && !section) {
            val sc = Scan()
            if (scanInline(kids, null, null, sc)) return Entry(id, p.start, p.end, paraOf(id, style, sc.runs, true, null, pPr), sc.srcRPr, false, sc.marks, sc.revIds)
        }

        val found = ArrayList<Xml.Elem>()
        visibleRuns(p, found)
        val runs = found.mapIndexed { i, r -> modelRun(r, i) }.filter { it.text.isNotEmpty() }
        val blank = runs.all { it.text.isBlank() }
        val block: DocBlock = when {
            tracked -> paraOf(id, style, runs, false, "tracked-changes", pPr, align0 = true)
            graphic && blank -> pictureOf(id, p, seg) ?: OpaqueBlock(id, graphicKind(seg))
            graphic -> paraOf(id, style, runs, false, "complex", pPr, align0 = true)
            field -> paraOf(id, style, runs, false, "field", pPr, align0 = true)
            section && blank -> OpaqueBlock(id, "section")
            else -> paraOf(id, style, runs, false, "complex", pPr, align0 = true)
        }
        return Entry(id, p.start, p.end, block, emptyList(), false)
    }

    /** Reads block [idx] again from the source text after an edit made there directly; its id stays. */
    private fun reparse(idx: Int) {
        val e = entries[idx]
        val el = Xml.elemAt(xml, e.start) ?: return
        when (el.name) {
            "w:p" -> { val n = parseParagraph(el, e.id); e.block = n.block; e.srcRPr = n.srcRPr; e.marks = n.marks; e.revIds = n.revIds }
            "w:tbl" -> e.block = parseTable(e.id, el)
        }
        blocksCache = null
    }

    private fun graphicKind(seg: String): String = when {
        seg.contains("<w:txbxContent") -> "text-box"
        seg.contains("<c:chart") || seg.contains("/drawingml/2006/chart\"") -> "chart"
        seg.contains("<dgm:relIds") || seg.contains("/drawingml/2006/diagram\"") -> "smart-graphic"
        else -> "other"
    }

    /** A paragraph whose only content is one picture → [ImageBlock]; anything else → null. */
    private fun pictureOf(id: Int, p: Xml.Elem, seg: String): ImageBlock? {
        if (seg.contains("<w:pict") || seg.contains("<w:object") || seg.contains("<mc:AlternateContent") || seg.contains("<w:txbxContent")) return null
        val drawings = Xml.findAll(xml, p, "w:drawing")
        if (drawings.size != 1) return null
        val d = drawings[0]
        if (Xml.find(xml, d, "pic:pic") == null) return null
        val blip = Xml.find(xml, d, "a:blip") ?: return null
        val rid = Xml.attr(xml, blip, "r:embed") ?: return null
        val rel = Rels.of(pkg, docPart).firstOrNull { it.id == rid && !it.external } ?: return null
        val part = OoxmlPackage.resolve(docPart, rel.target)
        if (!pkg.has(part)) return null
        val ext = Xml.find(xml, d, "wp:extent")
        val w = ext?.let { Xml.attr(xml, it, "cx")?.toLongOrNull() } ?: 0L
        val h = ext?.let { Xml.attr(xml, it, "cy")?.toLongOrNull() } ?: 0L
        return ImageBlock(id, part, w, h)
    }

    private fun parseTable(id: Int, tbl: Xml.Elem): TableBlock {
        val rows = ArrayList<List<String>>()
        // M3 d42: merged cells, horizontal (gridSpan) and vertical (vMerge restart … continue)
        val merges = ArrayList<CellMerge>()
        val open = HashMap<Int, Int>()
        for ((r, tr) in Xml.children(xml, tbl, "w:tr").withIndex()) {
            val seen = HashSet<Int>()
            for (c in tcs(xml, tr)) {
                when (c.vMerge) {
                    "continue" -> open[c.grid]?.let { k -> merges[k] = merges[k].copy(rows = merges[k].rows + 1); seen.add(c.grid) }
                    "restart" -> { merges.add(CellMerge(r, c.grid, 1, c.span)); open[c.grid] = merges.size - 1; seen.add(c.grid) }
                    else -> if (c.span > 1) merges.add(CellMerge(r, c.grid, 1, c.span))
                }
            }
            open.keys.retainAll(seen)
        }
        for (tr in Xml.children(xml, tbl, "w:tr")) {
            val row = ArrayList<String>()
            for (tc in Xml.children(xml, tr, "w:tc")) {
                val paras = Xml.findAll(xml, tc, "w:p").map { p ->
                    val found = ArrayList<Xml.Elem>()
                    visibleRuns(p, found)
                    found.joinToString("") { runText(it) }
                }
                row.add(paras.joinToString("\n"))
                val span = Xml.child(xml, tc, "w:tcPr")?.let { Xml.child(xml, it, "w:gridSpan") }?.let { Xml.attr(xml, it, "w:val")?.toIntOrNull() } ?: 1
                repeat(span - 1) { row.add("") }
            }
            rows.add(row)
        }
        return TableBlock(id, rows, tableWidthPct(tbl), merges.filter { it.rows > 1 || it.cols > 1 })
    }

    // ------------------------------------------------------------------------------------------------ writing

    private fun touched() {
        xmlChanged = true
        blocksCache = null
        compatCache = null
        commentsCache = null
        revCache = null
    }

    private fun replaceEntry(idx: Int, newXml: String) {
        val e = entries[idx]
        val delta = newXml.length - (e.end - e.start)
        xml = xml.substring(0, e.start) + newXml + xml.substring(e.end)
        e.end = e.start + newXml.length
        for (k in idx + 1 until entries.size) { entries[k].start += delta; entries[k].end += delta }
        touched()
    }

    /** Drops empty runs and characters the file format cannot carry; `\r\n` and `\r` become `\n`. */
    private fun normRuns(runs: List<Run>): List<Run> = runs.mapNotNull { r ->
        val raw = r.text.replace("\r\n", "\n").replace('\r', '\n')
        val sb = StringBuilder(raw.length)
        for (c in raw) if (c >= ' ' && c != '￾' && c != '￿' || c == '\t' || c == '\n' || c == '\u000C') sb.append(c)
        if (sb.isEmpty()) null else if (sb.length == r.text.length && raw == r.text) r else r.copy(text = sb.toString())
    }

    /** Gives each tracked insertion / deletion written its `w:id`: the one it had ([known]) when there is one, else a new one. */
    private inner class RevIds(known: List<Pair<RunRev, Int>>) {
        private val free = known.toMutableList()
        val used = ArrayList<Pair<RunRev, Int>>()
        fun idFor(rev: RunRev): Int {
            val i = free.indexOfFirst { it.first == rev }
            val id = if (i >= 0) free.removeAt(i).second else newRevId()
            used.add(rev to id)
            return id
        }
    }

    private var revIdNext = -1

    /** An annotation id no element of the document has: above every `w:id` in it (revisions, bookmarks and comments alike). */
    private fun newRevId(): Int {
        if (revIdNext < 0) revIdNext = (W_ID.findAll(xml).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0) + 1
        return revIdNext++
    }

    /** The `w:rPr` a run is written with ("" = none): that of the original run it names, made to say what the model says. */
    private fun effectiveRPr(r: Run, srcRPr: List<String>): String {
        var rPr = srcRPr.getOrNull(r.src) ?: srcRPr.firstOrNull() ?: ""
        // a run taken out of a link loses the link's character style too
        if (r.link == null && rPr.isNotEmpty() && DocxStyles.rStyle(rPr)?.let { it.equals("Hyperlink", true) || it == styles().idForName("Hyperlink") } == true) rPr = XmlEx.removeChildren(rPr, setOf("w:rStyle"))
        if (vertAlignOf(rPr) != r.vertAlign) rPr = withValue(rPr, setOf("w:vertAlign"), r.vertAlign?.let { listOf("<w:vertAlign w:val=\"$it\"/>" to "w:vertAlign") })
        rPr = withToggle(rPr, "w:b", r.bold)
        rPr = withToggle(rPr, "w:i", r.italic)
        rPr = withToggle(rPr, "w:u", r.underline)
        rPr = withToggle(rPr, "w:strike", r.strike)
        if (colorOf(rPr) != r.color) rPr = withValue(rPr, setOf("w:color"), r.color?.let { listOf("<w:color w:val=\"%06X\"/>".format(it and 0xFFFFFF) to "w:color") })
        if (highlightOf(rPr) != r.highlight) rPr = withValue(rPr, setOf("w:highlight"), r.highlight?.let { listOf("<w:highlight w:val=\"${Xml.escape(it)}\"/>" to "w:highlight") })
        if (sizeOf(rPr) != r.size) rPr = withValue(rPr, setOf("w:sz", "w:szCs"), r.size?.let { listOf("<w:sz w:val=\"$it\"/>" to "w:sz", "<w:szCs w:val=\"$it\"/>" to "w:szCs") })
        return if (rPr.isNotEmpty() && XmlEx.isEmpty(rPr)) "" else rPr
    }

    /** One `w:r`; [deleted] writes its text as `w:delText` (a run inside a tracked deletion). */
    private fun runXml(sb: StringBuilder, r: Run, rPr: String, deleted: Boolean) {
        sb.append("<w:r>").append(rPr)
        val buf = StringBuilder()
        fun flush() {
            if (buf.isEmpty()) return
            val t = buf.toString()
            val keep = t.first().isWhitespace() || t.last().isWhitespace() || t.contains("  ")
            val name = if (deleted) "w:delText" else "w:t"
            sb.append(if (keep) "<$name xml:space=\"preserve\">" else "<$name>").append(Xml.escape(t)).append("</").append(name).append('>')
            buf.setLength(0)
        }
        for (c in r.text) {
            when (c) {
                '\t' -> { flush(); sb.append("<w:tab/>") }
                '\n' -> { flush(); sb.append("<w:br/>") }
                '\u000C' -> { flush(); sb.append("<w:br w:type=\"page\"/>") }
                else -> buf.append(c)
            }
        }
        flush()
        sb.append("</w:r>")
    }

    /**
     * The content of a paragraph after its `w:pPr`: [runs], with [marks] put back between the characters they sit
     * between (a run is cut in two where a mark falls inside it). Neighbouring runs of one link share a
     * `w:hyperlink`; neighbouring runs of one tracked change share a `w:ins` / `w:del` (inside the link, if any).
     */
    private fun runsXml(runs: List<Run>, srcRPr: List<String>, marks: List<Mark> = emptyList(), ids: RevIds? = null): String {
        val pool = ids ?: RevIds(emptyList())
        val sb = StringBuilder()
        var openLink: String? = null
        var openRev: RunRev? = null
        fun closeRev() {
            val r = openRev ?: return
            sb.append(if (r.kind == RevisionKind.DELETE) "</w:del>" else "</w:ins>")
            openRev = null
        }
        fun closeLink() {
            closeRev()
            if (openLink != null) { sb.append("</w:hyperlink>"); openLink = null }
        }
        var mi = 0
        var pos = 0
        /** Writes the marks that sit at or before [at]; a comment's reference run never goes inside a link or a tracked change. */
        fun marksAt(at: Int, nextLink: String?) {
            if (mi >= marks.size || marks[mi].off > at) return
            closeRev()
            var j = mi
            var hasRef = false
            while (j < marks.size && marks[j].off <= at) { if (marks[j].kind == Mark.C_REF) hasRef = true; j++ }
            if (hasRef || nextLink != openLink) closeLink()
            while (mi < j) sb.append(marks[mi++].raw)
        }
        for (r0 in runs) {
            if (r0.text.isEmpty()) continue
            val rev = r0.rev?.takeIf { it.kind == RevisionKind.INSERT || it.kind == RevisionKind.DELETE }
            val rPr = effectiveRPr(r0, srcRPr)
            var rest = r0
            while (true) {
                marksAt(pos, rest.link)
                val nextOff = if (mi < marks.size) marks[mi].off else Int.MAX_VALUE
                val piece = if (nextOff < pos + rest.text.length) rest.copy(text = rest.text.substring(0, nextOff - pos)) else rest
                // M3 d20: neighbouring runs with the same link share one w:hyperlink element
                if (piece.link != openLink) {
                    closeLink()
                    if (piece.link != null) sb.append(if (Xml.root(xml)?.let { Xml.attr(xml, it, "xmlns:r") } != null) "<w:hyperlink r:id=\"" else "<w:hyperlink xmlns:r=\"${DocxTemplates.REL}\" r:id=\"").append(linkRid(piece.link)).append("\" w:history=\"1\">")
                    openLink = piece.link
                }
                if (rev != openRev) {
                    closeRev()
                    if (rev != null) {
                        sb.append(if (rev.kind == RevisionKind.DELETE) "<w:del" else "<w:ins").append(" w:id=\"").append(pool.idFor(rev)).append("\" w:author=\"").append(XmlEx.esc(rev.author)).append('"')
                        if (rev.date.isNotEmpty()) sb.append(" w:date=\"").append(XmlEx.esc(rev.date)).append('"')
                        sb.append('>')
                    }
                    openRev = rev
                }
                runXml(sb, piece, rPr, rev?.kind == RevisionKind.DELETE)
                pos += piece.text.length
                if (piece === rest) break
                rest = rest.copy(text = rest.text.substring(piece.text.length))
            }
        }
        closeLink()
        while (mi < marks.size) sb.append(marks[mi++].raw)
        return sb.toString()
    }

    /** [rPrIn] without the children [remove], then with each (element text, name) of [add] put in schema order. */
    private fun withValue(rPrIn: String, remove: Set<String>, add: List<Pair<String, String>>?): String {
        var rPr = if (rPrIn.isEmpty()) "" else XmlEx.removeChildren(rPrIn, remove)
        for ((el, name) in add ?: emptyList()) rPr = if (rPr.isEmpty()) "<w:rPr>$el</w:rPr>" else XmlEx.insertChild(rPr, el, name, RPR_ORDER)
        return rPr
    }

    /** Returns [rPrIn] with the run-level toggle [prop] made to read [want], touching nothing else in it. */
    private fun withToggle(rPrIn: String, prop: String, want: Boolean): String {
        var rPr = rPrIn
        if (runToggle(rPr, prop) == want) return rPr
        if (rPr.isNotEmpty()) {
            rPr = XmlEx.removeChildren(rPr, when (prop) { "w:b" -> setOf("w:b", "w:bCs"); "w:i" -> setOf("w:i", "w:iCs"); "w:strike" -> setOf("w:strike"); else -> setOf("w:u") })
        }
        if (runToggle(rPr, prop) == want) return rPr       // what is left (a character style, or nothing) already says so
        val el = if (prop == "w:u") (if (want) "<w:u w:val=\"single\"/>" else "<w:u w:val=\"none\"/>")
        else (if (want) "<$prop/>" else "<$prop w:val=\"0\"/>")
        if (rPr.isEmpty()) return "<w:rPr>$el</w:rPr>"
        return XmlEx.insertChild(rPr, el, prop, RPR_ORDER)
    }

    /**
     * `w:pPr` for a paragraph that changes to [target]: the old properties with only the style reference, list
     * membership and outline level replaced. [prevIdx] / [nextIdx] are the neighbouring blocks, used to join a list.
     */
    private fun restylePPr(oldPPr: String, target: ParaStyle, prevIdx: Int, nextIdx: Int): String {
        val st = styles()
        val oldStyle = pStyleOf(oldPPr)
        var pStyle: String? = oldStyle?.takeIf { st.isPlain(it) }
        var numPr: String? = null
        when (target) {
            ParaStyle.BODY -> {}
            ParaStyle.TITLE, ParaStyle.H1, ParaStyle.H2, ParaStyle.H3 -> pStyle = ensureParaStyle(target)
            ParaStyle.BULLET, ParaStyle.NUMBERED -> {
                val nb = listNeighbour(prevIdx, target) ?: listNeighbour(nextIdx, target)
                if (nb != null) {
                    if (nb.first != null) pStyle = nb.first
                    numPr = nb.second
                } else {
                    numPr = "<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"${ownNumId(target == ParaStyle.BULLET)}\"/></w:numPr>"
                    styles().idForName("List Paragraph")?.let { pStyle = it }
                }
            }
        }
        var pPr = if (oldPPr.isEmpty()) "<w:pPr></w:pPr>" else XmlEx.removeChildren(oldPPr, setOf("w:pStyle", "w:numPr", "w:outlineLvl"))
        if (pStyle != null) pPr = XmlEx.insertChild(pPr, "<w:pStyle w:val=\"${Xml.escape(pStyle!!)}\"/>", "w:pStyle", PPR_ORDER)
        if (numPr != null) pPr = XmlEx.insertChild(pPr, numPr, "w:numPr", PPR_ORDER)
        return if (XmlEx.isEmpty(pPr)) "" else pPr
    }

    /** Style id and `w:numPr` of the block at [idx] when it is a paragraph of list style [target]. */
    private fun listNeighbour(idx: Int, target: ParaStyle): Pair<String?, String?>? {
        val e = entries.getOrNull(idx) ?: return null
        val b = e.block as? Paragraph ?: return null
        if (b.style != target) return null
        val p = Xml.elemAt(xml, e.start) ?: return null
        val pPrEl = Xml.child(xml, p, "w:pPr") ?: return null
        val pStyle = Xml.child(xml, pPrEl, "w:pStyle")?.let { Xml.attr(xml, it, "w:val") }
        val numPr = Xml.child(xml, pPrEl, "w:numPr")?.let { XmlEx.src(xml, it) }
        if (pStyle == null && numPr == null) return null
        return pStyle to numPr
    }

    /** Id of the title / heading style, adding the definition to the styles part when the file lacks it. */
    private fun ensureParaStyle(target: ParaStyle): String {
        styles().idFor(target)?.let { return it }
        var part = relPart("/styles")
        if (part == null) {
            part = OoxmlPackage.resolve(docPart, "styles.xml")
            pkg.putText(part, DocxTemplates.stylesXml())
            Rels.add(pkg, docPart, DocxTemplates.REL + "/styles", "styles.xml")
            ContentTypes.addOverride(pkg, part, DocxTemplates.CT_STYLES)
        } else {
            val sx = pkg.text(part)!!
            var id = when (target) { ParaStyle.TITLE -> "Title"; ParaStyle.H1 -> "Heading1"; ParaStyle.H2 -> "Heading2"; else -> "Heading3" }
            while (styles().byId.containsKey(id)) id += "S"
            pkg.putText(part, Xml.appendChild(sx, DocxTemplates.headingStyle(target, id, styles().defaultParaId)))
        }
        auxChanged = true
        stylesCache = null
        return styles().idFor(target) ?: throw IllegalStateException("could not add a style for $target")
    }

    /** numId of a list definition written by this module; bullets share one, every numbered list gets its own. */
    private fun ownNumId(bullet: Boolean): Int {
        if (bullet) ownBulletNumId?.let { return it }
        var part = relPart("/numbering")
        if (part == null) {
            part = OoxmlPackage.resolve(docPart, "numbering.xml")
            pkg.putText(part, DocxTemplates.numberingXml())
            Rels.add(pkg, docPart, DocxTemplates.REL + "/numbering", "numbering.xml")
            ContentTypes.addOverride(pkg, part, DocxTemplates.CT_NUMBERING)
        }
        val nx = pkg.text(part)!!
        val root = Xml.root(nx) ?: throw IllegalStateException("numbering part is not readable")
        val kids = Xml.children(nx, root)
        val absId = (kids.filter { it.name == "w:abstractNum" }.mapNotNull { Xml.attr(nx, it, "w:abstractNumId")?.toIntOrNull() }.maxOrNull() ?: -1) + 1
        val numId = (kids.filter { it.name == "w:num" }.mapNotNull { Xml.attr(nx, it, "w:numId")?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val absXml = DocxTemplates.abstractNum(absId, bullet)
        val numXml = "<w:num w:numId=\"$numId\"><w:abstractNumId w:val=\"$absId\"/></w:num>"
        val out = if (root.selfClosing) {
            Xml.appendInto(nx, root, absXml + numXml)
        } else {
            // schema order: every w:abstractNum before every w:num, both before w:numIdMacAtCleanup
            val tail = kids.firstOrNull { it.name == "w:numIdMacAtCleanup" }?.start ?: root.closeStart
            val absPos = kids.firstOrNull { it.name == "w:num" }?.start ?: tail
            val numPos = kids.lastOrNull { it.name == "w:num" }?.end ?: tail
            if (absPos == numPos) Xml.splice(nx, listOf(Triple(absPos, absPos, absXml + numXml)))
            else Xml.splice(nx, listOf(Triple(absPos, absPos, absXml), Triple(numPos, numPos, numXml)))
        }
        pkg.putText(part, out)
        auxChanged = true
        numberingCache = null
        if (bullet) ownBulletNumId = numId
        return numId
    }

    companion object {
        /** Opens an existing document. Nothing is modified until an edit method is called. */
        fun open(bytes: ByteArray): DocxDocument = DocxDocument(OoxmlPackage.read(bytes), bytes.copyOf())

        /** A new, empty document: one empty body paragraph, title / heading styles, A4 page. */
        fun blank(): DocxDocument = DocxDocument(DocxTemplates.blankPackage(), null)

        /** Text width the app's own tables are made for (A4 with 2.54 cm margins ≈ 9026 twips); `dxa` table widths are read against it. */
        const val TEXT_WIDTH_TWIPS = 9000
        /** Text width for pictures: 6 in. */
        const val TEXT_WIDTH_EMU = 5_486_400L
        private const val HYPERLINK_REL = DocxTemplates.REL + "/hyperlink"
        private val TCPR_ORDER = listOf(
            "w:cnfStyle", "w:tcW", "w:gridSpan", "w:hMerge", "w:vMerge", "w:tcBorders", "w:shd", "w:noWrap", "w:tcMar",
            "w:textDirection", "w:tcFitText", "w:vAlign", "w:hideMark", "w:headers", "w:cellIns", "w:cellDel", "w:cellMerge", "w:tcPrChange",
        )
        private val NUMPR_ORDER = listOf("w:ilvl", "w:numId", "w:numberingChange", "w:ins")
        private val BARE_TAG = Regex("^<[\\w:.-]+\\s*/?>$")
        private val TBL_PR_ORDER = listOf(
            "w:tblStyle", "w:tblpPr", "w:tblOverlap", "w:bidiVisual", "w:tblStyleRowBandSize", "w:tblStyleColBandSize", "w:tblW", "w:jc",
            "w:tblCellSpacing", "w:tblInd", "w:tblBorders", "w:shd", "w:tblLayout", "w:tblCellMar", "w:tblLook", "w:tblCaption", "w:tblDescription",
        )

        private val TRACKED = Regex("<w:(?:ins|del|moveFrom|moveTo|pPrChange|rPrChange|sectPrChange|tblPrChange|numberingChange)[\\s/>]")
        private val TRACKED_MARK = Regex("<w:(?:ins|del|moveFrom|moveTo)[\\s/>]")
        private val TRACKED_OTHER = Regex("<w:(?:moveFrom|moveTo|moveFromRangeStart|moveFromRangeEnd|moveToRangeStart|moveToRangeEnd|pPrChange|rPrChange|sectPrChange|tblPrChange|numberingChange)[\\s/>]")
        private val W_ID = Regex("\\bw:id=\"(\\d+)\"")
        private val GRAPHIC = Regex("<(?:w:drawing|w:pict|w:object|mc:AlternateContent)[\\s/>]")
        private val FIELD = Regex("<w:(?:fldSimple|fldChar|instrText)[\\s/>]")

        private val RPR_ORDER = listOf(
            "w:rStyle", "w:rFonts", "w:b", "w:bCs", "w:i", "w:iCs", "w:caps", "w:smallCaps", "w:strike", "w:dstrike",
            "w:outline", "w:shadow", "w:emboss", "w:imprint", "w:noProof", "w:snapToGrid", "w:vanish", "w:webHidden",
            "w:color", "w:spacing", "w:w", "w:kern", "w:position", "w:sz", "w:szCs", "w:highlight", "w:u", "w:effect",
            "w:bdr", "w:shd", "w:fitText", "w:vertAlign", "w:rtl", "w:cs", "w:em", "w:lang", "w:eastAsianLayout",
            "w:specVanish", "w:oMath", "w:rPrChange",
        )
        private val PPR_ORDER = listOf(
            "w:pStyle", "w:keepNext", "w:keepLines", "w:pageBreakBefore", "w:framePr", "w:widowControl", "w:numPr",
            "w:suppressLineNumbers", "w:pBdr", "w:shd", "w:tabs", "w:suppressAutoHyphens", "w:kinsoku", "w:wordWrap",
            "w:overflowPunct", "w:topLinePunct", "w:autoSpaceDE", "w:autoSpaceDN", "w:bidi", "w:adjustRightInd",
            "w:snapToGrid", "w:spacing", "w:ind", "w:contextualSpacing", "w:mirrorIndents", "w:suppressOverlap", "w:jc",
            "w:textDirection", "w:textAlignment", "w:textboxTightWrap", "w:outlineLvl", "w:divId", "w:cnfStyle",
            "w:rPr", "w:sectPr", "w:pPrChange",
        )
    }
}
