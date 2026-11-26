package io.embodit.spatialoffice.core.docx

import io.embodit.spatialoffice.core.review.RevisionKind
import io.embodit.spatialoffice.core.text.RunRev
import io.embodit.spatialoffice.core.text.Span

/** The same stretch of text in the editor's model. Every field is carried over, [Run.rev] included. */
fun Run.toSpan(): Span = Span(text, bold, italic, underline, src, strike, color, highlight, size, link, vertAlign, rev)

/** The same stretch of text in the document's model. */
fun Span.toRun(): Run = Run(text, bold, italic, underline, src, strike, color, highlight, size, link, vertAlign, rev)

/**
 * M5 (d36): recording my own changes. Nothing here touches a file: [merge] takes a paragraph before an edit and what
 * the ordinary editor made of it, and gives back the runs to store, with the removed text kept as a tracked deletion
 * and the new text marked as a tracked insertion.
 */
object Tracking {

    /**
     * [runs] = what to pass to [DocxDocument.setParagraph]. [caret] = where the caret belongs in the text of [runs]:
     * after the inserted text, or — when the edit only removed text — in front of what is now struck through (as after
     * Backspace). [deletedEnd] = just after that struck-through stretch (where the caret belongs after forward Delete).
     */
    data class Result(val runs: List<Run>, val caret: Int, val deletedEnd: Int)

    /**
     * The paragraph to store when [author] changed [old] into [new] with change tracking on.
     *
     * The text both share at the start and at the end is taken from [new] (so a format-only change is applied as it
     * is, untracked). Of the stretch in between, what [old] had stays in the paragraph as a DELETE run by [author] —
     * unless it was [author]'s own tracked insertion, which is simply removed, or was already a tracked deletion,
     * which stays as it is — and what [new] has becomes an INSERT run by [author]. Text typed next to [author]'s own
     * insertion joins it (same mark, same date) instead of starting a new one.
     */
    fun merge(old: List<Run>, new: List<Run>, author: String, dateIso: String): List<Run> = mergeAt(old, new, author, dateIso).runs

    /** [merge] plus where the caret goes. */
    fun mergeAt(old: List<Run>, new: List<Run>, author: String, dateIso: String): Result {
        val a = text(old)
        val b = text(new)
        if (a == b) return Result(new, b.length, b.length)
        val (p, s) = common(a, b)
        val removed = slice(old, p, a.length - s)
        val inserted = slice(new, p, b.length - s)
        val kept = removed.mapNotNull { r ->
            val rev = r.rev
            when {
                rev != null && rev.kind == RevisionKind.DELETE -> r
                rev != null && rev.kind == RevisionKind.INSERT && rev.author == author -> null
                else -> r.copy(rev = RunRev(RevisionKind.DELETE, author, dateIso))
            }
        }
        // typing inside or next to my own insertion makes that insertion longer
        fun mine(r: Run?): RunRev? = r?.rev?.takeIf { it.kind == RevisionKind.INSERT && it.author == author }
        val insRev = mine(runAt(old, p - 1)) ?: mine(runAt(old, a.length - s)) ?: RunRev(RevisionKind.INSERT, author, dateIso)
        val added = inserted.map { it.copy(rev = insRev) }
        val keptLen = kept.sumOf { it.text.length }
        val runs = normalize(slice(new, 0, p) + kept + added + slice(new, b.length - s, b.length))
        val insLen = added.sumOf { it.text.length }
        return Result(runs, if (insLen > 0) p + keptLen + insLen else p, p + keptLen)
    }

    /** The same for the editor's spans. */
    fun mergeSpans(old: List<Span>, new: List<Span>, author: String, dateIso: String): List<Span> =
        merge(old.map { it.toRun() }, new.map { it.toRun() }, author, dateIso).map { it.toSpan() }

    fun text(runs: List<Run>): String = runs.joinToString("") { it.text }

