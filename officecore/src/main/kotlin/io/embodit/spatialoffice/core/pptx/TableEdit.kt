package io.embodit.spatialoffice.core.pptx

import io.embodit.spatialoffice.core.docx.XmlEx
import io.embodit.spatialoffice.core.pkg.Xml

/**
 * M3 p40: rows, columns and merged cells of one slide table (`a:tbl`), worked on as text. The table is read into a
 * grid where every position names the cell that owns it (itself, or the first cell of the merged area that covers
 * it); operations change that grid and [xml] writes the `a:tbl` back. A cell whose merge attributes come out as they
 * were keeps its exact text; new cells copy the properties of the cell next to them, with empty text.
 * Column widths are rescaled so that they always add up to the graphic frame's width.
 */
internal class TableEdit private constructor(
    private val open: String,
    private val before: String,
    private val gridOpen: String,
    private val gridTail: String,
    private val cols: MutableList<Col>,
    private val rows: MutableList<Row>,
    private val after: String,
    private val frameW: Long,
) {
    class Col(var raw: String, var w: Long)
    class Row(val open: String, var h: Long, val cells: MutableList<String>, val tail: String)

    /** owner[r][c] = (row, col) of the cell that owns grid position (r, c). */
    private var owner: MutableList<MutableList<Pair<Int, Int>>> = ArrayList()

    val rowCount: Int get() = rows.size
    val colCount: Int get() = cols.size

    /** Sum of the row heights (EMU): what the frame's height should be. */
    val totalHeight: Long get() = rows.sumOf { it.h }

    /** Sum of the column widths (EMU). */
    val totalWidth: Long get() = cols.sumOf { it.w }

    /** Rectangle (r1, c1, r2, c2) of the merged area that covers (r, c). */
    fun area(r: Int, c: Int): IntArray {
        val o = owner[r][c]
        var r2 = o.first; var c2 = o.second
        while (r2 + 1 < rows.size && owner[r2 + 1][o.second] == o) r2++
        while (c2 + 1 < cols.size && owner[o.first][c2 + 1] == o) c2++
        return intArrayOf(o.first, o.second, r2, c2)
    }

    private fun readOwners() {
        owner = MutableList(rows.size) { r -> MutableList(cols.size) { c -> r to c } }
        val taken = Array(rows.size) { BooleanArray(cols.size) }
        for (r in rows.indices) for (c in cols.indices) {
            if (taken[r][c]) continue
            val a = attrs(rows[r].cells[c])
            if (a.hMerge || a.vMerge) continue // a covered cell no area claimed: it owns itself
            val h = a.rowSpan.coerceIn(1, rows.size - r)
            val w = a.gridSpan.coerceIn(1, cols.size - c)
            for (rr in r until r + h) for (cc in c until c + w) {
                if ((rr == r && cc == c) || taken[rr][cc]) continue
                val k = attrs(rows[rr].cells[cc])
                if (k.hMerge || k.vMerge) { owner[rr][cc] = r to c; taken[rr][cc] = true }
            }
        }
    }

    private class Attrs(val gridSpan: Int, val rowSpan: Int, val hMerge: Boolean, val vMerge: Boolean)

    private fun attrs(tc: String): Attrs {
        val e = Xml.root(tc) ?: return Attrs(1, 1, false, false)
        val a = Xml.attrs(tc, e)
        fun on(v: String?) = v == "1" || v == "true"
        return Attrs(a["gridSpan"]?.toIntOrNull() ?: 1, a["rowSpan"]?.toIntOrNull() ?: 1, on(a["hMerge"]), on(a["vMerge"]))
    }

    /** Cell [tc] with its merge attributes set as the grid says ((r, c) in the current grid). */
    private fun cellXml(tc: String, r: Int, c: Int): String {
        val o = owner[r][c]
        var span = 1; var rspan = 1; var h = false; var v = false
        if (o == (r to c)) {
            val ar = area(r, c)
            span = ar[3] - ar[1] + 1; rspan = ar[2] - ar[0] + 1
        } else { h = c != o.second; v = r != o.first }
        val cur = attrs(tc)
        if (cur.gridSpan == span && cur.rowSpan == rspan && cur.hMerge == h && cur.vMerge == v) return tc
        val e = Xml.root(tc)!!
        var tag = tc.substring(e.start, e.openEnd)
        for (n in listOf("rowSpan", "gridSpan", "hMerge", "vMerge")) tag = XmlEx.tagWithAttr(tag, n, null)
        if (rspan > 1) tag = XmlEx.tagWithAttr(tag, "rowSpan", rspan.toString())
        if (span > 1) tag = XmlEx.tagWithAttr(tag, "gridSpan", span.toString())
        if (h) tag = XmlEx.tagWithAttr(tag, "hMerge", "1")
        if (v) tag = XmlEx.tagWithAttr(tag, "vMerge", "1")
        return tag + tc.substring(e.openEnd)
    }

    fun xml(): String {
        val sb = StringBuilder(open).append(before).append(gridOpen)
        for (c in cols) sb.append(withW(c.raw, c.w))
        sb.append(gridTail)
        for ((r, row) in rows.withIndex()) {
            sb.append(XmlEx.tagWithAttr(row.open, "h", row.h.toString()))
            for ((c, tc) in row.cells.withIndex()) sb.append(cellXml(tc, r, c))
            sb.append(row.tail).append("</a:tr>")
        }
        return sb.append(after).append("</a:tbl>").toString()
    }

    private fun withW(raw: String, w: Long): String {
        val e = Xml.root(raw) ?: return raw
        if (Xml.attr(raw, e, "w")?.toLongOrNull() == w) return raw
        return XmlEx.withAttr(raw, e, "w", w.toString()) + raw.substring(e.openEnd)
    }

    /** Widths scaled so they sum to the frame's width; the last column takes the rounding. */
    private fun fitWidths() {
        val total = frameW.takeIf { it > 0 } ?: cols.sumOf { it.w }
        val sum = cols.sumOf { it.w }.coerceAtLeast(1)
        var used = 0L
        for ((i, c) in cols.withIndex()) {
            c.w = if (i == cols.size - 1) (total - used).coerceAtLeast(1) else Math.round(c.w.toDouble() * total / sum).coerceAtLeast(1)
            used += c.w
        }
    }

    // ---------------------------------------------------------------------------------------------- operations

    /** A row inserted above ([below] false) or below row [ref], as tall as it; returns the new row's index. */
    fun insertRow(ref: Int, below: Boolean): Int {
        require(ref in rows.indices) { "row $ref" }
        val at = if (below) ref + 1 else ref
        val src = rows[ref]
        val cells = src.cells.map { blankCell(it) }.toMutableList()
        val newOwners = MutableList(cols.size) { c ->
            // inside a merged area that reaches across the new row's place: the new cell joins it
            if (at in 1 until rows.size && owner[at - 1][c] == owner[at][c]) owner[at][c] else at to c
        }
        for (r in owner.indices) for (c in cols.indices) { val o = owner[r][c]; if (o.first >= at) owner[r][c] = (o.first + 1) to o.second }
        for (c in cols.indices) { val o = newOwners[c]; if (o != (at to c) && o.first >= at) newOwners[c] = (o.first + 1) to o.second }
        rows.add(at, Row(src.open, src.h, cells, ""))
        owner.add(at, newOwners)
        return at
    }

    /** Deletes row [r] (false when it is the only one). A merged area that started there starts in the next row. */
    fun deleteRow(r: Int): Boolean {
        if (rows.size <= 1 || r !in rows.indices) return false
        for (c in cols.indices) {
            if (owner[r][c] != (r to c) || r + 1 >= rows.size || owner[r + 1][c] != (r to c)) continue
            // the area's first cell moves down one row, with its text
            rows[r + 1].cells[c] = rows[r].cells[c]
            for (rr in r + 1 until rows.size) for (cc in cols.indices) if (owner[rr][cc] == (r to c)) owner[rr][cc] = (r + 1) to c
        }
        rows.removeAt(r); owner.removeAt(r)
        for (rr in owner.indices) for (c in cols.indices) { val o = owner[rr][c]; if (o.first > r) owner[rr][c] = (o.first - 1) to o.second }
        return true
    }

    /** A column inserted left ([right] false) or right of column [ref]; returns its index. Widths are refit to the frame. */
    fun insertCol(ref: Int, right: Boolean): Int {
        require(ref in cols.indices) { "column $ref" }
        val at = if (right) ref + 1 else ref
        val newOwners = MutableList(rows.size) { r ->
            if (at in 1 until cols.size && owner[r][at - 1] == owner[r][at]) owner[r][at] else r to at
        }
        for (r in rows.indices) for (c in cols.indices) { val o = owner[r][c]; if (o.second >= at) owner[r][c] = o.first to (o.second + 1) }
        for (r in rows.indices) { val o = newOwners[r]; if (o != (r to at) && o.second >= at) newOwners[r] = o.first to (o.second + 1) }
        for ((r, row) in rows.withIndex()) { row.cells.add(at, blankCell(row.cells[ref])); owner[r].add(at, newOwners[r]) }
        cols.add(at, Col("<a:gridCol w=\"${cols[ref].w}\"/>", cols[ref].w))
        fitWidths()
        return at
    }

    /** Deletes column [c] (false when it is the only one); the others widen to fill the frame. */
    fun deleteCol(c: Int): Boolean {
        if (cols.size <= 1 || c !in cols.indices) return false
        for (r in rows.indices) {
            if (owner[r][c] != (r to c) || c + 1 >= cols.size || owner[r][c + 1] != (r to c)) continue
            rows[r].cells[c + 1] = rows[r].cells[c]
            for (rr in rows.indices) for (cc in c + 1 until cols.size) if (owner[rr][cc] == (r to c)) owner[rr][cc] = r to (c + 1)
        }
        for ((r, row) in rows.withIndex()) { row.cells.removeAt(c); owner[r].removeAt(c) }
        cols.removeAt(c)
        for (r in rows.indices) for (cc in cols.indices) { val o = owner[r][cc]; if (o.second > c) owner[r][cc] = o.first to (o.second - 1) }
        fitWidths()
        return true
    }

    /**
     * Merges the area holding (r, c) with the area to its right ([down] false) or below it; both must span the same
     * rows (columns). The second area's text is added to the first's. False when there is nothing to merge with.
     */
    fun merge(r: Int, c: Int, down: Boolean): Boolean {
        if (r !in rows.indices || c !in cols.indices) return false
        val a = area(r, c)
        val nr = if (down) a[2] + 1 else a[0]
        val nc = if (down) a[1] else a[3] + 1
        if (nr >= rows.size || nc >= cols.size) return false
        val b = area(nr, nc)
        if (down && (b[1] != a[1] || b[3] != a[3])) return false
        if (!down && (b[0] != a[0] || b[2] != a[2])) return false
        val master = a[0] to a[1]
        val other = b[0] to b[1]
        rows[a[0]].cells[a[1]] = joinText(rows[a[0]].cells[a[1]], rows[b[0]].cells[b[1]])
        rows[b[0]].cells[b[1]] = clearText(rows[b[0]].cells[b[1]])
        for (rr in b[0]..b[2]) for (cc in b[1]..b[3]) if (owner[rr][cc] == other) owner[rr][cc] = master
        return true
    }

    // ---------------------------------------------------------------------------------------------- cell text

    private fun hasText(p: String): Boolean = TEXT_RUN.containsMatchIn(p)

    private fun paras(tc: String): List<String> {
        val e = Xml.root(tc) ?: return emptyList()
        val body = Xml.child(tc, e, "a:txBody") ?: return emptyList()
        return Xml.children(tc, body, "a:p").map { XmlEx.src(tc, it) }
    }

    /** [a] with the paragraphs of [b] that hold text added after its own (replacing its paragraphs when it has none). */
    private fun joinText(a: String, b: String): String {
        val extra = paras(b).filter { hasText(it) }
        if (extra.isEmpty()) return a
        val e = Xml.root(a)!!
        val body = Xml.child(a, e, "a:txBody")
        val own = paras(a).filter { hasText(it) }
        if (body == null) return a.substring(0, e.openEnd) + "<a:txBody><a:bodyPr/><a:lstStyle/>" + extra.joinToString("") + "</a:txBody>" + a.substring(e.openEnd)
        val keep = Xml.children(a, body).filter { it.name != "a:p" }.joinToString("") { XmlEx.src(a, it) }
        val newBody = a.substring(body.start, body.openEnd) + keep + (own + extra).joinToString("") + "</a:txBody>"
        return a.substring(0, body.start) + newBody + a.substring(body.end)
    }

    /** [tc] with its text emptied (body properties and the first paragraph's properties kept). */
    private fun clearText(tc: String): String {
        val e = Xml.root(tc) ?: return tc
        val body = Xml.child(tc, e, "a:txBody") ?: return tc
        return tc.substring(0, body.start) + blankBody(tc, body) + tc.substring(body.end)
    }

    private fun blankBody(tc: String, body: Xml.Elem): String {
        val kids = Xml.children(tc, body)
        val bodyPr = kids.firstOrNull { it.name == "a:bodyPr" }?.let { XmlEx.src(tc, it) } ?: "<a:bodyPr/>"
        val lst = kids.firstOrNull { it.name == "a:lstStyle" }?.let { XmlEx.src(tc, it) } ?: "<a:lstStyle/>"
        val p = kids.firstOrNull { it.name == "a:p" }
        val pPr = p?.let { Xml.child(tc, it, "a:pPr") }?.let { XmlEx.src(tc, it) } ?: ""
        val rPr = p?.let { pp -> Xml.child(tc, pp, "a:endParaRPr") ?: Xml.children(tc, pp, "a:r").firstOrNull()?.let { Xml.child(tc, it, "a:rPr") } }
        val end = if (rPr == null) DEFAULT_END else {
            val attrs = tc.substring(rPr.start + 1 + rPr.name.length, rPr.openEnd).trimEnd('>', '/')
            if (rPr.selfClosing) "<a:endParaRPr$attrs/>" else "<a:endParaRPr$attrs>${tc.substring(rPr.openEnd, rPr.closeStart)}</a:endParaRPr>"
        }
        return "<a:txBody>$bodyPr$lst<a:p>$pPr$end</a:p></a:txBody>"
    }

    /** An empty cell with the properties of [ref] (no merge attributes, no text). */
    private fun blankCell(ref: String): String {
        val e = Xml.root(ref) ?: return BLANK
        var tag = ref.substring(e.start, e.openEnd)
        for (n in listOf("rowSpan", "gridSpan", "hMerge", "vMerge", "id")) tag = XmlEx.tagWithAttr(tag, n, null)
        if (tag.endsWith("/>")) tag = tag.dropLast(2).trimEnd() + ">"
        if (e.selfClosing) return "$tag<a:txBody><a:bodyPr/><a:lstStyle/><a:p>$DEFAULT_END</a:p></a:txBody><a:tcPr/></a:tc>"
        val body = Xml.child(ref, e, "a:txBody")
        val txt = if (body != null) blankBody(ref, body) else "<a:txBody><a:bodyPr/><a:lstStyle/><a:p>$DEFAULT_END</a:p></a:txBody>"
        val tcPr = Xml.child(ref, e, "a:tcPr")?.let { XmlEx.src(ref, it) } ?: ""
        return "$tag$txt$tcPr</a:tc>"
    }

    companion object {
        private val TEXT_RUN = Regex("<a:t(?:\\s[^>]*)?>[^<]+</a:t>")
        private const val DEFAULT_END = "<a:endParaRPr lang=\"en-US\" sz=\"1600\"/>"
        private const val BLANK = "<a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p>$DEFAULT_END</a:p></a:txBody><a:tcPr/></a:tc>"

        /**
         * Reads the `a:tbl` element [tbl] of [xml]. [frameW] = width of the frame holding it (0: keep the widths' sum).
         * @throws IllegalStateException when the table is not a full grid (a row with more or fewer cells than columns).
         */
        fun read(xml: String, tbl: Xml.Elem, frameW: Long): TableEdit {
            val kids = Xml.children(xml, tbl)
            val grid = kids.firstOrNull { it.name == "a:tblGrid" } ?: throw IllegalStateException("table has no grid")
            val gridKids = Xml.children(xml, grid)
            val cols = gridKids.filter { it.name == "a:gridCol" }.map { Col(XmlEx.src(xml, it), Xml.attr(xml, it, "w")?.toLongOrNull() ?: 0L) }.toMutableList()
            val gridTail = gridKids.filter { it.name != "a:gridCol" }.joinToString("") { XmlEx.src(xml, it) } + "</a:tblGrid>"
            val gridOpen = xml.substring(grid.start, grid.openEnd).let { if (it.endsWith("/>")) it.dropLast(2).trimEnd() + ">" else it }
            val before = kids.takeWhile { it !== grid }.joinToString("") { XmlEx.src(xml, it) }
            val trs = kids.filter { it.name == "a:tr" }
            val last = trs.lastOrNull() ?: grid
            val after = kids.dropWhile { it !== last }.drop(1).filter { it.name != "a:tr" }.joinToString("") { XmlEx.src(xml, it) }
            val rows = trs.map { tr ->
                val tk = Xml.children(xml, tr)
                val cells = tk.filter { it.name == "a:tc" }.map { XmlEx.src(xml, it) }.toMutableList()
                if (cells.size != cols.size) throw IllegalStateException("table row has ${cells.size} cells for ${cols.size} columns")
                val open = xml.substring(tr.start, tr.openEnd).let { if (it.endsWith("/>")) it.dropLast(2).trimEnd() + ">" else it }
                Row(open, Xml.attr(xml, tr, "h")?.toLongOrNull() ?: 370840L, cells, tk.filter { it.name != "a:tc" }.joinToString("") { XmlEx.src(xml, it) })
            }.toMutableList()
            if (rows.isEmpty() || cols.isEmpty()) throw IllegalStateException("empty table")
            return TableEdit(xml.substring(tbl.start, tbl.openEnd), before, gridOpen, gridTail, cols, rows, after, frameW).also { it.readOwners() }
        }
    }
}
