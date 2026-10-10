package dev.localwriter

import java.io.File
import java.util.concurrent.CancellationException

fun main(args: Array<String>) {
    val examples = listOf(
        "She go to school every day." to "She goes to school every day.",
        "He don't like swimming because the water are cold." to "He doesn't like swimming because the water is cold.",
        "I am agree with you." to "I agree with you.",
        "The the dog barked loudly." to "The dog barked loudly.",
        "She have been working here since five years." to "She has been working here for five years."
    )
    GrammarEngine(File(args[0])).use { engine ->
        for ((input, expected) in examples) {
            val edits = engine.check(input)
            val actual = TextLogic.apply(input, edits)
            check(actual == expected) { "Expected: $expected; actual: $actual" }
            println("PASS: $input → $actual")
        }
        for (text in listOf("Hello, world.", "This is a well-written sentence.", "Hello  world.\n\nThank you."))
            check(TextLogic.apply(text,engine.check(text)) == text) { "Correct text changed: $text" }
        val long = (1..40).joinToString("\n\n") { "  She go to school every day.  " }
        val corrected = TextLogic.apply(long,engine.check(long))
        check(corrected == long.replace("She go ","She goes ")) { "Long document lost content or formatting" }
        var cancelled = false
        try { engine.check("She go to school.") { false } } catch (_: CancellationException) { cancelled = true }
        check(cancelled)
    }
    for ((old,new) in listOf("😀 She go to school." to "😀 She goes to school.", "Line one.\nShe go." to "Line one.\nShe goes.", "I likes tea." to "I like tea.", "a b c" to "a c", "I like tea" to "I really like tea"))
        check(TextLogic.apply(old,TextLogic.diff(old,new)) == new) { "UTF-16 diff failed" }
    check(TextLogic.mapPosition(8,listOf(Edit(4,6,"goes"))) == 10)
    check(TextLogic.mapPosition(5,listOf(Edit(4,6,"goes"))) == 8)
    for (type in listOf(0x81,0x91,0xe1,0x12)) check(TextLogic.sensitive(type))
    for (type in listOf(1,0x21,0x101)) check(!TextLogic.sensitive(type))
    check(runCatching { TextLogic.apply("😀 hi",listOf(Edit(1,2,"x"))) }.isFailure)
    check(runCatching { TextLogic.apply("abcdef",listOf(Edit(0,3,"x"),Edit(2,5,"y"))) }.isFailure)
    println("PASS: correct-text preservation, long documents, cancellation, UTF-16 boundaries, cursor mapping and password filters")
}
