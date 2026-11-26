package io.embodit.spatialoffice.core.docx

import io.embodit.spatialoffice.core.pkg.Xml

/** Read-only view of `styles.xml`: enough to map paragraph styles to [ParaStyle] and to read character-style b / i / u. */
internal class DocxStyles(xml: String?) {

    class Style(
        val id: String, val type: String, val name: String, val basedOn: String?, val isDefault: Boolean,
        val outlineLvl: Int?, val numId: Int?, val ilvl: Int?, val rPr: String,
    )

    val byId = LinkedHashMap<String, Style>()
    var defaultParaId: String? = null
        private set
    /** `w:docDefaults/w:rPrDefault/w:rPr/w:sz` in half-points, if the file says. */
    var defaultSizeHp: Int? = null
        private set

    init {
        val root = xml?.let { Xml.root(it) }
        if (xml != null && root != null) {
            for (c in Xml.children(xml, root)) {
                when (c.name) {
                    "w:style" -> {
                        val id = Xml.attr(xml, c, "w:styleId") ?: continue
                        val kids = Xml.children(xml, c)
                        fun v(n: String) = kids.firstOrNull { it.name == n }?.let { Xml.attr(xml, it, "w:val") }
                        val pPr = kids.firstOrNull { it.name == "w:pPr" }
                        val numPr = pPr?.let { Xml.child(xml, it, "w:numPr") }
                        val st = Style(
                            id = id,
                            type = Xml.attr(xml, c, "w:type") ?: "paragraph",
                            name = v("w:name") ?: id,
                            basedOn = v("w:basedOn"),
                            isDefault = Xml.attr(xml, c, "w:default").let { it == "1" || it == "true" },
                            outlineLvl = pPr?.let { Xml.child(xml, it, "w:outlineLvl") }?.let { Xml.attr(xml, it, "w:val")?.toIntOrNull() },
                            numId = numPr?.let { Xml.child(xml, it, "w:numId") }?.let { Xml.attr(xml, it, "w:val")?.toIntOrNull() },
                            ilvl = numPr?.let { Xml.child(xml, it, "w:ilvl") }?.let { Xml.attr(xml, it, "w:val")?.toIntOrNull() },
                            rPr = kids.firstOrNull { it.name == "w:rPr" }?.let { xml.substring(it.start, it.end) } ?: "",
                        )
                        byId[id] = st
                        if (st.isDefault && st.type == "paragraph" && defaultParaId == null) defaultParaId = id
                    }
                    "w:docDefaults" -> {
                        defaultSizeHp = Xml.child(xml, c, "w:rPrDefault")?.let { Xml.child(xml, it, "w:rPr") }
                            ?.let { Xml.child(xml, it, "w:sz") }?.let { Xml.attr(xml, it, "w:val")?.toIntOrNull() }
                    }
                }
            }
        }
    }

    /** The style and the styles it is based on, nearest first. */
    fun chain(id: String?): List<Style> {
        val out = ArrayList<Style>()
        var cur = id?.let { byId[it] }
        while (cur != null && out.size < 20 && cur !in out) { out.add(cur); cur = cur.basedOn?.let { byId[it] } }
        return out
    }

    /** TITLE / H1 / H2 / H3 when the paragraph style (or one it is based on) is a title or heading style. */
    fun heading(id: String?): ParaStyle? {
        for (s in chain(id ?: defaultParaId)) {
            headingByName(s.name)?.let { return it }
            s.outlineLvl?.let { if (it in 0..8) return levelStyle(it) }
        }
        return null
    }

    /** numId / ilvl a paragraph style brings along, if any. */
    fun num(id: String?): Pair<Int, Int>? {
        val ch = chain(id ?: defaultParaId)
        val numId = ch.firstNotNullOfOrNull { it.numId } ?: return null
        return numId to (ch.firstNotNullOfOrNull { it.ilvl } ?: 0)
    }

    /** BULLET / NUMBERED guessed from a built-in list style name, for files whose numbering cannot be resolved. */
    fun listHint(id: String?): ParaStyle? {
        for (s in chain(id)) {
            val n = s.name.lowercase()
            if (n.startsWith("list bullet")) return ParaStyle.BULLET
            if (n.startsWith("list number")) return ParaStyle.NUMBERED
        }
        return null
    }