    /**
     * Lengths of the text two strings share at the start and (of what is left) at the end, never cutting a
     * surrogate pair in two. The same rule as the editor's `RichText.applyEdit`, so both see one edit the same way.
     */
    fun common(a: String, b: String): Pair<Int, Int> {
        var p = 0
        val max = minOf(a.length, b.length)
        while (p < max && a[p] == b[p]) p++
        var s = 0
        while (s < max - p && a[a.length - 1 - s] == b[b.length - 1 - s]) s++
        if (p > 0 && Character.isHighSurrogate(a[p - 1])) p--
        if (s > 0 && Character.isLowSurrogate(a[a.length - s])) s--
        return p to s
    }

    /** Characters [from, to) of [runs], runs cut where needed. */
    fun slice(runs: List<Run>, from: Int, to: Int): List<Run> {
        if (to <= from) return emptyList()
        val out = ArrayList<Run>()
        var pos = 0
        for (r in runs) {
            val a = pos
            val b = pos + r.text.length
            pos = b
            if (b <= from || a >= to || r.text.isEmpty()) continue
            val lo = maxOf(a, from) - a
            val hi = minOf(b, to) - a
            out.add(if (lo == 0 && hi == r.text.length) r else r.copy(text = r.text.substring(lo, hi)))
        }
        return out
    }

    /** The run holding character [index]; null outside the text. */
    fun runAt(runs: List<Run>, index: Int): Run? {
        if (index < 0) return null
        var pos = 0
        for (r in runs) {
            if (index < pos + r.text.length) return r
            pos += r.text.length
        }
        return null
    }

    /** Joins neighbours that differ in nothing but their text; drops empty runs. */
    fun normalize(runs: List<Run>): List<Run> {
        val out = ArrayList<Run>()
        for (r in runs) {
            if (r.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.copy(text = "") == r.copy(text = "")) out[out.size - 1] = last.copy(text = last.text + r.text) else out.add(r)
        }
        return out
    }
}

/**
 * Things that sit between the characters of a paragraph and are not text: the two ends of a commented range, the run
 * that holds a comment's reference, bookmark ends. [off] counts characters of [Paragraph.text]; [raw] is the element
 * as the file has it (written back unchanged); [cid] = the comment's or the bookmark's `w:id` (-1 when unreadable).
 */
internal class Mark(val off: Int, val kind: Int, val cid: Int, val raw: String) {
    fun at(o: Int): Mark = if (o == off) this else Mark(o, kind, cid, raw)
    /** True for the kinds that open a range. */
    val opens: Boolean get() = kind == C_START || kind == B_START

    companion object {
        const val C_START = 0
        const val C_END = 1
        const val C_REF = 2
        const val B_START = 3
        const val B_END = 4

        /**
         * Where [marks] of a paragraph are once its text changed from [old] to [new] (one replaced stretch between the
         * shared start and end). Text typed at either edge of a range stays outside it, text typed inside makes it
         * longer, a replaced range covers its replacement, and a range whose text is gone stays as an empty range at
         * that place. The order of marks at one place is kept.
         */
        fun shift(marks: List<Mark>, old: String, new: String): List<Mark> {
            if (marks.isEmpty() || old == new) return marks
            if (old.isEmpty()) return marks.map { it.at(if (it.opens) 0 else new.length) }
            val (p, s) = Tracking.common(old, new)
            val q = old.length - s
            val n = new.length - s - p
            val out = marks.map { m ->
                val o = m.off
                val to = if (m.opens) when {
                    o < p -> o
                    o == p && q > p -> p
                    o < q -> p + n
                    else -> o - (q - p) + n
                } else when {
                    o <= p -> o
                    o < q -> p
                    else -> o - (q - p) + n
                }
                m.at(to.coerceIn(0, new.length))
            }.toMutableList()
            // a range never starts after its own end
            for (i in out.indices) {
                val m = out[i]
                if (!m.opens) continue
                val endKind = if (m.kind == C_START) C_END else B_END
                val end = out.firstOrNull { it.kind == endKind && it.cid == m.cid } ?: continue
                if (m.off > end.off) out[i] = m.at(end.off)
            }
            // written in order of place; marks at one place keep the order they had
            return out.withIndex().sortedWith(compareBy({ it.value.off }, { it.index })).map { it.value }
        }
    }
}
