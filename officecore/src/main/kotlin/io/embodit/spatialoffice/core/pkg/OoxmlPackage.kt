package io.embodit.spatialoffice.core.pkg

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * An OPC package (.docx / .xlsx / .pptx = a ZIP of parts). Every part is kept as the bytes that were read.
 * Saving writes all parts back in their original order; a part that was never [put] keeps exactly the bytes
 * it had, so "save only what changed" holds at part level. Editors narrow that further by patching byte
 * ranges inside the one XML part they touch.
 */
class OoxmlPackage private constructor(
    private val order: MutableList<String>,
    private val parts: MutableMap<String, ByteArray>,
) {
    private val dirty = LinkedHashSet<String>()

    /** Part names in package order, without a leading slash (e.g. `word/document.xml`). */
    fun names(): List<String> = order.toList()

    fun has(name: String): Boolean = parts.containsKey(norm(name))

    fun bytes(name: String): ByteArray? = parts[norm(name)]

    fun text(name: String): String? = parts[norm(name)]?.let { String(it, Charsets.UTF_8) }

    /** Adds or replaces a part and marks it changed. */
    fun put(name: String, data: ByteArray) {
        val n = norm(name)
        if (!parts.containsKey(n)) order.add(n)
        parts[n] = data
        dirty.add(n)
    }

    fun putText(name: String, xml: String) = put(name, xml.toByteArray(Charsets.UTF_8))

    fun remove(name: String) {
        val n = norm(name)
        if (parts.remove(n) != null) { order.remove(n); dirty.add(n) }
    }

    /** Names of parts added, replaced or removed since the package was read. */
    val dirtyParts: Set<String> get() = dirty

    fun write(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // [Content_Types].xml is conventionally the first entry; keep it there when new parts were appended.
            val names = order.sortedBy { if (it == CONTENT_TYPES) 0 else 1 }
            for (n in names) {
                val e = ZipEntry(n)
                e.time = FIXED_TIME
                zip.putNextEntry(e)
                zip.write(parts.getValue(n))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    companion object {
        const val CONTENT_TYPES = "[Content_Types].xml"
        private const val FIXED_TIME = 315_561_600_000L // 1980-01-02, keeps output deterministic

        fun read(bytes: ByteArray): OoxmlPackage {
            val order = ArrayList<String>()
            val parts = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (!e.isDirectory) {
                        val n = norm(e.name)
                        if (!parts.containsKey(n)) order.add(n)
                        parts[n] = zip.readBytes()
                    }
                    zip.closeEntry()
                }
            }
            if (!parts.containsKey(CONTENT_TYPES)) throw IllegalArgumentException("not an OOXML package: no $CONTENT_TYPES")
            return OoxmlPackage(order, parts)
        }

        fun empty(): OoxmlPackage = OoxmlPackage(ArrayList(), LinkedHashMap())

        private fun norm(name: String): String = name.trimStart('/')

        /** Resolves a relationship target against the part that owns the .rels file. `ppt/slides/slide1.xml` + `../media/a.png` → `ppt/media/a.png`. */
        fun resolve(ownerPart: String, target: String): String {
            if (target.startsWith("/")) return target.trimStart('/')
            val stack = ArrayList(norm(ownerPart).split('/').dropLast(1))
            for (seg in target.split('/')) {
                when (seg) {
                    "", "." -> {}
                    ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    else -> stack.add(seg)
                }
            }
            return stack.joinToString("/")
        }

        /** `word/document.xml` → `word/_rels/document.xml.rels`. */
        fun relsOf(part: String): String {
            val n = norm(part)
            val i = n.lastIndexOf('/')
            return if (i < 0) "_rels/$n.rels" else n.substring(0, i) + "/_rels/" + n.substring(i + 1) + ".rels"
        }
    }
}

/** One `<Relationship>` of a .rels part. */
data class Rel(val id: String, val type: String, val target: String, val external: Boolean)

object Rels {
    fun parse(xml: String): List<Rel> {
        val root = Xml.root(xml) ?: return emptyList()
        return Xml.children(xml, root).filter { Xml.local(it.name) == "Relationship" }.map {
            Rel(
                id = Xml.attr(xml, it, "Id") ?: "",
                type = Xml.attr(xml, it, "Type") ?: "",
                target = Xml.attr(xml, it, "Target") ?: "",
                external = Xml.attr(xml, it, "TargetMode") == "External",
            )
        }
    }

    /** Relationships of [part], empty when it has none. */
    fun of(pkg: OoxmlPackage, part: String): List<Rel> = pkg.text(OoxmlPackage.relsOf(part))?.let { parse(it) } ?: emptyList()

    /** Appends a relationship to [part]'s .rels (creating it) and returns the new id. */
    fun add(pkg: OoxmlPackage, part: String, type: String, target: String, external: Boolean = false): String {
        val relsName = OoxmlPackage.relsOf(part)
        val xml = pkg.text(relsName)
            ?: "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"></Relationships>"
        val used = parse(xml).map { it.id }.toSet()
        var n = used.size + 1
        while ("rId$n" in used) n++
        val id = "rId$n"
        val tag = "<Relationship Id=\"$id\" Type=\"${Xml.escape(type)}\" Target=\"${Xml.escape(target)}\"" + (if (external) " TargetMode=\"External\"" else "") + "/>"
        pkg.putText(relsName, Xml.appendChild(xml, tag))
        return id
    }
}

object ContentTypes {
    /** Adds an `<Override>` for [part] unless one exists. */
    fun addOverride(pkg: OoxmlPackage, part: String, contentType: String) {
        val xml = pkg.text(OoxmlPackage.CONTENT_TYPES) ?: return
        val pn = "/" + part.trimStart('/')
        if (xml.contains("PartName=\"$pn\"")) return
        pkg.putText(OoxmlPackage.CONTENT_TYPES, Xml.appendChild(xml, "<Override PartName=\"$pn\" ContentType=\"$contentType\"/>"))
    }

    /** Adds a `<Default>` for [extension] unless one exists. */
    fun addDefault(pkg: OoxmlPackage, extension: String, contentType: String) {
        val xml = pkg.text(OoxmlPackage.CONTENT_TYPES) ?: return
        if (Regex("Extension=\"${Regex.escape(extension)}\"", RegexOption.IGNORE_CASE).containsMatchIn(xml)) return
        val root = Xml.root(xml) ?: return
        val tag = "<Default Extension=\"$extension\" ContentType=\"$contentType\"/>"
        pkg.putText(OoxmlPackage.CONTENT_TYPES, xml.substring(0, root.openEnd) + tag + xml.substring(root.openEnd))
    }

    fun removeOverride(pkg: OoxmlPackage, part: String) {
        val xml = pkg.text(OoxmlPackage.CONTENT_TYPES) ?: return
        val pn = "/" + part.trimStart('/')
        val root = Xml.root(xml) ?: return
        val hit = Xml.children(xml, root).firstOrNull { Xml.attr(xml, it, "PartName") == pn } ?: return
        pkg.putText(OoxmlPackage.CONTENT_TYPES, xml.substring(0, hit.start) + xml.substring(hit.end))
    }
}