    /** M2: size in half-points of a run without its own `w:sz` in a paragraph of style [paraStyleId]: style chain, document defaults, else 20. */
    fun sizeHp(paraStyleId: String?): Int {
        for (s in chain(paraStyleId ?: defaultParaId)) {
            if (s.rPr.isEmpty()) continue
            XmlEx.childOf(s.rPr, "w:sz")?.let { Xml.attr(s.rPr, it, "w:val")?.toIntOrNull() }?.let { return it }
        }
        return defaultSizeHp ?: 20
    }

    /** True when the style on its own (no direct formatting) is plain body text. */
    fun isPlain(id: String?): Boolean = heading(id) == null && num(id).let { it == null || it.first == 0 } && listHint(id) == null

    fun idForName(name: String): String? =
        byId.values.firstOrNull { it.type == "paragraph" && it.name.equals(name, ignoreCase = true) }?.id

    fun idFor(target: ParaStyle): String? = when (target) {
        ParaStyle.TITLE -> idForName("Title")
        ParaStyle.H1 -> idForName("heading 1")
        ParaStyle.H2 -> idForName("heading 2")
        ParaStyle.H3 -> idForName("heading 3")
        else -> null
    }

    /**
     * Value a character style (or one it is based on) gives a toggle property ("w:b", "w:i", "w:u"); null when
     * the run has no character style or the style says nothing about it.
     */
    fun charToggle(prop: String, rStyle: String?): Boolean? {
        for (s in chain(rStyle)) toggle(s.rPr, prop)?.let { return it }
        return null
    }

    companion object {
        fun levelStyle(outlineLvl: Int): ParaStyle = when (outlineLvl) { 0 -> ParaStyle.H1; 1 -> ParaStyle.H2; else -> ParaStyle.H3 }

        fun headingByName(name: String): ParaStyle? {
            val n = name.trim().lowercase()
            if (n == "title" || n == "标题") return ParaStyle.TITLE
            val m = Regex("^(?:heading|标题)\\s*(\\d)$").find(n) ?: return null
            return levelStyle(m.groupValues[1].toInt() - 1)
        }

        /** Value of a toggle element directly inside an `<w:rPr>` text: true / false, null when absent. */
        fun toggle(rPr: String, prop: String): Boolean? {
            if (rPr.isEmpty()) return null
            val e = XmlEx.childOf(rPr, prop) ?: return null
            val v = Xml.attr(rPr, e, "w:val")
            return if (prop == "w:u") v != "none" else !(v == "0" || v == "false" || v == "off")
        }

        fun rStyle(rPr: String): String? {
            if (rPr.isEmpty()) return null
            val e = XmlEx.childOf(rPr, "w:rStyle") ?: return null
            return Xml.attr(rPr, e, "w:val")
        }
    }
}

/** Read-only view of `numbering.xml`: is a given list level a bullet? */
internal class DocxNumbering(xml: String?) {
    private val numToAbstract = HashMap<Int, Int>()
    private val fmt = HashMap<Int, HashMap<Int, String>>()

    init {
        val root = xml?.let { Xml.root(it) }
        if (xml != null && root != null) {
            for (c in Xml.children(xml, root)) {
                when (c.name) {
                    "w:abstractNum" -> {
                        val id = Xml.attr(xml, c, "w:abstractNumId")?.toIntOrNull() ?: continue
                        val levels = HashMap<Int, String>()
                        for (l in Xml.children(xml, c, "w:lvl")) {
                            val il = Xml.attr(xml, l, "w:ilvl")?.toIntOrNull() ?: continue
                            Xml.child(xml, l, "w:numFmt")?.let { f -> Xml.attr(xml, f, "w:val")?.let { levels[il] = it } }
                        }
                        fmt[id] = levels
                    }
                    "w:num" -> {
                        val id = Xml.attr(xml, c, "w:numId")?.toIntOrNull() ?: continue
                        Xml.child(xml, c, "w:abstractNumId")?.let { a -> Xml.attr(xml, a, "w:val")?.toIntOrNull()?.let { numToAbstract[id] = it } }
                    }
                }
            }
        }
    }

    /** true = bullet, false = some numbered format, null = unknown. */
    fun isBullet(numId: Int, ilvl: Int): Boolean? {
        val levels = fmt[numToAbstract[numId] ?: return null] ?: return null
        val f = levels[ilvl] ?: levels[0] ?: return null
        return f == "bullet"
    }
}
