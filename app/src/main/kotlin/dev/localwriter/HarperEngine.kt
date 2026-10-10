package dev.localwriter

import org.json.JSONArray
import java.util.concurrent.CancellationException

/** English spelling/grammar lints from the bundled Rust engine. No network calls. */
class HarperEngine {
    companion object {
        init { System.loadLibrary("localharper") }
    }

    private external fun lint(text: String, dialect: String, personalDictionary: Array<String>, style: Boolean): String

    fun check(text: String, dialect: String, dictionary: Set<String>, style: Boolean, active: () -> Boolean): List<Edit> {
        fun ensureActive() { if (!active()) throw CancellationException("Harper check superseded") }
        ensureActive()
        if (text.isBlank()) return emptyList()
        val output = lint(text, dialect, dictionary.sorted().toTypedArray(), style)
        ensureActive()
        val result = JSONArray(output)
        val edits = ArrayList<Edit>(result.length())
        for (i in 0 until result.length()) {
            ensureActive()
            val item = result.getJSONObject(i)
            val begin = item.getInt("begin")
            val end = item.getInt("end")
            require(begin in 0..end && end <= text.length) { "Invalid Harper UTF-16 range" }
            require(begin == text.length || !text[begin].isLowSurrogate()) { "Harper range splits a Unicode character" }
            require(end == text.length || !text[end].isLowSurrogate()) { "Harper range splits a Unicode character" }
            val choices = item.getJSONArray("alternatives")
            edits += Edit(
                begin = begin, end = end, replacement = item.getString("replacement"),
                alternatives = List(choices.length()) { choices.getString(it) },
                category = item.getString("category"), explanation = item.getString("explanation"),
                engine = "harper", ruleId = item.getString("ruleId"),
                actionable = item.getBoolean("actionable"), priority = item.getInt("priority"),
                style = item.getBoolean("style")
            )
        }
        return edits
    }
}
