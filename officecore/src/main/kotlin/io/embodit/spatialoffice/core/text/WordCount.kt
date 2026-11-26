package io.embodit.spatialoffice.core.text

/** Word and character counts the way desktop word processors show them for mixed Chinese / Latin text. */
object WordCount {
    data class Result(val words: Int, val chars: Int, val charsNoSpaces: Int, val paragraphs: Int)

    private fun isCjk(c: Char): Boolean {
        val b = Character.UnicodeBlock.of(c)
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS || b == Character.UnicodeBlock.HIRAGANA ||
            b == Character.UnicodeBlock.KATAKANA || b == Character.UnicodeBlock.HANGUL_SYLLABLES ||
            b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION || b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }

    /** Each CJK character counts as one word; a run of other non-space characters counts as one word. */
    fun count(text: String): Result {
        var words = 0
        var inWord = false
        var chars = 0
        var noSpace = 0
        for (c in text) {
            if (c == '\n' || c == '\r') { inWord = false; continue }
            chars++
            if (c.isWhitespace()) { inWord = false; continue }
            noSpace++
            if (isCjk(c)) {
                inWord = false
                if (Character.UnicodeBlock.of(c) != Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION && Character.UnicodeBlock.of(c) != Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS) words++
            } else if (!inWord) { inWord = true; if (c.isLetterOrDigit()) words++ else inWord = false }
        }
        val paragraphs = text.split('\n').count { it.isNotBlank() }
        return Result(words, chars, noSpace, paragraphs)
    }
}
