package dev.localwriter

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.InputMethod
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.PowerManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/** No keyboard switch is required: Android 13 exposes a separate accessibility input connection. */
class WriterAccessibilityService : AccessibilityService() {
    private data class EditorKey(val epoch: Long, val pkg: String, val fieldId: Int, val fieldName: String?, val inputType: Int)
    // AccessibilityNodeInfo's equality/hash include the window and virtual descendant source ID.
    private data class NodeKey(val window: Int, val source: Int, val viewId: String?, val uniqueId: String?)
    private data class Snapshot(
        val text: String, val offset: Int, val pkg: String, val editor: EditorKey?,
        val node: NodeKey?, val revision: Long, val connection: Boolean,
        val wholeNodeText: String? = null, val selectionStart: Int = -1, val selectionEnd: Int = -1
    )
    private data class Undo(val source: Snapshot, val replacement: String, val original: String, var confirmed: Boolean = false)
    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private lateinit var analysis: AnalysisSession
    private var accessibilityIme: InputMethod? = null
    private var editor: EditorKey? = null
    private var epoch = 0L
    private var revision = 0L
    private var snapshot: Snapshot? = null
    private var retainedNode: AccessibilityNodeInfo? = null
    private var undo: Undo? = null
    private var dismissedEditor: EditorKey? = null
    private var dismissedNode: NodeKey? = null
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var sheet: LinearLayout? = null
    private var sheetParams: WindowManager.LayoutParams? = null
    private var panel: AssistantPanel? = null
    private var panelSnapshot: Snapshot? = null
    private var suggestionCount = 0
    private var connected = false
    private var receiverRegistered = false
    private var dragX = -1
    private var dragY = -1
    private val refresh = Runnable { refreshField() }
    private val resume = Runnable { scheduleRefresh() }
    private val confirmUndo = Runnable {
        val last = undo
        if (last != null) {
            val read = readSnapshot()
            read?.second?.recycle()
            if (read == null || !sameField(last.source, read.first) || !contains(read.first, last.source.offset, last.replacement)) undo = null
            else last.confirmed = true
        }
    }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        Engine.main.post { clearField(); scheduleRefresh() }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) clearField() else scheduleRefresh()
        }
    }

    override fun onCreateInputMethod(): InputMethod = object : InputMethod(this) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
            super.onStartInput(attribute, restarting)
            clearField()
            editor = EditorKey(++epoch, attribute.packageName.orEmpty(), attribute.fieldId, attribute.fieldName, attribute.inputType)
            dismissedEditor = null; dismissedNode = null
            scheduleRefresh()
        }
        override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
            super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
            invalidateResults()
            scheduleRefresh()
        }
        override fun onFinishInput() {
            super.onFinishInput()
            editor = null; epoch++
            dismissedEditor = null; dismissedNode = null
            clearField()
        }
    }.also { accessibilityIme = it }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this); wm = getSystemService(WindowManager::class.java); analysis = AnalysisSession(this)
        connected = true
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        prefs.store.registerOnSharedPreferenceChangeListener(preferenceListener)
        registerReceiver(screenReceiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) }, Context.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        scheduleRefresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!connected || event == null) return
        val pkg = event.packageName?.toString()
        // Our nonfocusable overlays produce content events, but never become an editor.
        if (pkg == packageName && event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) invalidateResults()
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && undo?.confirmed == true) undo = null
                if (undo?.confirmed == false) {
                    Engine.main.removeCallbacks(confirmUndo); Engine.main.postDelayed(confirmUndo, 350)
                }
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != snapshot?.pkg) {
                    closeSheet(); undo = null
                }
                scheduleRefresh()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> scheduleRefresh(180)
        }
    }

    private fun scheduleRefresh(delay: Long = 100) {
        if (!connected) return
        Engine.main.removeCallbacks(refresh)
        Engine.main.postDelayed(refresh, delay)
    }

    private fun unlocked(): Boolean = getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun allowed(pkg: String): Boolean = pkg.isNotBlank() && pkg != packageName &&
        pkg != "com.android.systemui" && prefs.allowed(pkg) && unlocked()

    private fun invalidateResults() {
        revision++
        if (connected) analysis.cancel()
    }

    private fun nodeKey(node: AccessibilityNodeInfo) = NodeKey(node.windowId, node.hashCode(), node.viewIdResourceName, node.uniqueId)
    private fun validNode(node: AccessibilityNodeInfo): Boolean = node.isEditable && node.isFocused &&
        !node.isPassword && !TextLogic.sensitive(node.inputType) &&
        (node.inputType == 0 || TextLogic.editableTextType(node.inputType))

    /** Fast focus lookup first. A bounded traversal only supports incomplete virtual focus trees. */
    private fun focusedNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()?.let { focused ->
            if (validNode(focused)) return focused
            focused.recycle()
        }
        val pending = ArrayDeque<AccessibilityNodeInfo>(); pending.add(AccessibilityNodeInfo.obtain(root))
        var visited = 0
        try {
            while (pending.isNotEmpty() && visited++ < 160) {
                val node = pending.removeFirst()
                if (validNode(node)) return node
                for (i in 0 until minOf(node.childCount, 24)) {
                    if (pending.size >= 160) break
                    runCatching { node.getChild(i) }.getOrNull()?.let { pending.add(it) }
                }
                node.recycle()
            }
        } finally { pending.forEach { it.recycle() } }
        return null
    }

    private fun activeRoot(): AccessibilityNodeInfo? = runCatching { rootInActiveWindow }.getOrNull()

    private fun readSnapshot(): Pair<Snapshot, AccessibilityNodeInfo?>? {
        if (!connected || !unlocked()) return null
        val root = activeRoot() ?: return null
        var focus: AccessibilityNodeInfo? = null
        try {
            val pkg = root.packageName?.toString().orEmpty()
            if (!allowed(pkg)) return null
            focus = focusedNode(root)
            // A password node vetoes reading even when EditorInfo is inaccurate.
            val rawFocus = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            val sensitive = rawFocus?.let { it.isPassword || TextLogic.sensitive(it.inputType) } == true
            rawFocus?.recycle()
            if (sensitive) return null
            val key = focus?.let(::nodeKey)
            val currentEditor = editor
            if (currentEditor?.pkg == pkg && !TextLogic.editableTextType(currentEditor.inputType)) return null
            if (currentEditor != null && currentEditor.pkg == pkg && TextLogic.editableTextType(currentEditor.inputType)) {
                val connection = accessibilityIme?.currentInputConnection
                val surrounding = runCatching { connection?.getSurroundingText(CONTEXT_LENGTH, CONTEXT_LENGTH, 0) }.getOrNull()
                if (surrounding != null && surrounding.offset >= 0 && surrounding.text.length <= MAX_TEXT) {
                    val text = surrounding.text.toString()
                    if (surrounding.selectionStart in 0..text.length && surrounding.selectionEnd in 0..text.length) {
                        val result = Snapshot(text, surrounding.offset, pkg, currentEditor, key, revision, true,
                            selectionStart = surrounding.offset + surrounding.selectionStart, selectionEnd = surrounding.offset + surrounding.selectionEnd)
                        val kept = focus; focus = null
                        return result to kept
                    }
                }
            }
            val node = focus ?: return null
            val whole = node.text?.toString().orEmpty()
            if (whole.length > MAX_NODE_TEXT) return null
            val cursor = node.textSelectionStart.coerceIn(0, whole.length)
            var start = (cursor - CONTEXT_LENGTH).coerceAtLeast(0)
            if (start > 0 && start < whole.length && whole[start].isLowSurrogate()) start--
            var end = (start + MAX_TEXT).coerceAtMost(whole.length)
            if (end < whole.length && end > start && whole[end].isLowSurrogate()) end--
            val result = Snapshot(whole.substring(start, end), start, pkg, currentEditor?.takeIf { it.pkg == pkg }, key,
                revision, false, whole, node.textSelectionStart, node.textSelectionEnd)
            focus = null
            return result to node
        } catch (_: RuntimeException) { return null }
        finally { focus?.recycle(); root.recycle() }
    }

    private fun refreshField() {
        if (!connected) return
        val read = readSnapshot()
        if (read == null) { clearField(); return }
        val (new, node) = read
        if ((new.editor != null && new.editor == dismissedEditor) || (new.node != null && new.node == dismissedNode)) {
            node?.recycle(); clearField(); return
        }
        val old = snapshot
        if (old != null && (!sameField(old, new) || (retainedNode != null && node != null && retainedNode != node))) {
            closeSheet(); undo = null
        }
        val alteredRevision = old?.revision != revision
        val changed = old == null || !sameField(old, new) || old.text != new.text || old.offset != new.offset
        if (changed && old != null) invalidateResults()
        snapshot = new.copy(revision = revision)
        retainedNode?.recycle(); retainedNode = node
        undo?.let { last ->
            val expectedWhole = last.source.wholeNodeText?.let { whole ->
                whole.substring(0, last.source.offset) + last.replacement + whole.substring(last.source.offset + last.original.length)
            }
            if (!sameField(last.source, new) || !contains(new, last.source.offset, last.replacement) ||
                (expectedWhole != null && new.wholeNodeText != expectedWhole)) undo = null
        }
        positionOverlays()
        showBubble()
        panel?.let { assistant ->
            analysis.cancel()
            if (changed || panelSnapshot?.revision != revision) {
                panelSnapshot = snapshot
                assistant.update(new.text, immediate = true)
            }
            return
        }
        if (!changed && !alteredRevision) return
        suggestionCount = 0; updateBubble()
        if (new.text.isBlank()) { analysis.cancel(); return }
        val expected = snapshot!!
        analysis.check(expected.text) { result ->
            if (snapshot != expected || !allowed(expected.pkg)) return@check
            suggestionCount = result.getOrDefault(emptyList()).size
            updateBubble()
        }
    }

    private fun sameField(a: Snapshot, b: Snapshot): Boolean = a.pkg == b.pkg && a.editor == b.editor &&
        a.node == b.node && a.connection == b.connection

    private fun contains(s: Snapshot, absoluteStart: Int, expected: String): Boolean {
        val source = s.wholeNodeText ?: s.text
        val relative = absoluteStart - if (s.wholeNodeText != null) 0 else s.offset
        if (relative >= 0 && relative.toLong() + expected.length <= source.length &&
            source.regionMatches(relative, expected, 0, expected.length)) return true
        if (!s.connection || editor != s.editor || expected.length > MAX_TEXT * 2) return false
        // Undo may be outside the new cursor-centered analysis window after a correction.
        val wider = runCatching { accessibilityIme?.currentInputConnection?.getSurroundingText(MAX_TEXT * 2, MAX_TEXT * 2, 0) }.getOrNull() ?: return false
        val position = absoluteStart - wider.offset
        return wider.offset >= 0 && position >= 0 && position.toLong() + expected.length <= wider.text.length &&
            wider.text.toString().regionMatches(position, expected, 0, expected.length)
    }

    private fun apply(source: String, edits: List<Edit>): Boolean {
        val expected = panelSnapshot ?: return false
        if (expected.text != source || !allowed(expected.pkg)) return false
        val replacement = runCatching { TextLogic.apply(source, edits) }.getOrNull() ?: return false
        if (replacement == source) return true
        val current = readSnapshot() ?: return false
        val currentNode = current.second
        val equalNode = (retainedNode == null && currentNode == null) || retainedNode == currentNode
        currentNode?.recycle()
        if (!equalNode || expected.revision != revision || !sameField(expected, current.first) ||
            expected.offset != current.first.offset || source != current.first.text ||
            expected.wholeNodeText != current.first.wholeNodeText) {
            scheduleRefresh(0); return false
        }
        val restore = if (expected.selectionStart in expected.offset..(expected.offset + source.length) &&
            expected.selectionEnd in expected.offset..(expected.offset + source.length)) {
            (expected.offset + TextLogic.mapPosition(expected.selectionStart - expected.offset, edits)) to
                (expected.offset + TextLogic.mapPosition(expected.selectionEnd - expected.offset, edits))
        } else null
        if (!replaceRange(current.first, expected.offset, source, replacement, restore)) return false
        undo = Undo(expected, replacement, source)
        Engine.main.removeCallbacks(confirmUndo); Engine.main.postDelayed(confirmUndo, 350)
        invalidateResults()
        scheduleRefresh(60)
        return true
    }

    private fun replaceRange(current: Snapshot, start: Int, before: String, replacement: String, restore: Pair<Int, Int>? = null): Boolean {
        if (!allowed(current.pkg) || !contains(current, start, before)) return false
        return runCatching {
            if (current.connection) {
                val connection = accessibilityIme?.currentInputConnection ?: return false
                if (editor != current.editor) return false
                connection.setSelection(start, start + before.length)
                // Selection is not a success-returning API. Verify it and the exact source before committing.
                val selected = connection.getSurroundingText(CONTEXT_LENGTH, CONTEXT_LENGTH, 0) ?: return false
                if (selected.offset < 0 || selected.offset + selected.selectionStart != start ||
                    selected.offset + selected.selectionEnd != start + before.length) return false
                val selection = selected.text.toString()
                val relative = start - selected.offset
                if (relative < 0 || relative + before.length > selection.length ||
                    selection.substring(relative, relative + before.length) != before) return false
                connection.commitText(replacement, 1, null)
                restore?.let { connection.setSelection(it.first, it.second) }
                // InputConnection is asynchronous; events/next read confirm the resulting content.
                true
            } else {
                val node = retainedNode ?: return false
                if (!node.refresh() || !validNode(node) || nodeKey(node) != current.node ||
                    node.packageName?.toString() != current.pkg || node.text?.toString().orEmpty() != current.wholeNodeText) return false
                val whole = current.wholeNodeText ?: return false
                val updated = whole.substring(0, start) + replacement + whole.substring(start + before.length)
                val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
                })
                if (result) node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    val cursor = start + replacement.length
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, restore?.first ?: cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, restore?.second ?: cursor)
                })
                result
            }
        }.getOrDefault(false)
    }

    private fun undoLast() {
        val last = undo ?: return
        val read = readSnapshot()
        if (read == null) { undo = null; return }
        val current = read.first
        val currentNode = read.second
        if (!((retainedNode == null && currentNode == null) || retainedNode == currentNode)) {
            currentNode?.recycle(); undo = null; Ui.toast(this, "The field changed. Undo is no longer available."); return
        }
        currentNode?.let { retainedNode?.recycle(); retainedNode = it }
        val expectedWhole = last.source.wholeNodeText?.let { whole ->
            whole.substring(0, last.source.offset) + last.replacement + whole.substring(last.source.offset + last.original.length)
        }
        if (!sameField(last.source, current) || (expectedWhole != null && current.wholeNodeText != expectedWhole) ||
            !contains(current, last.source.offset, last.replacement) ||
            !replaceRange(current, last.source.offset, last.replacement, last.original)) {
            undo = null; Ui.toast(this, "The field changed. Undo is no longer available."); return
        }
        undo = null; invalidateResults(); scheduleRefresh(60)
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.LEFT; title = "Local Writer" }

    private fun showBubble() {
        if (bubble != null) { updateBubble(); return }
        val view = Ui.text(this, "W", 17f, android.graphics.Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = Ui.background(Ui.GREEN, Ui.dp(this@WriterAccessibilityService, 28))
            setPadding(0, 0, 0, 0); contentDescription = "Open local writing assistant"
            isFocusable = false
        }
        val p = overlayParams(Ui.dp(this, 58), Ui.dp(this, 48))
        bubble = view; bubbleParams = p
        positionOverlays()
        view.setOnTouchListener(object : View.OnTouchListener {
            var x = 0f; var y = 0f; var initialX = 0; var initialY = 0; var moved = false
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { x = event.rawX; y = event.rawY; initialX = p.x; initialY = p.y; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(event.rawX - x) + abs(event.rawY - y) > Ui.dp(this@WriterAccessibilityService, 8)) moved = true
                        if (moved) {
                            dragX = initialX + (event.rawX - x).toInt(); dragY = initialY + (event.rawY - y).toInt()
                            positionOverlays()
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) { v.performClick(); openSheet() }
                    MotionEvent.ACTION_CANCEL -> moved = false
                }
                return true
            }
        })
        if (runCatching { wm.addView(view, p) }.isFailure) { bubble = null; bubbleParams = null }
        updateBubble()
    }

    private fun updateBubble() {
        bubble?.text = if (suggestionCount == 0) "W" else "W $suggestionCount"
        bubble?.contentDescription = "Local writing assistant, $suggestionCount suggestions"
    }

    private fun openSheet() {
        if (sheet != null) { closeSheet(); return }
        val current = snapshot ?: return
        analysis.cancel()
        val root = Ui.column(this, 4).apply { background = Ui.background(); isFocusable = false }
        val controls = Ui.row(this)
        controls.addView(Ui.button(this, "Undo") { undoLast() })
        controls.addView(Ui.button(this, "Dismiss") {
            dismissedEditor = snapshot?.editor; dismissedNode = snapshot?.node; clearField()
        })
        controls.addView(Ui.button(this, "Pause 15m") {
            prefs.pauseUntil = System.currentTimeMillis() + 15 * 60 * 1000
            clearField(); Engine.main.removeCallbacks(resume); Engine.main.postDelayed(resume, 15 * 60 * 1000L)
        })
        root.addView(controls)
        root.addView(Ui.button(this, "Disable in this app") { snapshot?.pkg?.let { prefs.block(it, true) }; clearField() })
        panelSnapshot = current
        val assistant = AssistantPanel(this, ::apply, ::closeSheet, count = { count -> suggestionCount = count; updateBubble() }, selection = {
            val s = panelSnapshot
            if (s == null || s.selectionStart < s.offset || s.selectionEnd < s.offset ||
                s.selectionStart > s.offset + s.text.length || s.selectionEnd > s.offset + s.text.length) null
            else (s.selectionStart - s.offset) to (s.selectionEnd - s.offset)
        })
        val scroll = ScrollView(this).apply { isFillViewport = false; addView(assistant); isFocusable = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        panel = assistant; sheet = root
        sheetParams = overlayParams(-1, Ui.dp(this, 360))
        positionOverlays()
        if (runCatching { wm.addView(root, sheetParams) }.isFailure) { closeSheet(); return }
        assistant.update(current.text, true)
    }

    /** Keep touch targets above both the IME window and system/cutout insets. */
    private fun positionOverlays() {
        if (!connected) return
        val bounds = wm.currentWindowMetrics.bounds
        val insets = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val left = insets.left + Ui.dp(this, 8); val right = bounds.width() - insets.right - Ui.dp(this, 8)
        val top = insets.top + Ui.dp(this, 8)
        var bottom = bounds.height() - insets.bottom - Ui.dp(this, 8)
        runCatching {
            for (window in windows) if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                val rect = Rect(); window.getBoundsInScreen(rect)
                if (!rect.isEmpty && rect.top > top) bottom = minOf(bottom, rect.top - Ui.dp(this, 8))
            }
        }
        val imeInset = wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.ime()).bottom
        if (imeInset > 0) bottom = minOf(bottom, bounds.height() - imeInset - Ui.dp(this, 8))
        bubbleParams?.let { p ->
            p.x = (if (dragX < 0) right - p.width else dragX).coerceIn(left, maxOf(left, right - p.width))
            p.y = (if (dragY < 0) bottom - p.height else dragY).coerceIn(top, maxOf(top, bottom - p.height))
            bubble?.takeIf { it.isAttachedToWindow }?.let { runCatching { wm.updateViewLayout(it, p) } }
        }
        sheetParams?.let { p ->
            p.width = maxOf(Ui.dp(this, 120), right - left)
            p.height = minOf(Ui.dp(this, 430), maxOf(Ui.dp(this, 80), bottom - top))
            p.x = left; p.y = maxOf(top, bottom - p.height)
            sheet?.takeIf { it.isAttachedToWindow }?.let { runCatching { wm.updateViewLayout(it, p) } }
        }
    }

    private fun closeSheet() {
        panel?.dispose(); panel = null; panelSnapshot = null
        sheet?.let { if (connected) runCatching { wm.removeViewImmediate(it) } }
        sheet = null; sheetParams = null
    }
    private fun clearField() {
        invalidateResults(); Engine.main.removeCallbacks(refresh); Engine.main.removeCallbacks(confirmUndo)
        closeSheet()
        bubble?.let { if (connected) runCatching { wm.removeViewImmediate(it) } }
        bubble = null; bubbleParams = null; suggestionCount = 0
        snapshot = null; retainedNode?.recycle(); retainedNode = null; undo = null
    }
    override fun onInterrupt() { editor = null; epoch++; clearField() }
    override fun onDestroy() {
        clearField(); Engine.main.removeCallbacks(resume)
        if (connected) prefs.store.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        if (receiverRegistered) runCatching { unregisterReceiver(screenReceiver) }
        receiverRegistered = false; connected = false; accessibilityIme = null; editor = null
        super.onDestroy()
    }
    companion object {
        private const val CONTEXT_LENGTH = 2048
        private const val MAX_TEXT = 4096
        private const val MAX_NODE_TEXT = 50_000
    }
}
