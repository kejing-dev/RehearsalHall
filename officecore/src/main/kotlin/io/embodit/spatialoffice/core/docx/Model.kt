package io.embodit.spatialoffice.core.docx

import io.embodit.spatialoffice.core.review.RevisionKind
import io.embodit.spatialoffice.core.text.RunRev

enum class ParaStyle { BODY, TITLE, H1, H2, H3, BULLET, NUMBERED }

/**
 * One stretch of text with uniform bold / italic / underline. The three flags are the run's own formatting (set
 * on the run or by its character style); a heading that is bold because of its paragraph style has `bold = false`.
 * [src] = index of the original run (in this paragraph as opened) whose remaining formatting (font, size, colour…)
 * this run keeps; -1 = take it from the paragraph's first original run, or none.
 * Inside [text]: `\t` is a tab, `\n` a line break inside the paragraph, `\u000C` a page break.
 */
data class Run(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val src: Int = -1,
    val strike: Boolean = false,
    /** M2: the run's own `w:color` as 0xRRGGBB; null = automatic / absent. */
    val color: Int? = null,
    /** M2: the run's own `w:highlight` name ("yellow", "green", "cyan" …); null = none. */
    val highlight: String? = null,
    /** M2: the run's own `w:sz` in half-points; null = the paragraph's ([Paragraph.baseSizeHp]). */
    val size: Int? = null,
    /** M3 (d20): URL of the external hyperlink the run sits in (`w:hyperlink r:id`); null = not a link. */
    val link: String? = null,
    /** M3 (d22): the run's own `w:vertAlign`: "superscript" | "subscript"; null = baseline. */
    val vertAlign: String? = null,
    /**
     * M5 (d30 / d36): the tracked change the run is part of: INSERT (`w:ins`, drawn underlined) or DELETE (`w:del`,
     * drawn struck through). The text of a DELETE run is still part of [Paragraph.text]. null = ordinary text.
     */
    val rev: RunRev? = null,
)

sealed class DocBlock { abstract val id: Int }

/**
 * [readOnlyWhy] is null when [editable], otherwise "tracked-changes" | "field" | "complex".
 * M5: insertions and deletions of text are part of the model ([Run.rev]) and leave the paragraph editable;
 * "tracked-changes" is now only a paragraph with a tracked move, format change or paragraph mark.
 */
data class Paragraph(
    override val id: Int,
    val style: ParaStyle,
    val runs: List<Run>,
    val editable: Boolean,
    val readOnlyWhy: String?,
    /** 0 start, 1 centre, 2 end, 3 justified. */
    val align: Int = 0,
    /** M2: list level (`w:ilvl`, direct or from the style), 0–8. */
    val level: Int = 0,
    /** M2: direct left indent (`w:ind w:left` / `w:start`) in twips; 0 when absent. */
    val indentTw: Int = 0,
    /** M2: direct line spacing (`w:spacing w:line`, auto rule) in 240ths of a line; null = not set here. */
    val lineSpacing: Int? = null,
    /** M2: direct space before / after the paragraph (`w:spacing w:before` / `w:after`) in twips; null = not set here. */
    val spaceBefore: Int? = null,
    val spaceAfter: Int? = null,
    /** M2: effective size in half-points of a run without its own `w:sz` (paragraph style chain, then document defaults, else 20). */
    val baseSizeHp: Int = 22,
    /** M3 (d21): the paragraph has a bottom border (`w:pBdr/w:bottom`): drawn as a horizontal line under it. */
    val rule: Boolean = false,
) : DocBlock() {
    /** Everything on the paper, in order — including text of tracked deletions ([Run.rev] DELETE). Offsets count in this. */
    val text: String get() = runs.joinToString("") { it.text }
    /** M5: what the document says: [text] without the tracked deletions. */
    val cleanText: String get() = if (runs.none { it.rev?.kind == RevisionKind.DELETE }) text else runs.filter { it.rev?.kind != RevisionKind.DELETE }.joinToString("") { it.text }
    /** M3 (d21): a paragraph that holds nothing but page breaks. */
    val pageBreak: Boolean get() = runs.isNotEmpty() && text.all { it == '\u000C' }
}

/** M3 (d42): a merged table cell: its top-left grid position and how many rows / grid columns it covers. */
data class CellMerge(val row: Int, val col: Int, val rows: Int, val cols: Int) {
    fun covers(r: Int, c: Int): Boolean = r in row until row + rows && c in col until col + cols
}

/** Cell text, row by row. A horizontally merged cell is followed by empty strings so every row lines up with the grid. */
data class TableBlock(
    override val id: Int, val rows: List<List<String>>,
    /** Width as a share of the text width, 10–100 (v0.2.5: tables can be made narrower or wider). */
    val widthPct: Int = 100,
    /** M3 (d42): merged cells (`w:gridSpan`, `w:vMerge`), anchors only; covered cells are "" in [rows] (or their own text for vertical ones). */
    val merges: List<CellMerge> = emptyList(),
) : DocBlock() {
    /** True for a cell hidden under a merge (inside one but not its top-left). */
    fun covered(r: Int, c: Int): Boolean = merges.any { it.covers(r, c) && !(it.row == r && it.col == c) }
    fun mergeAt(r: Int, c: Int): CellMerge? = merges.firstOrNull { it.row == r && it.col == c }
}

data class ImageBlock(override val id: Int, val part: String, val widthEmu: Long, val heightEmu: Long) : DocBlock()

/** [kind]: "smart-graphic" | "chart" | "text-box" | "section" | "other". */
data class OpaqueBlock(override val id: Int, val kind: String) : DocBlock()

enum class CompatKind { APPROX, READ_ONLY }

/** code examples: "columns", "text-box", "font:<name>", "smart-graphic", "tracked-changes", "comments", "chart" */
data class CompatNote(val kind: CompatKind, val code: String, val count: Int)
