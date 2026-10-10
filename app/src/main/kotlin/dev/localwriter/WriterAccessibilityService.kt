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
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
    private enum class ReadFailure { TRANSIENT, REJECTED }
    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private lateinit var analysis: AnalysisSession
    private lateinit var highlights: TextHighlightOverlay
    private var checkedSnapshot: Snapshot? = null
    private var fieldEdits = emptyList<Edit>()
    private var accessibilityIme: InputMethod? = null
    private var editor: EditorKey? = null
    private var epoch = 0L
    private var revision = 0L
    private var snapshot: Snapshot? = null
    private var retainedNode: AccessibilityNodeInfo? = null
    private var undo: Undo? = null
    private var dismissedEditor: EditorKey? = null
    private var dismissedNode: NodeKey? = null
    private var bubble: WritingWidget? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var sheet: LinearLayout? = null
    private var sheetParams: WindowManager.LayoutParams? = null
    private var compactSheet = false
    private var sheetAnchorX = -1
    private var sheetAnchorY = -1
    private var sheetOnRight = true
    private var panel: AssistantPanel? = null
    private var panelSnapshot: Snapshot? = null
    private var suggestionCount = 0
    private var checkState = WritingCheckState.IDLE
    private var connected = false
    private var readFailure = ReadFailure.TRANSIENT
    private var transientReadStarted = 0L
    private var fieldReadPending = false
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
            val preservePanel = sheet != null && snapshot?.pkg == attribute.packageName &&
                !TextLogic.sensitive(attribute.inputType) &&
                (attribute.inputType == 0 || TextLogic.editableTextType(attribute.inputType))
            if (preservePanel) { invalidateResults(); fieldReadPending = true }
            else clearField()
            editor = EditorKey(++epoch, attribute.packageName.orEmpty(), attribute.fieldId, attribute.fieldName, attribute.inputType)
            dismissedEditor = null; dismissedNode = null
            scheduleRefresh()
        }
        override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
            super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
            if (sheet == null) invalidateResults()
            scheduleRefresh()
        }
        override fun onFinishInput() {
            super.onFinishInput()
            editor = null; epoch++
            dismissedEditor = null; dismissedNode = null
            if (sheet != null) {
                // The sheet owns a captured editor. Losing its IME connection during
                // overlay creation is not proof the user switched fields or apps.
                invalidateResults(); fieldReadPending = true; scheduleRefresh()
            } else clearField()
        }
    }.also { accessibilityIme = it }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this); wm = getSystemService(WindowManager::class.java); analysis = AnalysisSession(this)
        highlights = TextHighlightOverlay(this, wm)
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
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) highlights.clear()
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                    (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED && sheet == null) ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) invalidateResults()
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && undo?.confirmed == true) undo = null
                if (undo?.confirmed == false) {
                    Engine.main.removeCallbacks(confirmUndo); Engine.main.postDelayed(confirmUndo, 350)
                }
                // An accessibility overlay creates its own window-state event. A package-name
                // mismatch (including our package or the IME) is not a foreground-field change.
                // refreshField closes the sheet only after checking the real focused app/editor.
                scheduleRefresh()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> { highlights.clear(); scheduleRefresh(180) }
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
        checkedSnapshot = null; fieldEdits = emptyList()
        if (sheet == null) {
            suggestionCount = 0; checkState = WritingCheckState.CHECKING; updateBubble()
        }
        if (connected) { analysis.cancel(); highlights.clear() }
    }

    private fun nodeKey(node: AccessibilityNodeInfo) = NodeKey(node.windowId, node.hashCode(), node.viewIdResourceName, node.uniqueId)
    private fun validNode(node: AccessibilityNodeInfo): Boolean = node.isEditable && node.isFocused && node.isVisibleToUser &&
        !node.isPassword && !(Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) && !TextLogic.sensitive(node.inputType) &&
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

    private fun activeRoot(): AccessibilityNodeInfo? = runCatching {
        val active = rootInActiveWindow
        val activeWindow = active?.window
        val auxiliary = activeWindow?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
            activeWindow?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        activeWindow?.recycle()
        if (active != null && !auxiliary) return@runCatching active
        active?.recycle()
        // Touching a nonfocusable assistant/IME window must not lose the app's editor.
        // A focused system dialog is not bypassed: only a genuinely focused app is eligible.
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }?.root
    }.getOrNull()

    private fun readSnapshot(): Pair<Snapshot, AccessibilityNodeInfo?>? {
        readFailure = ReadFailure.TRANSIENT
        if (!connected || !unlocked()) { readFailure = ReadFailure.REJECTED; return null }
        val root = activeRoot() ?: return null
        var focus: AccessibilityNodeInfo? = null
        try {
            val pkg = root.packageName?.toString().orEmpty()
            if (!allowed(pkg)) { readFailure = ReadFailure.REJECTED; return null }
            focus = focusedNode(root)
            // A password node vetoes reading even when EditorInfo is inaccurate.
            val rawFocus = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            val sensitive = rawFocus?.let { it.isPassword || (Build.VERSION.SDK_INT >= 34 && it.isAccessibilityDataSensitive) || TextLogic.sensitive(it.inputType) } == true
            rawFocus?.recycle()
            if (sensitive) { readFailure = ReadFailure.REJECTED; return null }
            val key = focus?.let(::nodeKey)
            // Do not replace a known field identity with a temporarily missing node
            // while Android rebuilds the window tree for our newly opened sheet.
            if (sheet != null && snapshot?.node != null && focus == null) return null
            val currentEditor = editor
            if (currentEditor?.pkg == pkg && currentEditor.inputType != 0 && !TextLogic.editableTextType(currentEditor.inputType)) {
                readFailure = ReadFailure.REJECTED; return null
            }
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
            if (whole.length > MAX_NODE_TEXT) { readFailure = ReadFailure.REJECTED; return null }
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
        // A field can remain focused after Back hides the keyboard. Do not leave
        // a launcher on that screen. An already-open correction sheet keeps its
        // captured editor across transient IME changes until it is dismissed.
        if (sheet == null && !typingVisible()) { clearField(); return }
        val read = readSnapshot()
        if (read == null) {
            if (retryTransientRead()) return
            clearField(); return
        }
        transientReadStarted = 0L; fieldReadPending = false
        val (new, node) = read
        if ((new.editor != null && new.editor == dismissedEditor) || (new.node != null && new.node == dismissedNode)) {
            node?.recycle(); clearField(); return
        }
        val old = snapshot
        if (old != null && (!sameTarget(old, new) || (retainedNode != null && node != null && retainedNode != node))) {
            closeSheet(); undo = null
        }
        val alteredRevision = old?.revision != revision
        val changed = old == null || !sameTarget(old, new) || old.text != new.text || old.offset != new.offset
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
        redrawHighlights()
        panel?.let { assistant ->
            analysis.cancel()
            val previousPanel = panelSnapshot
            panelSnapshot = snapshot
            if (changed || previousPanel?.text != new.text || previousPanel?.offset != new.offset)
                assistant.update(new.text, immediate = true)
            return
        }
        if (!changed && !alteredRevision) return
        suggestionCount = 0
        checkState = if (new.text.isBlank()) WritingCheckState.IDLE else WritingCheckState.CHECKING
        updateBubble()
        if (new.text.isBlank()) { analysis.cancel(); return }
        val expected = snapshot!!
        analysis.check(expected.text) { result ->
            if (snapshot != expected || !allowed(expected.pkg)) return@check
            fieldEdits = result.getOrDefault(emptyList()); checkedSnapshot = expected
            suggestionCount = fieldEdits.size
            checkState = if (result.isSuccess) WritingCheckState.READY else WritingCheckState.UNAVAILABLE
            updateBubble()
            redrawHighlights()
        }
    }

    /** Opening an overlay can briefly invalidate Android's cached focus/window tree. */
    private fun retryTransientRead(): Boolean {
        val current = snapshot ?: return false
        if (sheet == null || readFailure != ReadFailure.TRANSIENT || !allowed(current.pkg)) return false
        // Never retain the sheet across a confirmed app change or a focused system surface.
        val safeForeground = runCatching {
            for (window in windows) {
                if (!window.isFocused && !window.isActive) continue
                if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM) return@runCatching false
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = window.root ?: continue
                try {
                    if (root.packageName?.toString() != current.pkg) return@runCatching false
                    val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (focus != null) {
                        try {
                            if (focus.isPassword || TextLogic.sensitive(focus.inputType) ||
                                (Build.VERSION.SDK_INT >= 34 && focus.isAccessibilityDataSensitive)) return@runCatching false
                        } finally { focus.recycle() }
                    }
                } finally { root.recycle() }
            }
            true
        }.getOrDefault(false)
        if (!safeForeground) return false
        val now = android.os.SystemClock.uptimeMillis()
        if (transientReadStarted == 0L) transientReadStarted = now
        if (now - transientReadStarted >= 400L) return false
        fieldReadPending = true
        highlights.clear()
        scheduleRefresh(80)
        return true
    }

    private fun sameField(a: Snapshot, b: Snapshot): Boolean = a.pkg == b.pkg && a.editor == b.editor &&
        a.node == b.node && a.connection == b.connection

    private fun sameTarget(a: Snapshot, b: Snapshot): Boolean {
        if (a.pkg != b.pkg) return false
        // Node source identity survives an IME restart/backend fallback. Connection
        // authority is checked separately against a freshly read source before edits.
        if (a.node != null && b.node != null) {
            if (a.editor != null && b.editor != null &&
                (a.editor.fieldId != b.editor.fieldId || a.editor.fieldName != b.editor.fieldName ||
                    a.editor.inputType != b.editor.inputType)) return false
            return a.node == b.node
        }
        return a.editor != null && a.editor == b.editor && a.node == b.node
    }

    /** Offsets are UTF-16 offsets into the entire editor, as required by the character-location API. */
    private fun redrawHighlights() {
        if (!connected) return
        val expected = checkedSnapshot
        val current = snapshot
        val node = retainedNode
        if (sheet != null || expected == null || current == null || node == null || fieldEdits.isEmpty() ||
            expected.revision != revision || !sameField(expected, current) ||
            expected.text != current.text || expected.offset != current.offset || !allowed(current.pkg)) {
            highlights.clear(); return
        }
        fun exactNode(): Boolean {
            if (!validNode(node) || nodeKey(node) != current.node || node.packageName?.toString() != current.pkg) return false
            val whole = node.text?.toString() ?: return false
            return whole.length <= MAX_NODE_TEXT && current.offset >= 0 &&
                current.offset.toLong() + current.text.length <= whole.length &&
                whole.regionMatches(current.offset, current.text, 0, current.text.length)
        }
        val rectangles = ArrayList<RectF>()
        val clip = Rect(wm.currentWindowMetrics.bounds)
        val insets = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        clip.inset(insets.left, insets.top, insets.right, insets.bottom)
        try {
            if (!node.refresh() || !exactNode() ||
                AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY !in node.availableExtraData) {
                highlights.clear(); return
            }
            val fieldBounds = Rect(); node.getBoundsInScreen(fieldBounds)
            if (!clip.intersect(fieldBounds)) { highlights.clear(); return }
            for (window in windows) {
                val bounds = Rect(); window.getBoundsInScreen(bounds)
                if (window.id == node.windowId && !clip.intersect(bounds)) { highlights.clear(); return }
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && !bounds.isEmpty && bounds.top > clip.top)
                    clip.bottom = minOf(clip.bottom, bounds.top)
            }
            val ime = wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.ime()).bottom
            if (ime > 0) clip.bottom = minOf(clip.bottom, wm.currentWindowMetrics.bounds.bottom - ime)
            if (clip.isEmpty) { highlights.clear(); return }
            // Bound IPC and memory. Apps may omit rectangles for offscreen characters.
            var requested = 0; var requests = 0
            for (edit in fieldEdits) {
                if (requests >= 16 || requested >= 512) break
                val range = TextLogic.highlightRange(current.text, edit) ?: continue
                val length = minOf(range.second - range.first, 64, 512 - requested)
                if (length <= 0) continue
                val start = current.offset + range.first
                if (!node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, Bundle().apply {
                    putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start)
                    putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
                }) || !exactNode()) { highlights.clear(); return }
                requests++; requested += length
                val locations = node.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,
                    RectF::class.java) ?: continue
                for (location in locations.take(length)) {
                    if (location == null || !location.left.isFinite() || !location.right.isFinite() ||
                        !location.top.isFinite() || !location.bottom.isFinite() || location.isEmpty) continue
                    // Preserve the returned baseline; Canvas clipping hides offscreen marks
                    // instead of moving an underline to the edge of a clipped character box.
                    if (RectF.intersects(location, RectF(clip))) rectangles.add(RectF(location))
                }
            }
            if (snapshot != current || current.revision != revision || !exactNode()) { highlights.clear(); return }
            highlights.show(rectangles, clip)
        } catch (_: RuntimeException) { highlights.clear() }
    }

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
        if (fieldReadPending) return false
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
        if (fieldReadPending) return
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
        if (sheet != null || !typingVisible()) { hideBubble(); return }
        if (bubble != null) { updateBubble(); return }
        val view = WritingWidget(this)
        val p = overlayParams(Ui.dp(this, 48), Ui.dp(this, 48))
        bubble = view; bubbleParams = p
        positionOverlays()
        view.setOnClickListener { openSheet() }
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
                    MotionEvent.ACTION_UP -> if (!moved) v.performClick()
                    MotionEvent.ACTION_CANCEL -> moved = false
                }
                return true
            }
        })
        if (runCatching { wm.addView(view, p) }.isFailure) { bubble = null; bubbleParams = null }
        updateBubble()
    }

    private fun updateBubble() {
        bubble?.update(suggestionCount, checkState)
    }

    private fun typingVisible(): Boolean {
        if (!connected || !unlocked()) return false
        if (runCatching {
            windows.any { window ->
                if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) false
                else Rect().let { bounds -> window.getBoundsInScreen(bounds); !bounds.isEmpty }
            }
        }.getOrDefault(false)) return true
        if (wm.currentWindowMetrics.windowInsets.isVisible(WindowInsets.Type.ime())) return true
        // A physical keyboard has no on-screen IME window. A live editor still
        // defines a typing session when the hardware keyboard is available.
        val config = resources.configuration
        return editor != null && config.keyboard == Configuration.KEYBOARD_QWERTY &&
            config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    }

    private fun hideBubble() {
        bubble?.let { if (connected) runCatching { wm.removeViewImmediate(it) } }
        bubble = null; bubbleParams = null
    }

    private fun openSheet() {
        if (sheet != null) { closeSheet(); return }
        // Refresh before opening: a bubble click may race the next text/focus event or its debounce.
        refreshField()
        val current = snapshot ?: return
        val knownCorrections = checkedSnapshot?.takeIf {
            checkState == WritingCheckState.READY && it.revision == revision && sameField(it, current) &&
                it.text == current.text && it.offset == current.offset
        }?.let { fieldEdits.toList() }
        analysis.cancel()
        highlights.clear()
        compactSheet = true
        sheetAnchorX = bubbleParams?.x ?: dragX
        sheetAnchorY = bubbleParams?.y ?: dragY
        sheetOnRight = sheetAnchorX < 0 || sheetAnchorX > wm.currentWindowMetrics.bounds.width() / 2
        val radius = Ui.dp(this,28).toFloat()
        fun sheetBackground() = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadii = floatArrayOf(radius,radius,radius,radius,0f,0f,0f,0f)
        }
        val root = Ui.column(this, 0).apply {
            background = null
            isFocusable = false
        }
        val handleArea = android.widget.FrameLayout(this).apply {
            visibility = View.GONE
            addView(View(this@WriterAccessibilityService).apply {
                background = Ui.background(Color.rgb(46,46,46),Ui.dp(this@WriterAccessibilityService,2))
            }, android.widget.FrameLayout.LayoutParams(Ui.dp(this@WriterAccessibilityService,32),Ui.dp(this@WriterAccessibilityService,4),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = Ui.dp(this@WriterAccessibilityService,8)
            })
            contentDescription = "Drag down to close suggestions"
            setOnTouchListener(object : View.OnTouchListener {
                var start = 0f
                override fun onTouch(view: View, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> start = event.rawY
                        MotionEvent.ACTION_UP -> if (event.rawY - start > Ui.dp(this@WriterAccessibilityService,32)) closeSheet()
                    }
                    return true
                }
            })
        }
        root.addView(handleArea,LinearLayout.LayoutParams(-1,Ui.dp(this,24)))
        panelSnapshot = current
        val assistant = AssistantPanel(this, ::apply, ::closeSheet, highlight = { edits ->
            checkedSnapshot = panelSnapshot; fieldEdits = edits
            redrawHighlights()
        }, count = { count -> suggestionCount = count; updateBubble() }, selection = {
            val s = panelSnapshot
            if (s == null || s.selectionStart < s.offset || s.selectionEnd < s.offset ||
                s.selectionStart > s.offset + s.text.length || s.selectionEnd > s.offset + s.text.length) null
            else (s.selectionStart - s.offset) to (s.selectionEnd - s.offset)
        }, checkState = { state -> checkState = state; updateBubble() }, options = listOf(
            "Undo last correction" to { undoLast() },
            "Dismiss assistant" to {
                dismissedEditor = snapshot?.editor; dismissedNode = snapshot?.node; clearField()
            },
            "Pause for 15 minutes" to {
                prefs.pauseUntil = System.currentTimeMillis() + 15 * 60 * 1000
                clearField(); Engine.main.removeCallbacks(resume)
                Engine.main.postDelayed(resume, 15 * 60 * 1000L)
            },
            "Disable in this app" to { snapshot?.pkg?.let { prefs.block(it, true) }; clearField() }
        ), overlayStyle = true, modeChanged = { compact ->
            compactSheet = compact; handleArea.visibility = if (compact) View.GONE else View.VISIBLE
            root.background = if (compact) null else sheetBackground()
            positionOverlays()
        }, inlineRight = sheetOnRight, inlineDrag = ::bindInlineDrag)
        root.addView(assistant, LinearLayout.LayoutParams(-1, 0, 1f))
        panel = assistant; sheet = root
        sheetParams = overlayParams(-1, Ui.dp(this, 360))
        positionOverlays()
        if (runCatching { wm.addView(root, sheetParams) }.isFailure) { closeSheet(); return }
        hideBubble()
        assistant.update(current.text, true, knownCorrections)
    }

    private fun bindInlineDrag(view: View) {
        view.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0f; var startY = 0f; var anchorX = 0; var anchorY = 0; var moved = false
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val p = sheetParams ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX; startY = event.rawY; moved = false
                        anchorX = p.x + if (sheetOnRight) p.width-Ui.dp(this@WriterAccessibilityService,48) else 0
                        anchorY = p.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX-startX; val dy = event.rawY-startY
                        if (abs(dx)+abs(dy) > Ui.dp(this@WriterAccessibilityService,8)) moved = true
                        if (moved) {
                            sheetAnchorX = anchorX+dx.toInt(); sheetAnchorY = anchorY+dy.toInt()
                            positionOverlays()
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            dragX = p.x + if (sheetOnRight) p.width-Ui.dp(this@WriterAccessibilityService,48) else 0
                            dragY = p.y
                        } else v.performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> moved = false
                }
                return true
            }
        })
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
            if (compactSheet) {
                p.width = minOf(right-left,bounds.width()/2+Ui.dp(this,80))
                p.height = minOf(Ui.dp(this,56),maxOf(Ui.dp(this,32),bottom-top))
                val anchor = if (sheetAnchorX < 0) right-Ui.dp(this,48) else sheetAnchorX
                val x = if (sheetOnRight) anchor+Ui.dp(this,48)-p.width else anchor
                p.x = x.coerceIn(left,maxOf(left,right-p.width))
                p.y = (if (sheetAnchorY < 0) bottom-p.height else sheetAnchorY).coerceIn(top,maxOf(top,bottom-p.height))
            } else {
                p.width = maxOf(Ui.dp(this,120),bounds.width()-insets.left-insets.right)
                val sheetBottom = bottom+Ui.dp(this,8)
                p.height = maxOf(Ui.dp(this,80),sheetBottom-top)
                p.x = insets.left; p.y = maxOf(top,sheetBottom-p.height)
            }
            sheet?.takeIf { it.isAttachedToWindow }?.let { runCatching { wm.updateViewLayout(it, p) } }
        }
    }

    private fun closeSheet() {
        val current = snapshot
        val previous = panelSnapshot
        val cached = if (current != null && previous != null && sameTarget(previous, current) &&
            previous.text == current.text && previous.offset == current.offset) panel?.currentCorrections(current.text) else null
        val wasOpen = sheet != null
        panel?.dispose(); panel = null; panelSnapshot = null
        sheet?.let { if (connected) runCatching { wm.removeViewImmediate(it) } }
        sheet = null; sheetParams = null; compactSheet = false
        if (connected && wasOpen && current != null) {
            if (cached != null) {
                checkedSnapshot = current.copy(revision = revision); fieldEdits = cached
                suggestionCount = cached.size; checkState = WritingCheckState.READY; updateBubble()
            } else { invalidateResults(); scheduleRefresh() }
            showBubble()
        }
        if (connected) redrawHighlights()
    }
    private fun clearField() {
        transientReadStarted = 0L; fieldReadPending = false
        invalidateResults(); Engine.main.removeCallbacks(refresh); Engine.main.removeCallbacks(confirmUndo)
        snapshot = null; retainedNode?.recycle(); retainedNode = null; undo = null
        closeSheet()
        hideBubble(); suggestionCount = 0; checkState = WritingCheckState.IDLE
    }
    override fun onInterrupt() { editor = null; epoch++; clearField() }
    override fun onDestroy() {
        clearField(); Engine.main.removeCallbacks(resume)
        if (connected) {
            highlights.dispose()
            prefs.store.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        }
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
