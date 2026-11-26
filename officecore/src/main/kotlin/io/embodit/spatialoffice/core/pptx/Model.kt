package io.embodit.spatialoffice.core.pptx

import io.embodit.spatialoffice.core.text.Span

enum class ShapeKind { TITLE, BODY, TEXT, PICTURE, CHART, OTHER }

/** M5 p32: which of a slide's footer-row placeholders a shape is (`p:ph type` = `sldNum` / `ftr` / `dt`). */
enum class FooterKind { NUMBER, FOOTER, DATE }

/**
 * M5 p32: what a slide shows in its footer row: [number] = it has a slide-number placeholder, [footer] = it has a
 * footer placeholder, [text] = the footer placeholder's text ("" without one).
 */
data class SlideFooter(val number: Boolean, val footer: Boolean, val text: String)

/**
 * One paragraph of a shape. A `\n` inside [text] is a line break within the paragraph.
 * M2 (v0.4.0): read from a file, [runs] holds one [Span] per text run with its effective bold / italic / underline /
 * strike / size (half-points) / colour, and [Span.src] = the index of that `a:r` among all `a:r` of the shape's text
 * body as stored when it was read; [align] is the effective alignment (0 left, 1 centre, 2 right). Handed to
 * [PptxDeck.setShapeText], `runs == null` / `align == null` mean "as the text / as it was".
 */
data class TextPara(val text: String, val level: Int = 0, val bullet: Boolean = false, val runs: List<Span>? = null, val align: Int? = null) {
    /** The paragraph without run formatting and alignment (text, level, bullet), for comparisons that only care about those. */
    fun plain(): TextPara = TextPara(text, level, bullet)
}

/**
 * One top-level shape of a slide. x / y / w / h in EMU, resolved through layout and master placeholders when the
 * slide omits `a:xfrm`. [fontPt], [bold], [colorRgb] and [align] describe the first text run / paragraph.
 * [align]: 0 left, 1 centre, 2 right. [colorRgb] is 0xRRGGBB.
 */
data class Shape(
    val id: Int, val kind: ShapeKind, val x: Long, val y: Long, val w: Long, val h: Long,
    val paras: List<TextPara>, val fontPt: Float?, val bold: Boolean, val colorRgb: Int?,
    val align: Int, val imagePart: String?, val editable: Boolean,
    /** Preset geometry of a drawn shape ("rect", "ellipse", "roundRect", "triangle", "rightArrow" …); null for none / text boxes. */
    val geom: String? = null,
    /** Solid fill as 0xRRGGBB; null when the shape has no plain fill. */
    val fillRgb: Int? = null,
    /** Cell text of a table (a graphic frame holding `a:tbl`), row by row; null for everything else. */
    val table: List<List<String>>? = null,
    /** A plain table (no merged cells): its cells can be typed into ([editable] stays false: a table has no single text). */
    val cellsEditable: Boolean = false,
    /** Outline colour 0xRRGGBB from `a:ln`'s own solid fill (null = none or not a plain colour). */
    val lineRgb: Int? = null,
    /** Outline width in EMU from `a:ln w` (null = not set). */
    val lineWidthEmu: Long? = null,
    /** Rotation in degrees, 0..359, from the shape's own `a:xfrm rot`. */
    val rotation: Int = 0,
    /** M3 p40: merge attributes of every grid cell of a table, row by row (same shape as [table]); null for non-tables. */
    val tableCells: List<List<TableCell>>? = null,
    /** M3 p40: the table's column widths (`a:gridCol w`) and row heights (`a:tr h`) in EMU. */
    val tableColW: List<Long>? = null,
    val tableRowH: List<Long>? = null,
    /** v0.6.0 (M4 p29): a group's objects (back to front) with their boxes in slide coordinates; null for anything else. */
    val children: List<Shape>? = null,
    /**
     * M5 p32: set on the slide-number, footer and date placeholders of a slide (their [kind] stays [ShapeKind.TEXT], so
     * they are never a slide's title or body). They are placed by the layout: draw them, do not offer them for dragging.
     * A slide-number field anywhere in [paras] already reads as the slide's number.
     */
    val footerKind: FooterKind? = null,
)

/**
 * M3 p40: one grid position of a slide table as stored. The cell that starts a merged area carries [gridSpan] /
 * [rowSpan] (> 1); the cells it covers carry [hMerge] (same row, further right) and / or [vMerge] (rows below).
 */
data class TableCell(val gridSpan: Int = 1, val rowSpan: Int = 1, val hMerge: Boolean = false, val vMerge: Boolean = false) {
    val covered: Boolean get() = hMerge || vMerge
}

/**
 * [backgroundRgb]: the plain background colour in effect (the slide's own, else its layout's, else its master's).
 * M3 p26: [backgroundImage] = the picture part of a picture background in effect (same chain), [ownBackground] = the
 * slide sets its own `p:bg`.
 */
data class Slide(val index: Int, val shapes: List<Shape>, val notes: String, val backgroundRgb: Int?, val backgroundImage: String? = null, val ownBackground: Boolean = false)

enum class SlideLayoutKind { TITLE, TITLE_BODY, TWO_COLUMN, BLANK }

/** Drawing-order moves of [PptxDeck.reorderShape]. FRONT / BACK: to the top / bottom; FORWARD / BACKWARD: one step. */
enum class ZOrder { FRONT, BACK, FORWARD, BACKWARD }

/**
 * A copied top-level slide object ([PptxDeck.copyShape]); opaque to callers. It holds the object's XML as text (a
 * placeholder already turned into a plain shape with a box of its own), the namespace bindings it was written with,
 * and the relationships its `r:` attributes use, so it stays valid after the source object is edited or deleted, after
 * an undo (a reopened deck) and across saves. Pasting needs the media parts it names to exist in the target deck.
 * [box] = x, y, w, h in EMU.
 */
class ShapeClip internal constructor(
    internal val xml: String,
    internal val sourcePart: String,
    internal val nsDecls: Map<String, String>,
    internal val rels: Map<String, ClipRel>,
    val box: LongArray,
)

/** A relationship a copied object uses: internal targets are absolute part names, external ones as written. */
internal class ClipRel(val type: String, val target: String, val external: Boolean)
