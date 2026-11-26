package io.embodit.spatialoffice.core.pkg

/**
 * A small range scanner over well-formed XML text. It never builds a tree and never re-serialises: callers get
 * character ranges ([Elem]) into the original string and splice replacements into those ranges, so everything
 * outside an edited element stays byte-for-byte what the file had.
 */
object Xml {

    /**
     * One element in the source text.
     * [start] = index of `<`; [openEnd] = index just after the `>` of the start tag;
     * [closeStart] = index of `</` of the end tag (= [openEnd] for a self-closing element);
     * [end] = index just after the element. Inner content is `xml.substring(openEnd, closeStart)`.
     */
    data class Elem(val name: String, val start: Int, val openEnd: Int, val closeStart: Int, val end: Int, val selfClosing: Boolean)

    /** `w:p` → `p`. */
    fun local(name: String): String = name.substringAfter(':')

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    fun unescape(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i)
                if (semi > i && semi - i <= 10) {
                    val ent = s.substring(i + 1, semi)
                    val rep: String? = when {
                        ent == "amp" -> "&"
                        ent == "lt" -> "<"
                        ent == "gt" -> ">"
                        ent == "quot" -> "\""
                        ent == "apos" -> "'"
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                        else -> null
                    }
                    if (rep != null) { sb.append(rep); i = semi + 1; continue }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    /** The document element, or null when [xml] has none. */
    fun root(xml: String): Elem? = nextElem(xml, 0, xml.length)

    /** Direct child elements of [parent], in order. */
    fun children(xml: String, parent: Elem): List<Elem> {
        if (parent.selfClosing) return emptyList()
        val out = ArrayList<Elem>()
        var i = parent.openEnd
        while (true) {
            val e = nextElem(xml, i, parent.closeStart) ?: break
            out.add(e); i = e.end
        }
        return out
    }

    /** Direct children with the given qualified name (e.g. `w:p`). */
    fun children(xml: String, parent: Elem, name: String): List<Elem> = children(xml, parent).filter { it.name == name }

    fun child(xml: String, parent: Elem, name: String): Elem? = children(xml, parent).firstOrNull { it.name == name }

    /** First descendant (depth-first, document order) with the given qualified name. */
    fun find(xml: String, within: Elem, name: String): Elem? {
        for (c in children(xml, within)) {
            if (c.name == name) return c
            find(xml, c, name)?.let { return it }
        }
        return null
    }

    /** All descendants with the given qualified name, document order. Does not descend into matches. */
    fun findAll(xml: String, within: Elem, name: String): List<Elem> {
        val out = ArrayList<Elem>()
        fun walk(e: Elem) { for (c in children(xml, e)) { if (c.name == name) out.add(c) else walk(c) } }
        walk(within)
        return out
    }

    /** Value of attribute [name] (qualified, e.g. `r:id`) on the start tag of [e], unescaped. */
    fun attr(xml: String, e: Elem, name: String): String? = attrs(xml, e)[name]

    fun attrs(xml: String, e: Elem): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = e.start + 1 + e.name.length
        val stop = e.openEnd - 1
        while (i < stop) {
            while (i < stop && (xml[i].isWhitespace() || xml[i] == '/')) i++
            if (i >= stop) break
            val eq = xml.indexOf('=', i)
            if (eq < 0 || eq >= stop) break
            val key = xml.substring(i, eq).trim()
            var q = eq + 1
            while (q < stop && xml[q].isWhitespace()) q++
            if (q >= stop) break
            val quote = xml[q]
            if (quote != '"' && quote != '\'') break
            val close = xml.indexOf(quote, q + 1)
            if (close < 0) break
            out[key] = unescape(xml.substring(q + 1, close))
            i = close + 1
        }
        return out
    }

