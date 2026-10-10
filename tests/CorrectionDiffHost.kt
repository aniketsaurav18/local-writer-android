package dev.localwriter

/** Regression checks for independently accepting a word-order correction. */
fun main() {
    sharedTextLogicRegressions()
    val examples = listOf(
        "Hey why you are ignoring my messages?" to "Hey, why are you ignoring my messages?",
        "Can you tell me where is the nearest bus stop?" to "Can you tell me where the nearest bus stop is?",
        "She enjoys to dance." to "She enjoys dancing.",
        "hey i recieved your mesage" to "Hey I received your message",
        "😀 Can you tell me where is the bus stop?" to "😀 Can you tell me where the bus stop is?"
    )
    for ((source, corrected) in examples) {
        val edits = TextLogic.correctionDiff(source, corrected)
        check(TextLogic.apply(source, edits) == corrected) { "Combined edits changed the model result" }
        val prefix = "Earlier sentence.\n"
        check(TextLogic.apply(prefix + source, TextLogic.correctionDiff(source, corrected, prefix.length)) == prefix + corrected)
    }
    val source = "Hey why you are ignoring my messages?"
    val edits = TextLogic.correctionDiff(source, "Hey, why are you ignoring my messages?")
    check(edits.size == 2) { "Punctuation and word order should remain independent" }
    val order = edits.single { it.replacement.contains("you") }
    check(TextLogic.apply(source, listOf(order)) == "Hey why are you ignoring my messages?")
    val embedded = "Can you tell me where is the nearest bus stop?"
    val movement = TextLogic.correctionDiff(embedded, "Can you tell me where the nearest bus stop is?")
    check(movement.size == 1) { "The embedded-question verb move must be accepted together" }
    val independent = TextLogic.correctionDiff("i have recieved a mesage", "I have received a message")
    check(independent.size == 3) { "Independent spelling and capitalization edits should remain separate" }
    val split = Edit(0, 6, "me sage", category = "Typo", engine = "harper", ruleId = "SplitWords", priority = 31)
    val spelling = Edit(0, 6, "message", category = "Spelling", alternatives = listOf("menage"),
        engine = "harper", ruleId = "SpellCheck", priority = 63)
    for (findings in listOf(listOf(split, spelling), listOf(spelling, split))) {
        val merged = IssueStore.merge("mesage", findings).single()
        check(merged.replacement == "message" && "me sage" in merged.alternatives)
    }
    val supportedSplit = IssueStore.merge("mesage", listOf(split, spelling, split.copy(engine = "t5"))).single()
    check(supportedSplit.replacement == "me sage" && "message" in supportedSplit.alternatives)
    val unsupported = IssueStore.merge("mesage", listOf(split, spelling, spelling.copy(engine = "t5", replacement = "massage", alternatives = emptyList()))).single()
    check(unsupported.replacement == "message") { "Context must agree with a basic candidate before promoting it" }
    check(IssueStore.merge("mesage", listOf(split)).single().replacement == "me sage")
    val store = IssueStore()
    store.update("I recieved the address.")
    store.publish(CheckUpdate(listOf(Edit(2, 10, "received"))))
    store.update("I received the address.")
    check(store.current.issues.isEmpty()) { "Editing the sentence must clear its stale finding" }
    println("PASS: atomic word order, independent corrections, Unicode, spelling ranking and stale findings")
}

/** Engine-independent checks retained from the retired ONNX test harness. */
private fun sharedTextLogicRegressions() {
    for ((old, corrected) in listOf(
        "😀 She go to school." to "😀 She goes to school.",
        "Line one.\nShe go." to "Line one.\nShe goes.",
        "I likes tea." to "I like tea.", "a b c" to "a c",
        "I like tea" to "I really like tea"
    )) check(TextLogic.apply(old, TextLogic.diff(old, corrected)) == corrected) { "UTF-16 diff failed" }
    check(TextLogic.mapPosition(8, listOf(Edit(4, 6, "goes"))) == 10)
    check(TextLogic.mapPosition(5, listOf(Edit(4, 6, "goes"))) == 8)
    for (type in listOf(0x81, 0x91, 0xe1, 0x12)) check(TextLogic.sensitive(type))
    for (type in listOf(1, 0x21, 0x101)) check(!TextLogic.sensitive(type))
    check(runCatching { TextLogic.apply("😀 hi", listOf(Edit(1, 2, "x"))) }.isFailure)
    check(runCatching { TextLogic.apply("abcdef", listOf(Edit(0, 3, "x"), Edit(2, 5, "y"))) }.isFailure)
    val phrase = "This is a testss. What happening?"
    val insertion = phrase.indexOf("happening")
    check(TextLogic.highlightRange(phrase, Edit(insertion, insertion, "is ")) ==
        (insertion to insertion + "happening".length))
    println("PASS: shared text diffs, cursor mapping, password filters and insertion highlighting")
}
