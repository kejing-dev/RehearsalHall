package io.embodit.spatialoffice.core.review

/**
 * M5 (v0.7.0): what the review pane shows, the same shape for documents, sheets and slides.
 * The three file modules (`docx`, `xlsx`, `pptx`) read their own parts into these and write them back;
 * nothing here knows a file format.
 */

/** THREAD = a comment people can reply to and resolve; NOTE = an old-style note / comment (text only, no replies, no resolve). */
enum class CommentKind { THREAD, NOTE }

/** One reply inside a thread. [id] is unique within the open file. [date] is ISO-8601 (`2026-11-22T10:00:00Z`) or "". */
data class ReviewReply(
    val id: String,
    val author: String,
    val date: String,
    val text: String,
)

/**
 * One comment (the first message of a thread) with its replies.
 * Where it sits depends on the file kind; the fields of the other kinds keep their defaults:
 * - document: [block] = id of the block the commented range starts in, [start]..[end] = character range inside that
 *   paragraph's text (start == end: the whole paragraph), [quote] = the commented text;
 * - sheet: [sheet] = sheet index, [cell] = A1 reference ("C2"), [quote] = the same reference;
 * - slides: [slide] = slide index, [x] / [y] = position as a share of the slide (0–1), [quote] = "".
 */
data class ReviewComment(
    val id: String,
    val kind: CommentKind,
    val author: String,
    val date: String,
    val text: String,
    val quote: String = "",
    val resolved: Boolean = false,
    val replies: List<ReviewReply> = emptyList(),
    val block: Int = -1,
    val start: Int = 0,
    val end: Int = 0,
    val sheet: Int = -1,
    val cell: String = "",
    val slide: Int = -1,
    val x: Float = 0f,
    val y: Float = 0f,
) {
    /** Messages in the thread: the comment itself plus its replies. */
    val count: Int get() = 1 + replies.size
}

/** INSERT / DELETE can be accepted or rejected; FORMAT and MOVE are listed but read-only in this version. */
enum class RevisionKind { INSERT, DELETE, FORMAT, MOVE }

/**
 * One tracked change in a document. [id] is unique within the open file and stays valid until the document changes.
 * [text] = the inserted or deleted text ("" for FORMAT / MOVE). [block] = id of the paragraph it is in.
 */
data class Revision(
    val id: String,
    val kind: RevisionKind,
    val author: String,
    val date: String,
    val text: String,
    val block: Int,
) {
    val canResolve: Boolean get() = kind == RevisionKind.INSERT || kind == RevisionKind.DELETE
}

object ReviewText {
    /** First letter shown in the round avatar: the first letter or CJK character of the name, upper-cased; "?" when empty. */
    fun initial(author: String): String {
        val t = author.trim()
        if (t.isEmpty()) return "?"
        val cp = t.codePointAt(0)
        return String(Character.toChars(cp)).uppercase()
    }

    /** Initials stored in files that ask for them (`w:initials`): first letters of up to three words, or the first character. */
    fun initials(author: String): String {
        val words = author.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""
        if (words.size == 1) return initial(words[0])
        return words.take(3).joinToString("") { initial(it) }
    }

    /** Thread list order: open threads first, each group in the order the file gives them. */
    fun ordered(list: List<ReviewComment>): List<ReviewComment> = list.filter { !it.resolved } + list.filter { it.resolved }
}
