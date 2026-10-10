package dev.localwriter

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.regex.Pattern

/** Byte BPE and a quantized GEC tagger. No Android or network dependencies. */
class GrammarEngine(dir: File) : AutoCloseable {
    private val vocab = mutableMapOf<String, Int>()
    private val ranks = mutableMapOf<String, Int>()
    private val verbs = mutableMapOf<String, String>()
    private val cache = object : LinkedHashMap<String, List<String>>() {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<String>>) = size > 4096
    }
    private val labels: Array<String>
    private val bytes = Array(256) { "" }
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val bpePattern = Pattern.compile("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+")
    init {
        val v = JSONObject(File(dir, "vocab.json").readText()); v.keys().forEach { vocab[it] = v.getInt(it) }
        val l = JSONObject(File(dir, "labels.json").readText()); labels = Array(l.length()) { l.getString(it.toString()) }
        var rank = 0
        File(dir, "merges.txt").forEachLine { if (!it.startsWith("#") && it.contains(' ')) ranks[it.replace(' ', '\u0000')] = rank++ }
        var extra = 256
        for (b in 0..255) bytes[b] = String(Character.toChars(if (b in 33..126 || b in 161..172 || b in 174..255) b else extra++))
        File(dir, "verb-form-vocab.txt").forEachLine {
            val h = it.split(':', limit = 2)
            if (h.size == 2) {
                val w = h[0].split('_', limit = 2); val t = h[1].split('_', limit = 2)
                if (w.size == 2 && t.size == 2 && t[0] == "VB") verbs.putIfAbsent("${w[0].lowercase(Locale.ROOT)}:${t[1]}", w[1])
            }
        }
        OrtSession.SessionOptions().use {
            it.setIntraOpNumThreads(2); it.setInterOpNumThreads(1)
            session = environment.createSession(File(dir, "gec-tagger.int8.onnx").absolutePath, it)
        }
    }
    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }; var parts = token.map { it.toString() }
        while (parts.size > 1) {
            var best = Int.MAX_VALUE; var pair: String? = null
            for (i in 0 until parts.lastIndex) {
                val p = parts[i] + '\u0000' + parts[i + 1]; val r = ranks[p] ?: Int.MAX_VALUE
                if (r < best) { best = r; pair = p }
            }
            if (pair == null) break
            val next = mutableListOf<String>(); var i = 0
            while (i < parts.size) {
                if (i < parts.lastIndex && parts[i] + '\u0000' + parts[i + 1] == pair) { next += parts[i] + parts[i + 1]; i += 2 }
                else next += parts[i++]
            }
            parts = next
        }
        cache[token] = parts; return parts
    }
    private data class Encoded(val ids: MutableList<Int> = mutableListOf(), val ends: MutableList<Int> = mutableListOf())
    private fun encode(text: String): Encoded {
        val out = Encoded(); val m = bpePattern.matcher(" $text")
        while (m.find()) {
            val piece = m.group(); val mapped = piece.toByteArray(Charsets.UTF_8).joinToString("") { bytes[it.toInt() and 255] }
            val ends = mutableListOf<Int>(); var i = 0
            while (i < piece.length) {
                val cp = piece.codePointAt(i); val length = Character.charCount(cp)
                repeat(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size) { ends += m.start() + i + length - 1 }
                i += length
            }
            var cursor = 0
            for (part in bpe(mapped)) { cursor += part.length; out.ids += vocab[part] ?: vocab.getValue("<unk>"); out.ends += ends[cursor - 1] }
        }
        return out
    }
    private fun transform(word: String, tag: String): String {
        if (tag == "\$TRANSFORM_CASE_CAPITALIZE") return word.replaceFirstChar { it.uppercaseChar() }
        if (tag == "\$TRANSFORM_CASE_LOWER") return word.lowercase(Locale.ROOT)
        if (!tag.startsWith("\$TRANSFORM_VERB_")) return word
        val kind = tag.removePrefix("\$TRANSFORM_VERB_"); val pos = when (kind) { "ING" -> "VBG"; "ED" -> "VBD"; else -> "VBZ" }
        verbs["${word.lowercase(Locale.ROOT)}:$pos"]?.let { return it }
        return when (kind) {
            "S" -> if (word.matches(Regex(".*(s|x|z|ch|sh)$"))) word + "es" else if (word.matches(Regex(".*[^aeiou]y$"))) word.dropLast(1) + "ies" else word + "s"
            "ED" -> if (word.endsWith("e")) word + "d" else if (word.matches(Regex(".*[^aeiou]y$"))) word.dropLast(1) + "ied" else word + "ed"
            "ING" -> if (word.endsWith("ie")) word.dropLast(2) + "ying" else if (word.endsWith("e") && !word.endsWith("ee")) word.dropLast(1) + "ing" else word + "ing"
            else -> word
        }
    }
    private fun join(words: List<String>) = words.joinToString(" ")
        .replace(Regex("\\s+([.,!?;:)%\\]}])"), "$1").replace(Regex("([(\\[{])\\s+"), "$1")
        .replace(Regex("\\s+(n't|'m|'s|'ve|'re|'d|'ll)\\b"), "$1").replace(Regex("\\s+(['’])"), "$1").replace(Regex("\\s{2,}"), " ").trim()
    fun correctSentence(text: String, active: () -> Boolean = { true }): String {
        var cur = text; val visited = mutableSetOf<String>()
        repeat(4) {
            if (!active()) throw CancellationException()
            if (!visited.add(cur)) return cur
            val words = TextLogic.words(cur); if (words.isEmpty()) return cur
            val enc = encode(cur); require(enc.ids.size <= 510) { "Sentence exceeds local model context" }
            val ids = arrayOf(LongArray(enc.ids.size + 2)); val mask = arrayOf(LongArray(enc.ids.size + 2) { 1 })
            ids[0][0] = vocab.getValue("<s>").toLong(); ids[0][ids[0].lastIndex] = vocab.getValue("</s>").toLong()
            enc.ids.forEachIndexed { i, id -> ids[0][i + 1] = id.toLong() }
            val tags = Array(words.size) { "\$KEEP" }; var si = 0
            OnnxTensor.createTensor(environment, ids).use { input -> OnnxTensor.createTensor(environment, mask).use { attention ->
                session.run(mapOf("input_ids" to input, "attention_mask" to attention)).use { result ->
                    @Suppress("UNCHECKED_CAST") val logits = (result[0].value as Array<Array<FloatArray>>)[0]
                    for (i in words.indices) {
                        while (si < enc.ends.size && enc.ends[si] <= words[i].start) si++
                        val row = logits[1 + minOf(si, enc.ids.lastIndex)]; var best = 0
                        for (j in 1 until row.size) if (row[j] > row[best]) best = j
                        val sum = row.sumOf { Math.exp((it - row[best]).toDouble()) }
                        tags[i] = if (1 / sum >= 0.7) labels[best] else "\$KEEP"
                    }
                }
            } }
            val drop = mutableSetOf<Int>(); val keep = mutableSetOf<Int>()
            for (i in tags.indices) {
                val tag = tags[i]
                if (tag.startsWith("\$REPLACE_")) {
                    val payload = tag.substring(9).split(' ')
                    if (payload.size > 1 && i + payload.size <= words.size && (1 until payload.size).all { words[i + it].text == payload[it] })
                        (1 until payload.size).forEach { drop += i + it }
                }
                if (tag != "\$KEEP" && i < tags.lastIndex && tag == tags[i + 1] && !tag.startsWith("\$APPEND_") && i + 1 !in drop) keep += i + 1
            }
            val result = mutableListOf<String>(); var changed = false; var i = 0
            while (i < words.size) {
                val w = words[i].text; val t = tags[i]
                when {
                    i in drop -> {}
                    i in keep || t == "\$KEEP" -> result += w
                    t == "\$DELETE" -> changed = true
                    t.startsWith("\$APPEND_") -> { result += w; result += t.substring(8); changed = true }
                    t.startsWith("\$REPLACE_") -> { result += t.substring(9).split(' '); changed = true }
                    t.startsWith("\$MERGE_") && i < words.lastIndex -> { result += t.substring(7); i++; changed = true }
                    t.startsWith("\$TRANSFORM_") -> { val v = transform(w, t); result += v; changed = changed || v != w }
                    else -> result += w
                }
                i++
            }
            if (!changed) return cur
            val next = join(result); if (next == cur) return cur; cur = next
        }
        return cur
    }
    fun check(text: String, active: () -> Boolean = { true }): List<Edit> {
        val edits = mutableListOf<Edit>(); val sentences = Pattern.compile("[^.!?\\r\\n]+[.!?]*|[.!?]+").matcher(text)
        while (sentences.find()) {
            var start = sentences.start(); val end = sentences.end()
            while (start < end && text[start].isWhitespace()) start++
            while (start < end) {
                if (!active()) throw CancellationException()
                var boundary = start; var count = 0
                val tokens = Pattern.compile("\\S+\\s*").matcher(text).region(start, end)
                while (tokens.find() && count++ < 80) boundary = tokens.end()
                if (boundary == start) break
                var sentence = text.substring(start, boundary).trimEnd()
                while (encode(sentence).ids.size > 400 && sentence.length > 1) {
                    var split = sentence.lastIndexOf(' ', sentence.length / 2)
                    if (split < 1) split = sentence.offsetByCodePoints(0, maxOf(1, sentence.codePointCount(0, sentence.length) / 2))
                    boundary = start + split; sentence = text.substring(start, boundary)
                }
                val corrected = correctSentence(sentence, active)
                if (corrected != sentence) edits += TextLogic.diff(sentence, corrected, start)
                start = boundary
            }
        }
        return edits
    }
    override fun close() = session.close()
}
