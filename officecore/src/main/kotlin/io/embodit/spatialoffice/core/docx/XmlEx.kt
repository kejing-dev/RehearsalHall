package io.embodit.spatialoffice.core.docx

import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import io.embodit.spatialoffice.core.pkg.Rels
import io.embodit.spatialoffice.core.pkg.Xml

/** String-level helpers on top of the [Xml] range scanner, shared by the docx and pptx editors. */
internal object XmlEx {

    const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    /** The source text of [e], start tag to end tag. */
    fun src(xml: String, e: Xml.Elem): String = xml.substring(e.start, e.end)

    /** A start tag for [e] that is never self-closing: `<w:p a="1"/>` → `<w:p a="1">`. */
    fun openTag(xml: String, e: Xml.Elem): String {
        val tag = xml.substring(e.start, e.openEnd)
        if (!e.selfClosing) return tag
        return tag.substring(0, tag.length - 2).trimEnd() + ">"
    }

    /** Removes characters XML 1.0 cannot carry. */
    fun clean(s: String): String {
        var ok = true
        for (c in s) if (c < ' ' && c != '\t' && c != '\n' && c != '\r' || c == '\uFFFE' || c == '\uFFFF') { ok = false; break }
        if (ok) return s
        val sb = StringBuilder(s.length)
        for (c in s) if (!(c < ' ' && c != '\t' && c != '\n' && c != '\r' || c == '\uFFFE' || c == '\uFFFF')) sb.append(c)
        return sb.toString()
    }

    /** Escaped text content (control characters dropped). */
    fun esc(s: String): String = Xml.escape(clean(s))

    /**
     * Returns the start tag text of [e] with attribute [name] set to [value] (added at the end when missing),
     * or removed when [value] is null. Works on the tag text only; the result keeps a self-closing `/`.
     */
    fun withAttr(xml: String, e: Xml.Elem, name: String, value: String?): String {
        val tag = xml.substring(e.start, e.openEnd)
        return tagWithAttr(tag, name, value)
    }

    fun tagWithAttr(tag: String, name: String, value: String?): String {
        val m = Regex("\\s" + Regex.escape(name) + "\\s*=\\s*(\"[^\"]*\"|'[^']*')").find(tag)
        if (m != null) {
            return if (value == null) tag.removeRange(m.range)
            else tag.replaceRange(m.range, " $name=\"${Xml.escape(value)}\"")
        }
        if (value == null) return tag
        val selfClosing = tag.endsWith("/>")
        val head = tag.substring(0, tag.length - (if (selfClosing) 2 else 1)).trimEnd()
        return head + " $name=\"${Xml.escape(value)}\"" + (if (selfClosing) "/>" else ">")
    }

    /** `ppt/presentation.xml` + `ppt/slides/slide3.xml` → `slides/slide3.xml`; climbs with `..` when needed. */
    fun relativize(ownerPart: String, targetPart: String): String {
        val from = ownerPart.trimStart('/').split('/').dropLast(1)
        val to = targetPart.trimStart('/').split('/')
        var i = 0
        while (i < from.size && i < to.size - 1 && from[i] == to[i]) i++
        val sb = StringBuilder()
        repeat(from.size - i) { sb.append("../") }
        sb.append(to.subList(i, to.size).joinToString("/"))
        return sb.toString()
    }

    /** Owner part of a relationships part: `ppt/slides/_rels/slide1.xml.rels` → `ppt/slides/slide1.xml`; `_rels/.rels` → "". */
    fun ownerOfRels(relsPart: String): String? {
        if (!relsPart.endsWith(".rels")) return null
        val i = relsPart.lastIndexOf("_rels/")
        if (i < 0) return null
        return relsPart.substring(0, i) + relsPart.substring(i + 6).removeSuffix(".rels")
    }

    /** Every part that some relationship in the package points at (internal targets only). */
    fun referencedParts(pkg: OoxmlPackage): Set<String> {
        val out = HashSet<String>()
        for (n in pkg.names()) {
            val owner = ownerOfRels(n) ?: continue
            val text = pkg.text(n) ?: continue
            for (r in Rels.parse(text)) {
                if (!r.external) out.add(OoxmlPackage.resolve(owner, r.target))
            }
        }
        return out
    }

    /** Removes the `<Relationship Id="id">` entry from [ownerPart]'s relationships. */
    fun removeRel(pkg: OoxmlPackage, ownerPart: String, id: String) {
        val name = OoxmlPackage.relsOf(ownerPart)
        val xml = pkg.text(name) ?: return
        val root = Xml.root(xml) ?: return
        val hit = Xml.children(xml, root).firstOrNull { Xml.attr(xml, it, "Id") == id } ?: return
        pkg.putText(name, xml.substring(0, hit.start) + xml.substring(hit.end))
    }

    /** Content type of [part] from an `<Override>`, else from the `<Default>` of its extension. */
    fun contentType(pkg: OoxmlPackage, part: String): String? {
        val xml = pkg.text(OoxmlPackage.CONTENT_TYPES) ?: return null
        val root = Xml.root(xml) ?: return null
        val pn = "/" + part.trimStart('/')
        val kids = Xml.children(xml, root)
        kids.firstOrNull { Xml.local(it.name) == "Override" && Xml.attr(xml, it, "PartName") == pn }
            ?.let { return Xml.attr(xml, it, "ContentType") }
        val ext = part.substringAfterLast('.', "")
        return kids.firstOrNull { Xml.local(it.name) == "Default" && Xml.attr(xml, it, "Extension").equals(ext, ignoreCase = true) }
            ?.let { Xml.attr(xml, it, "ContentType") }
    }

    /** Length of the common prefix of two strings. */
    fun commonPrefix(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    /** [elemXml] is one element as text. Returns it without the direct children whose names are in [names]. */
    fun removeChildren(elemXml: String, names: Set<String>): String {
        val root = Xml.root(elemXml) ?: return elemXml
        val edits = Xml.children(elemXml, root).filter { it.name in names }.map { Triple(it.start, it.end, "") }
        return Xml.splice(elemXml, edits)
    }

    /**
     * Inserts [childXml] into the element text [elemXml] at the place the schema sequence [order] asks for:
     * before the first existing child that sorts after [childName]. Children the list does not know sort last.
     */
    fun insertChild(elemXml: String, childXml: String, childName: String, order: List<String>): String {
        val root = Xml.root(elemXml) ?: return elemXml
        if (root.selfClosing) return openTag(elemXml, root) + childXml + "</" + root.name + ">"
        fun rank(n: String): Int { val i = order.indexOf(n); return if (i < 0) Int.MAX_VALUE else i }
        val my = rank(childName)
        val before = Xml.children(elemXml, root).firstOrNull { rank(it.name) > my }
        val pos = before?.start ?: root.closeStart
        return elemXml.substring(0, pos) + childXml + elemXml.substring(pos)
    }

    /** True when the element text has neither child elements nor text. */
    fun isEmpty(elemXml: String): Boolean {
        val root = Xml.root(elemXml) ?: return true
        return root.selfClosing || elemXml.substring(root.openEnd, root.closeStart).isBlank()
    }

    fun childOf(elemXml: String, name: String): Xml.Elem? {
        val root = Xml.root(elemXml) ?: return null
        return Xml.child(elemXml, root, name)
    }
}