    /** Text content of [e] with markup removed and entities resolved. */
    fun text(xml: String, e: Elem): String {
        if (e.selfClosing) return ""
        val s = xml.substring(e.openEnd, e.closeStart)
        if (s.indexOf('<') < 0) return unescape(s)
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val lt = s.indexOf('<', i)
            if (lt < 0) { sb.append(s, i, s.length); break }
            sb.append(s, i, lt)
            i = if (s.startsWith("<![CDATA[", lt)) {
                val c = s.indexOf("]]>", lt)
                if (c < 0) s.length else { sb.append(escape(s.substring(lt + 9, c))); c + 3 }
            } else {
                val gt = tagEnd(s, lt)
                if (gt < 0) s.length else gt + 1
            }
        }
        return unescape(sb.toString())
    }

    /** Inserts [childXml] as the last child of the document element (expanding a self-closing root). */
    fun appendChild(xml: String, childXml: String): String {
        val r = root(xml) ?: return xml
        return appendInto(xml, r, childXml)
    }

    /** Inserts [childXml] as the last child of [e]. */
    fun appendInto(xml: String, e: Elem, childXml: String): String =
        if (e.selfClosing) {
            val open = xml.substring(e.start, e.end).trimEnd().removeSuffix(">").trimEnd().removeSuffix("/").trimEnd()
            xml.substring(0, e.start) + open + ">" + childXml + "</" + e.name + ">" + xml.substring(e.end)
        } else {
            xml.substring(0, e.closeStart) + childXml + xml.substring(e.closeStart)
        }

    /** Replaces the whole element [e] with [replacement]. */
    fun replace(xml: String, e: Elem, replacement: String): String = xml.substring(0, e.start) + replacement + xml.substring(e.end)

    /**
     * Applies several range edits to one string in a single pass. [edits] are (start, endExclusive, replacement)
     * in original coordinates and must not overlap. An insertion is an edit with start == end.
     */
    fun splice(xml: String, edits: List<Triple<Int, Int, String>>): String {
        if (edits.isEmpty()) return xml
        val sorted = edits.sortedWith(compareBy({ it.first }, { it.second }))
        val sb = StringBuilder(xml.length + 256)
        var pos = 0
        for ((s, e, rep) in sorted) {
            require(s >= pos && e >= s) { "overlapping edits at $s" }
            sb.append(xml, pos, s).append(rep)
            pos = e
        }
        sb.append(xml, pos, xml.length)
        return sb.toString()
    }

    // ---- scanning ----

    /** Next element starting at or after [from] and before [limit], skipping text, comments, PIs, CDATA and DOCTYPE. */
    fun nextElem(xml: String, from: Int, limit: Int): Elem? {
        var i = from
        while (i < limit) {
            val lt = xml.indexOf('<', i)
            if (lt < 0 || lt >= limit) return null
            when {
                xml.startsWith("<!--", lt) -> { val c = xml.indexOf("-->", lt + 4); if (c < 0) return null; i = c + 3 }
                xml.startsWith("<![CDATA[", lt) -> { val c = xml.indexOf("]]>", lt + 9); if (c < 0) return null; i = c + 3 }
                xml.startsWith("<?", lt) -> { val c = xml.indexOf("?>", lt + 2); if (c < 0) return null; i = c + 2 }
                xml.startsWith("<!", lt) -> { val c = xml.indexOf('>', lt + 2); if (c < 0) return null; i = c + 1 }
                xml.startsWith("</", lt) -> return null // end tag of the enclosing element
                else -> return elemAt(xml, lt)
            }
        }
        return null
    }

    /** Parses the element whose `<` is at [lt]. */
    fun elemAt(xml: String, lt: Int): Elem? {
        val gt = tagEnd(xml, lt)
        if (gt < 0) return null
        var n = lt + 1
        while (n < gt && !xml[n].isWhitespace() && xml[n] != '/' && xml[n] != '>') n++
        val name = xml.substring(lt + 1, n)
        if (xml[gt - 1] == '/') return Elem(name, lt, gt + 1, gt + 1, gt + 1, true)
        // find the matching end tag, counting nested elements of the same name
        var depth = 1
        var i = gt + 1
        while (true) {
            val nx = xml.indexOf('<', i)
            if (nx < 0) return null
            when {
                xml.startsWith("<!--", nx) -> { val c = xml.indexOf("-->", nx + 4); if (c < 0) return null; i = c + 3 }
                xml.startsWith("<![CDATA[", nx) -> { val c = xml.indexOf("]]>", nx + 9); if (c < 0) return null; i = c + 3 }
                xml.startsWith("<?", nx) -> { val c = xml.indexOf("?>", nx + 2); if (c < 0) return null; i = c + 2 }
                xml.startsWith("</", nx) -> {
                    val c = xml.indexOf('>', nx)
                    if (c < 0) return null
                    if (xml.regionMatches(nx + 2, name, 0, name.length) && isNameEnd(xml, nx + 2 + name.length)) {
                        depth--
                        if (depth == 0) return Elem(name, lt, gt + 1, nx, c + 1, false)
                    }
                    i = c + 1
                }
                else -> {
                    val c = tagEnd(xml, nx)
                    if (c < 0) return null
                    if (xml[c - 1] != '/' && xml.regionMatches(nx + 1, name, 0, name.length) && isNameEnd(xml, nx + 1 + name.length)) depth++
                    i = c + 1
                }
            }
        }
    }

    private fun isNameEnd(xml: String, i: Int): Boolean = i >= xml.length || xml[i].isWhitespace() || xml[i] == '>' || xml[i] == '/'

    /** Index of the `>` closing the tag that starts at [lt], honouring quoted attribute values. */
    private fun tagEnd(xml: String, lt: Int): Int {
        var i = lt + 1
        var quote = 0.toChar()
        while (i < xml.length) {
            val c = xml[i]
            if (quote != 0.toChar()) { if (c == quote) quote = 0.toChar() }
            else if (c == '"' || c == '\'') quote = c
            else if (c == '>') return i
            i++
        }
        return -1
    }
}
