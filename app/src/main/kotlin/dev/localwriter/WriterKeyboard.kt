package dev.localwriter

import android.content.Context
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.*

/** A small native keyboard. Checking is an explicit action, never a keystroke upload. */
class WriterKeyboard : InputMethodService() {
    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var keys: LinearLayout
    private lateinit var status: TextView
    private lateinit var checkButton: Button
    private lateinit var rewriteButton: Button
    private lateinit var toneButton: Button
    private lateinit var undoButton: Button
    private lateinit var panel: AssistantPanel
    private lateinit var panelScroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private var repeatDelete: Runnable? = null
    private var shifted = false
    private var symbols = false
    private var emoji = false
    private var fieldRevision = 0L
    private var applying = false
    private var expectedSelection: Pair<Int, Int>? = null
    private var snapshot: Snapshot? = null
    private var undo: KeyboardUndo? = null
    private data class Window(val text: String, val offset: Int, val selectionStart: Int, val selectionEnd: Int)
    private data class Snapshot(val text: String, val start: Int, val window: Window, val revision: Long, val pkg: String, val fieldId: Int)
    private data class KeyboardUndo(val before: String, val after: String, val start: Int, val revision: Long, val pkg: String, val fieldId: Int)

    override fun onCreate() { super.onCreate(); prefs = Prefs(this) }
    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        root = Ui.column(this, 6).apply { setBackgroundColor(Ui.PALE) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.navigationBars())
            view.setPadding(Ui.dp(this, 6) + bars.left, Ui.dp(this, 6), Ui.dp(this, 6) + bars.right, Ui.dp(this, 6) + bars.bottom)
            insets
        }
        status = Ui.text(this, "Local Writer · tap Check when ready", 12f, Ui.MUTED)
        root.addView(status)
        checkButton = Ui.button(this, "Check", true) { openAssistant("check") }
        rewriteButton = Ui.button(this, "Rewrite") { openAssistant("rewrite") }
        toneButton = Ui.button(this, "Tone") { openAssistant("tone") }
        undoButton = Ui.button(this, "Undo") { undoLast() }.apply { isEnabled = false }
        Ui.addRow(root, checkButton, rewriteButton, toneButton, undoButton)
        panel = AssistantPanel(this, ::applyEdits, { hideAssistant() }, selection = {
            snapshot?.let { if (it.window.selectionStart != it.window.selectionEnd) 0 to it.text.length else null }
        })
        panelScroll = Ui.scroll(this, panel).apply { visibility = View.GONE }
        root.addView(panelScroll, LinearLayout.LayoutParams(-1, Ui.dp(this, 185)))
        keys = Ui.column(this, 0)
        root.addView(keys)
        renderKeys()
        refreshAvailability()
        return root
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        fieldRevision++
        snapshot = null
        undo = null
        expectedSelection = null
        stopDelete()
        if (::panel.isInitialized) { panel.dispose(); panelScroll.visibility = View.GONE; undoButton.isEnabled = false }
        symbols = info?.inputType?.and(InputType.TYPE_MASK_CLASS) in listOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME)
        emoji = false
        shifted = runCatching { (currentInputConnection?.getCursorCapsMode(info?.inputType ?: 0) ?: 0) != 0 }.getOrDefault(false)
        if (::keys.isInitialized) { renderKeys(); refreshAvailability() }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (::keys.isInitialized) { renderKeys(); refreshAvailability() }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopDelete()
        hideAssistant()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        stopDelete()
        fieldRevision++
        snapshot = null
        undo = null
        if (::panel.isInitialized) { panel.dispose(); panelScroll.visibility = View.GONE; undoButton.isEnabled = false }
        super.onFinishInput()
    }

    override fun onDestroy() { stopDelete(); if (::panel.isInitialized) panel.dispose(); super.onDestroy() }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (expectedSelection == (newSelStart to newSelEnd)) { expectedSelection = null; return }
        if (!applying && snapshot != null && (oldSelStart != newSelStart || oldSelEnd != newSelEnd)) {
            hideAssistant()
            status.text = "Field changed · tap Check to refresh"
        }
    }

    private fun allowed(): Boolean {
        val info = currentInputEditorInfo ?: return false
        return TextLogic.editableTextType(info.inputType) && prefs.allowed(info.packageName ?: "")
    }

    private fun refreshAvailability() {
        if (!::status.isInitialized) return
        val enabled = allowed()
        checkButton.isEnabled = enabled
        rewriteButton.isEnabled = enabled
        toneButton.isEnabled = enabled
        val info = currentInputEditorInfo
        status.text = when {
            info == null -> "Local Writer"
            TextLogic.sensitive(info.inputType) -> "Private field · writing checks disabled"
            !TextLogic.editableTextType(info.inputType) -> "Writing checks are available in text fields"
            !prefs.enabled -> "Assistant off · typing is available"
            prefs.pauseUntil > System.currentTimeMillis() -> "Assistant paused · typing is available"
            info.packageName in prefs.blocked -> "This app is excluded · typing is available"
            else -> "Local Writer · tap Check when ready"
        }
    }

    /** Prefer a complete extracted window. Never infer an absolute offset from a partial result. */
    private fun readWindow(connection: InputConnection): Window? {
        val extracted = runCatching {
            connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 12000; hintMaxLines = 200 }, 0)
        }.getOrNull()
        if (extracted != null && extracted.partialStartOffset < 0 && extracted.startOffset >= 0) {
            val text = extracted.text?.toString()
            if (text != null && text.length <= 12000 && extracted.selectionStart in 0..text.length && extracted.selectionEnd in 0..text.length) {
                return Window(text, extracted.startOffset, extracted.selectionStart, extracted.selectionEnd)
            }
        }
        val surrounding = runCatching { connection.getSurroundingText(6000, 6000, 0) }.getOrNull() ?: return null
        val value = surrounding.text.toString()
        if (surrounding.offset < 0 || surrounding.selectionStart !in 0..value.length || surrounding.selectionEnd !in 0..value.length) return null
        return Window(value, surrounding.offset, surrounding.selectionStart, surrounding.selectionEnd)
    }

    private fun capture(): Snapshot? {
        if (!allowed()) return null
        val info = currentInputEditorInfo ?: return null
        val connection = currentInputConnection ?: return null
        val window = readWindow(connection) ?: return null
        val from = minOf(window.selectionStart, window.selectionEnd)
        val to = maxOf(window.selectionStart, window.selectionEnd)
        val selected = from != to
        return Snapshot(if (selected) window.text.substring(from, to) else window.text,
            window.offset + if (selected) from else 0, window, fieldRevision, info.packageName ?: "", info.fieldId)
    }

    private fun openAssistant(action: String) {
        refreshAvailability()
        if (!allowed()) return
        val captured = capture()
        if (captured == null) { Ui.toast(this, "This field does not expose a safe text range. Use the editor or select and share text."); return }
        if (captured.text.isBlank()) { Ui.toast(this, "Write or select some text first"); return }
        snapshot = captured
        panelScroll.visibility = View.VISIBLE
        val selected = captured.window.selectionStart != captured.window.selectionEnd
        status.text = if (selected) "Checking selected text · on this device" else "Checking available text around the cursor · on this device"
        panel.update(captured.text, true)
        when (action) {
            "rewrite" -> panel.rewrite("Improve")
            "tone" -> panel.tone()
        }
    }

    private fun sameField(revision: Long, pkg: String, fieldId: Int): Boolean {
        val info = currentInputEditorInfo ?: return false
        return revision == fieldRevision && pkg == info.packageName && fieldId == info.fieldId && allowed()
    }

    private fun applyEdits(source: String, edits: List<Edit>): Boolean {
        val captured = snapshot ?: return false
        if (source != captured.text || !sameField(captured.revision, captured.pkg, captured.fieldId)) return false
        val connection = currentInputConnection ?: return false
        val current = readWindow(connection) ?: return false
        // Also guard cursor/selection: a delayed result cannot target a newer editing context.
        if (current != captured.window) return false
        val corrected = runCatching { TextLogic.apply(source, edits) }.getOrNull() ?: return false
        val oldStart = current.offset + current.selectionStart
        val oldEnd = current.offset + current.selectionEnd
        applying = true
        var success = true
        runCatching {
            connection.beginBatchEdit()
            connection.finishComposingText()
            for (edit in edits.sortedByDescending { it.begin }) {
                if (!connection.setSelection(captured.start + edit.begin, captured.start + edit.end) || !connection.commitText(edit.replacement, 1)) {
                    success = false; break
                }
            }
            if (success) {
                val globalEdits = edits.map { Edit(captured.start + it.begin, captured.start + it.end, it.replacement) }
                val start = TextLogic.mapPosition(oldStart, globalEdits)
                val end = TextLogic.mapPosition(oldEnd, globalEdits)
                expectedSelection = start to end
                success = connection.setSelection(start, end)
            }
        }.onFailure { success = false }
        runCatching { connection.endBatchEdit() }
        applying = false
        if (!success) {
            snapshot = null
            expectedSelection = null
            undo = null
            undoButton.isEnabled = false
            status.text = "The app did not accept this edit · check again"
            return false
        }
        undo = KeyboardUndo(source, corrected, captured.start, fieldRevision, captured.pkg, captured.fieldId)
        undoButton.isEnabled = true
        val newWindow = readWindow(connection)
        snapshot = newWindow?.let { captured.copy(text = corrected, window = it) }
        status.text = "Suggestion applied · Undo is available"
        return true
    }

    private fun undoLast() {
        val previous = undo ?: return
        if (!sameField(previous.revision, previous.pkg, previous.fieldId)) { clearUndo(); return }
        val connection = currentInputConnection ?: return
        val window = readWindow(connection) ?: return
        val localStart = previous.start - window.offset
        val localEnd = localStart + previous.after.length
        if (localStart < 0 || localEnd > window.text.length || window.text.substring(localStart, localEnd) != previous.after) {
            clearUndo(); Ui.toast(this, "The text changed; this edit can no longer be undone"); return
        }
        hideAssistant()
        applying = true
        val result = runCatching {
            connection.beginBatchEdit()
            connection.finishComposingText()
            connection.setSelection(previous.start, previous.start + previous.after.length) && connection.commitText(previous.before, 1)
        }.getOrDefault(false)
        runCatching { connection.endBatchEdit() }
        applying = false
        clearUndo()
        status.text = if (result) "Edit undone · tap Check to review" else "This app did not accept Undo"
    }

    private fun clearUndo() { undo = null; if (::undoButton.isInitialized) undoButton.isEnabled = false }
    private fun hideAssistant() {
        snapshot = null
        expectedSelection = null
        if (::panel.isInitialized) { panel.dispose(); panelScroll.visibility = View.GONE }
    }

    private fun renderKeys() {
        keys.removeAllViews()
        val rows = when {
            emoji -> listOf(listOf("😀", "😊", "🙂", "😂", "😍", "🥳"), listOf("👍", "❤️", "✨", "🙏", "🎉", "✅"), listOf("👋", "🤔", "😅", "😎", "🌟", "💚"))
            symbols -> listOf("1234567890".map(Char::toString), "@#₹%&*()-".map(Char::toString), listOf("!", "\"", "'", ":", ";", "/", "?"))
            else -> listOf("qwertyuiop".map(Char::toString), "asdfghjkl".map(Char::toString), "zxcvbnm".map(Char::toString))
        }
        rows.forEachIndexed { index, characters ->
            val row = Ui.row(this)
            if (index == 2) row.addView(key(if (symbols || emoji) "ABC" else if (shifted) "⇧" else "↑", 1.25f) {
                if (symbols || emoji) { symbols = false; emoji = false } else shifted = !shifted
                renderKeys()
            })
            characters.forEach { character ->
                val value = if (!symbols && !emoji && shifted) character.uppercase() else character
                row.addView(key(value) { type(value) })
            }
            if (index == 2) row.addView(deleteKey())
            keys.addView(row)
        }
        val bottom = Ui.row(this)
        bottom.addView(key(if (symbols) "ABC" else "?123", 1.3f) { symbols = !symbols; emoji = false; renderKeys() })
        bottom.addView(key("🌐", 1f) {
            if (!switchToNextInputMethod(false)) getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        })
        bottom.addView(key(if (emoji) "ABC" else "☺", 1f) { emoji = !emoji; symbols = false; renderKeys() })
        bottom.addView(key("space", 4f) { type(" ") })
        bottom.addView(key(".", 1f) { type(".") })
        bottom.addView(key(enterLabel(), 1.65f, true) { enter() })
        keys.addView(bottom)
    }

    private fun key(label: String, weight: Float = 1f, primary: Boolean = false, action: () -> Unit): Button = Ui.button(this, label, primary) {
        feedback(); action()
    }.apply {
        textSize = if (label.length > 2) 12f else 20f
        setPadding(0, 0, 0, 0)
        contentDescription = when (label) { "⇧", "↑" -> "Shift"; "🌐" -> "Switch keyboard"; "☺" -> "Emoji"; else -> label }
        layoutParams = LinearLayout.LayoutParams(0, Ui.dp(this@WriterKeyboard, 46), weight).apply {
            setMargins(Ui.dp(this@WriterKeyboard, 2), Ui.dp(this@WriterKeyboard, 3), Ui.dp(this@WriterKeyboard, 2), Ui.dp(this@WriterKeyboard, 3))
        }
        background = Ui.background(if (primary) Ui.GREEN else Color.WHITE, Ui.dp(this@WriterKeyboard, 7))
    }

    private fun deleteKey(): Button = key("⌫", 1.25f) {}.apply {
        contentDescription = "Backspace"
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stopDelete(); feedback(); delete()
                    val repeat = object : Runnable { override fun run() { delete(); handler.postDelayed(this, 65) } }
                    repeatDelete = repeat; handler.postDelayed(repeat, 380); view.isPressed = true; true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { stopDelete(); view.isPressed = false; true }
                else -> true
            }
        }
        // Accessibility activation still invokes a normal click.
        setOnClickListener { feedback(); delete() }
    }

    private fun stopDelete() { repeatDelete?.let(handler::removeCallbacks); repeatDelete = null }
    private fun feedback() { if (prefs.vibrate && ::root.isInitialized) root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }

    private fun type(value: String) {
        hideAssistant()
        clearUndo()
        val success = runCatching { currentInputConnection?.commitText(value, 1) == true }.getOrDefault(false)
        if (!success) Ui.toast(this, "This field is unavailable")
        if (shifted && !symbols && !emoji && value.any(Char::isLetter)) { shifted = false; renderKeys() }
        refreshAvailability()
    }

    private fun delete() {
        hideAssistant(); clearUndo()
        val connection = currentInputConnection ?: return
        runCatching {
            if (!connection.getSelectedText(0).isNullOrEmpty()) connection.commitText("", 1)
            else connection.deleteSurroundingTextInCodePoints(1, 0)
        }
    }

    private fun enterLabel(): String {
        val options = currentInputEditorInfo?.imeOptions ?: 0
        if (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return "↵"
        return when (options and EditorInfo.IME_MASK_ACTION) {
        EditorInfo.IME_ACTION_DONE -> "Done"
        EditorInfo.IME_ACTION_GO -> "Go"
        EditorInfo.IME_ACTION_NEXT -> "Next"
        EditorInfo.IME_ACTION_SEARCH -> "Search"
        EditorInfo.IME_ACTION_SEND -> "Send"
        else -> "↵"
        }
    }

    private fun enter() {
        hideAssistant(); clearUndo()
        val info = currentInputEditorInfo ?: return
        val connection = currentInputConnection ?: return
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        runCatching {
            if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 && action !in listOf(EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_UNSPECIFIED))
                connection.performEditorAction(action)
            else connection.commitText("\n", 1)
        }
    }
}
