package dev.localwriter

import java.io.File
import java.text.BreakIterator
import java.util.Locale
import java.util.concurrent.CancellationException

/** Sentence-scoped generative GEC; suggestions retain the source's UTF-16 offsets. */
class ContextGrammarEngine(dir: File) : AutoCloseable {
    private val modelPath = File(dir, "context-t5-small-q8.gguf").absolutePath
    private val cache = object : LinkedHashMap<String, List<Edit>>(32, 0.75f, true) {
        override fun removeEldestEntry(entry: MutableMap.MutableEntry<String, List<Edit>>) = size > 64
    }
    @Volatile private var closed = false

    fun check(text: String, active: () -> Boolean = { true }): List<Edit> {
        check(!closed) { "Context checker is closed" }
        val result = mutableListOf<Edit>()
        val sentences = BreakIterator.getSentenceInstance(Locale.ENGLISH)
        sentences.setText(text)
        var begin = sentences.first()
        var end = sentences.next()
        while (end != BreakIterator.DONE) {
            requireActive(active)
            var sourceBegin = begin
            var sourceEnd = end
            while (sourceBegin < sourceEnd && text[sourceBegin].isWhitespace()) sourceBegin++
            while (sourceEnd > sourceBegin && text[sourceEnd - 1].isWhitespace()) sourceEnd--
            val sentence = text.substring(sourceBegin, sourceEnd)
            // Keep the complete sentence. Never chop it into arbitrary fragments
            // or silently truncate a long source to fit the model.
            if (sentence.any(Char::isLetter)) {
                require(sentence.length <= 1200) { "Sentence exceeds context check limit" }
                val local = cache[sentence] ?: run {
                    val ticket = epoch()
                    requireActive(active)
                    val corrected = generate(modelPath, sentence, ticket).trim()
                    requireActive(active)
                    // Explicitly reject empty generations and unsupported control
                    // text before converting the correction into localized edits.
                    check(corrected.isNotEmpty() && corrected.none { it == '\u0000' }) { "Invalid context correction" }
                    TextLogic.correctionDiff(sentence, corrected).map {
                        it.copy(engine = "t5", category = "Suggested correction")
                    }.also { cache[sentence] = it }
                }
                result += local.map { it.copy(begin = it.begin + sourceBegin, end = it.end + sourceBegin) }
            }
            begin = end
            end = sentences.next()
        }
        requireActive(active)
        return result
    }

    private fun requireActive(active: () -> Boolean) {
        if (closed || !active() || Thread.currentThread().isInterrupted) throw CancellationException()
    }

    /** Worker-confined; the native state can also be released between batches. */
    override fun close() { closed = true; cancel(); cache.clear(); release() }

    companion object {
        init { System.loadLibrary("localrewrite") }
        @JvmStatic private external fun generate(modelPath: String, text: String, epoch: Long): String
        @JvmStatic private external fun epoch(): Long
        @JvmStatic external fun cancel()
        @JvmStatic external fun release()
    }
}
