package dev.localwriter

import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException

/** A bundled frequency dictionary supplies suggestions the grammar tagger can miss. */
class SpellingEngine(file: File) {
    private val frequency = HashMap<String, Long>(90_000)
    init {
        file.forEachLine { line ->
            val split = line.lastIndexOf(' ')
            if (split > 0) {
                val word = line.substring(0, split).trimStart('\uFEFF')
                val count = line.substring(split + 1).toLongOrNull()
                if (count != null && word.isNotEmpty() && word.all { it in 'a'..'z' }) frequency[word] = count
            }
        }
    }
    fun check(text: String, dictionary: Set<String>, active: () -> Boolean): List<Edit> {
        val words = TextLogic.words(text)
        val edits = mutableListOf<Edit>()
        var unknowns = 0
        for ((index, token) in words.withIndex()) {
            if (!active()) throw CancellationException()
            val original = token.text
            val word = original.lowercase(Locale.ROOT)
            if (word.length !in 4..24 || !word.matches(Regex("[a-z]+")) ||
                original.any(Char::isUpperCase) || word in frequency || word in dictionary) continue
            // Avoid addresses, URLs, identifiers and words embedded in contractions.
            if ((token.start > 0 && text[token.start - 1] in "@/#_'’") ||
                (token.end < text.length && text[token.end] in "@/_'’")) continue
            if (++unknowns > 48) break
            val candidates = HashSet<String>()
            fun consider(candidate: String) { if ((frequency[candidate] ?: 0) >= 1_000_000) candidates += candidate }
            for (i in word.indices) {
                consider(word.removeRange(i, i + 1))
                if (i < word.lastIndex) consider(word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2))
                for (letter in 'a'..'z') consider(word.substring(0, i) + letter + word.substring(i + 1))
            }
            for (i in 0..word.length) for (letter in 'a'..'z') consider(word.substring(0, i) + letter + word.substring(i))
            val ranked = candidates.sortedWith(compareByDescending<String> { frequency[it] }.thenBy { it })
            var replacement = ranked.firstOrNull() ?: continue
            val runnerUp = ranked.getOrNull(1)?.let { frequency[it] } ?: 0
            if ((frequency[replacement] ?: 0) < runnerUp * 2) continue
            // An unknown extra-letter typo after a/an should suggest the singular form.
            val previous = words.getOrNull(index - 1)
            if (previous?.text?.lowercase(Locale.ROOT) in listOf("a", "an") &&
                previous != null && text.substring(previous.end, token.start).all(Char::isWhitespace) &&
                replacement.endsWith('s') && !replacement.endsWith("ss") &&
                (frequency[replacement.dropLast(1)] ?: 0) >= 1_000_000) replacement = replacement.dropLast(1)
            edits += Edit(token.start, token.end, replacement)
        }
        return edits
    }
}
