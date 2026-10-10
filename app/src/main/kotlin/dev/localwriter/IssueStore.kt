package dev.localwriter

import java.text.BreakIterator
import java.util.Locale

data class CheckUpdate(
    val issues: List<Edit>, val pending: Boolean = false, val unavailable: Boolean = false,
    val message: String = ""
) {
    val state: WritingCheckState get() = when {
        pending && issues.isNotEmpty() -> WritingCheckState.PARTIAL
        pending -> WritingCheckState.CHECKING
        unavailable -> WritingCheckState.UNAVAILABLE
        else -> WritingCheckState.READY
    }
}

/** Owned by an editor session, shared by its badge, marks and correction panels. */
class IssueStore {
    var text: String = ""; private set
    private var dismissed = emptyList<Edit>()
    private var result = CheckUpdate(emptyList())
    val current: CheckUpdate get() = result.copy(issues = result.issues.filterNot(::ignored))

    fun reset() { text = ""; dismissed = emptyList(); result = CheckUpdate(emptyList()) }
    fun invalidate() { result = CheckUpdate(emptyList(), pending = text.isNotBlank()) }
    fun update(value: String) {
        if (value == text) return
        val before = text
        dismissed = rebase(before, value, dismissed)
        result = CheckUpdate(rebase(before, value, result.issues), pending = value.isNotBlank())
        text = value
    }
    fun publish(value: CheckUpdate) {
        val retained = if (value.pending) result.issues.filter { it.engine != "harper" } else emptyList()
        result = value.copy(issues = merge(text, value.issues + retained))
    }
    fun dismiss(issue: Edit) { dismissed = dismissed + issue }
    private fun ignored(issue: Edit) = dismissed.any {
        it.begin == issue.begin && it.end == issue.end && it.ruleId == issue.ruleId &&
            (it.replacement == issue.replacement || !it.actionable && !issue.actionable)
    }

    companion object {
        fun counted(issues: List<Edit>) = issues.count { it.actionable && !it.style }
        fun valid(text: String, issue: Edit): Boolean = issue.begin in 0..issue.end && issue.end <= text.length &&
            (issue.begin == text.length || !text[issue.begin].isLowSurrogate()) &&
            (issue.end == text.length || !text[issue.end].isLowSurrogate())

        fun merge(text: String, issues: List<Edit>): List<Edit> {
            val valid = issues.filter { valid(text, it) }
            val merged = linkedMapOf<String, Edit>()
            for (issue in valid.sortedWith(compareBy<Edit> { it.engine != "harper" }.thenBy { it.priority })) {
                val key = if (issue.actionable) "${issue.begin}:${issue.end}:edit" else "${issue.begin}:${issue.end}:${issue.ruleId}"
                val previous = merged[key]
                merged[key] = if (previous != null && previous.actionable && issue.actionable) {
                    // SplitWords guesses a missing space from dictionary halves.
                    // A competing whole-word spelling correction gets first choice;
                    // keep the split as a reviewable alternative.
                    val preferred = if (previous.engine == "harper" && previous.ruleId == "SplitWords" &&
                        issue.engine == "harper" && issue.category == "Spelling") issue else previous
                    preferred.copy(alternatives = (previous.choices + issue.choices).distinct().filter { it != preferred.replacement })
                } else previous ?: issue
            }
            // Context may disambiguate a basic spelling candidate, but may not
            // invent a new primary correction or override an unrelated phrase.
            for ((key, issue) in merged.toMap()) {
                if (!issue.actionable || issue.engine != "harper" ||
                    !text.substring(issue.begin, issue.end).matches(Regex("[\\p{L}\\p{M}’'-]+"))) continue
                val basic = valid.filter { it.actionable && it.engine == "harper" &&
                    it.begin == issue.begin && it.end == issue.end && it.category in listOf("Spelling", "Typo") }
                val contextual = valid.firstOrNull { it.actionable && it.engine == "t5" &&
                    it.begin == issue.begin && it.end == issue.end && it.choices.size == 1 &&
                    basic.any { candidate -> it.replacement in candidate.choices } } ?: continue
                val preferred = basic.firstOrNull { it.replacement == contextual.replacement } ?: issue.copy(
                    explanation = "This spelling is supported by the contextual check.")
                merged[key] = preferred.copy(replacement = contextual.replacement,
                    alternatives = issue.choices.filter { it != contextual.replacement })
            }
            // A neural phrase and a basic word correction must not count as two
            // independent errors. Prefer the narrower, explained basic finding;
            // rechecking after acceptance can expose the remaining phrase edit.
            val reconciled = mutableListOf<Edit>()
            for (issue in merged.values) {
                if (issue.actionable && reconciled.any { it.actionable &&
                        it.engine != issue.engine && !compatible(it, issue) }) continue
                reconciled += issue
            }
            return reconciled.sortedWith(compareBy<Edit> { it.begin }.thenBy { it.style }.thenBy { it.priority })
        }

        fun compatible(a: Edit, b: Edit): Boolean = when {
            a.begin == a.end -> a.begin !in b.begin..b.end
            b.begin == b.end -> b.begin !in a.begin..a.end
            else -> a.end <= b.begin || b.end <= a.begin
        }
        fun bulk(issues: List<Edit>): List<Edit> {
            val selected = mutableListOf<Edit>()
            for (issue in issues.filter { it.actionable && !it.style && it.choices.size == 1 }.sortedBy { it.priority })
                if (selected.all { compatible(it, issue) }) selected += issue
            return selected.sortedBy { it.begin }
        }

        /** Only retain findings outside sentences whose input changed. */
        fun rebase(old: String, value: String, issues: List<Edit>): List<Edit> {
            if (old == value) return issues
            if (old.isEmpty() || value.isEmpty()) return emptyList()
            var prefix = 0
            while (prefix < minOf(old.length, value.length) && old[prefix] == value[prefix]) prefix++
            if (prefix < old.length && old[prefix].isLowSurrogate()) prefix--
            var suffix = 0
            while (suffix < minOf(old.length, value.length) - prefix && old[old.lastIndex-suffix] == value[value.lastIndex-suffix]) suffix++
            var oldEnd = old.length - suffix
            if (oldEnd < old.length && old[oldEnd].isLowSurrogate()) oldEnd++
            val boundaries = BreakIterator.getSentenceInstance(Locale.ENGLISH).apply { setText(old) }
            val affectedStart = if (prefix == 0) 0 else boundaries.preceding(prefix).let { if (it < 0) 0 else it }
            val affectedEnd = if (oldEnd >= old.length) old.length else boundaries.following(oldEnd).let { if (it < 0) old.length else it }
            val delta = value.length - old.length
            return issues.mapNotNull { issue ->
                when {
                    issue.end < affectedStart -> issue
                    issue.begin > affectedEnd -> issue.copy(begin = issue.begin + delta, end = issue.end + delta)
                    else -> null
                }
            }.filter { valid(value, it) }
        }
    }
}
