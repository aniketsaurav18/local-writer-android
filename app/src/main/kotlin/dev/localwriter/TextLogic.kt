package dev.localwriter

import java.util.regex.Pattern

data class Edit(val begin: Int, val end: Int, val replacement: String) {
    fun key(text: String) = "${text.substring(begin, end)}\u0000$replacement"
}

/** All positions are UTF-16, matching Android's editor and accessibility APIs. */
object TextLogic {
    data class Word(val text: String, val start: Int, val end: Int)
    // Android's ICU regex rejects Java's UNICODE_CHARACTER_CLASS flag.
    // Spell out Unicode classes so desktop and Android token offsets agree.
    private const val WORD = "\\p{L}\\p{M}\\p{N}\\p{Pc}\\u200C\\u200D"
    private const val SPACE = "\\p{Z}\\t\\n\\x0B\\f\\r\\u0085"
    private val wordPattern = Pattern.compile("[$WORD]+n't|[$WORD]+'m|[$WORD]+'s|[$WORD]+'ve|[$WORD]+'re|[$WORD]+'d|[$WORD]+'ll|[$WORD]+|[^$WORD$SPACE]")
    private val contraction = Pattern.compile("^([$WORD]+?)(n't|'m|'s|'ve|'re|'d|'ll)$", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
    fun words(text: String): List<Word> {
        val out = mutableListOf<Word>(); val m = wordPattern.matcher(text)
        while (m.find()) {
            val c = contraction.matcher(m.group())
            if (c.matches()) {
                val stem = requireNotNull(c.group(1)); val suffix = requireNotNull(c.group(2))
                val split = m.start() + stem.length
                out += Word(stem, m.start(), split); out += Word(suffix, split, m.end())
            } else out += Word(m.group(), m.start(), m.end())
        }
        return out
    }
    /** Insertions must mark a visible word instead of an empty space at the insertion point. */
    fun highlightRange(text: String, edit: Edit): Pair<Int, Int>? {
        if (edit.begin !in 0..edit.end || edit.end > text.length) return null
        if (edit.begin < edit.end && text.substring(edit.begin, edit.end).any { !it.isWhitespace() })
            return edit.begin to edit.end
        val tokens = words(text)
        val word = tokens.firstOrNull { it.end > edit.begin && it.text.any(Char::isLetterOrDigit) }
            ?: tokens.lastOrNull { it.text.any(Char::isLetterOrDigit) }
        return word?.let { it.start to it.end }
    }
    fun diff(old: String, corrected: String, offset: Int = 0): List<Edit> {
        val a = words(old); val b = words(corrected)
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed())
            lcs[i][j] = if (a[i].text == b[j].text) 1 + lcs[i + 1][j + 1] else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        val matches = mutableListOf<Pair<Int, Int>>(); var i = 0; var j = 0
        while (i < a.size && j < b.size) {
            if (a[i].text == b[j].text) { matches += i to j; i++; j++ }
            else if (lcs[i + 1][j] >= lcs[i][j + 1]) i++ else j++
        }
        matches += a.size to b.size
        val out = mutableListOf<Edit>(); var ai = 0; var bi = 0; var oldEnd = 0; var newEnd = 0
        for ((am, bm) in matches) {
            val oe = a.getOrNull(am)?.start ?: old.length; val ne = b.getOrNull(bm)?.start ?: corrected.length
            if (am != ai || bm != bi) {
                val before = old.substring(oldEnd, oe); val after = corrected.substring(newEnd, ne)
                var prefix = 0; var suffix = 0
                while (prefix < before.length && prefix < after.length && before[prefix] == after[prefix] && before[prefix].isWhitespace()) prefix++
                while (suffix < before.length - prefix && suffix < after.length - prefix && before[before.lastIndex - suffix] == after[after.lastIndex - suffix] && before[before.lastIndex - suffix].isWhitespace()) suffix++
                out += Edit(offset + oldEnd + prefix, offset + oe - suffix, after.substring(prefix, after.length - suffix))
            }
            if (am < a.size) { oldEnd = a[am].end; newEnd = b[bm].end }
            ai = am + 1; bi = bm + 1
        }
        return out
    }
    fun apply(text: String, edits: List<Edit>): String {
        var end = text.length; var result = text
        for (e in edits.sortedByDescending { it.begin }) {
            require(e.begin in 0..e.end && e.end <= end) { "Invalid or overlapping correction" }
            require(e.begin == 0 || e.begin == text.length || !text[e.begin].isLowSurrogate()) { "Split Unicode character" }
            require(e.end == 0 || e.end == text.length || !text[e.end].isLowSurrogate()) { "Split Unicode character" }
            result = result.substring(0, e.begin) + e.replacement + result.substring(e.end); end = e.begin
        }
        return result
    }
    fun sensitive(inputType: Int): Boolean {
        val klass = inputType and 15; val variation = inputType and 0xff0
        return (klass == 1 && variation in setOf(0x80, 0x90, 0xe0)) || (klass == 2 && variation == 0x10)
    }
    fun mapPosition(position: Int, edits: List<Edit>): Int {
        var delta = 0
        for (edit in edits.sortedBy { it.begin }) {
            if (position < edit.begin) break
            if (position <= edit.end) return edit.begin + delta + edit.replacement.length
            delta += edit.replacement.length - (edit.end - edit.begin)
        }
        return position + delta
    }
    fun editableTextType(inputType: Int) = inputType and 15 == 1 && !sensitive(inputType)
}
