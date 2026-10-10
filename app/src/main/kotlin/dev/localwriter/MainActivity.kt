package dev.localwriter

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.CharacterStyle
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.widget.*

/** Drafts live only in the activity and its rotation state. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout
    private lateinit var editorScreen: LinearLayout
    private lateinit var editor: EditText
    private lateinit var panel: AssistantPanel
    private lateinit var words: TextView
    private lateinit var undoButton: Button
    private lateinit var returnButton: Button
    private val tabs = mutableListOf<Button>()
    private var activeTab = 0
    private var changing = false
    private var processText = false
    private var processReadOnly = false
    private var undo: Undo? = null
    private data class Undo(val before: String, val after: String, val start: Int, val end: Int)
    private class CorrectionUnderline : CharacterStyle() {
        override fun updateDrawState(paint: TextPaint) {
            paint.isUnderlineText = true
            paint.underlineColor = Color.rgb(207, 60, 60)
            paint.underlineThickness = 2f
            paint.bgColor = Color.rgb(255, 232, 232)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        prefs = Prefs(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = Ui.PALE
        window.navigationBarColor = Ui.PALE
        val root = Ui.column(this, 0).apply { setBackgroundColor(Ui.PALE) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val header = Ui.column(this, 20)
        header.addView(Ui.text(this, "Local Writer", 26f, bold = true))
        header.addView(Ui.text(this, "Your words. Your device.", 13f, Ui.MUTED))
        root.addView(header)
        val navigation = Ui.row(this).apply { setPadding(Ui.dp(this@MainActivity, 16), 0, Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 10)) }
        listOf("Home", "Editor", "Settings").forEachIndexed { index, label ->
            val button = Ui.button(this, label) { showTab(index) }
            tabs += button
            navigation.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(Ui.dp(this@MainActivity, 3), 0, Ui.dp(this@MainActivity, 3), 0) })
        }
        root.addView(navigation)
        content = Ui.column(this, 0)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        createEditor()
        val initial = state?.getString("draft") ?: importedText(intent) ?: "She go to school every day."
        processText = state?.getBoolean("processText") ?: (intent.action == Intent.ACTION_PROCESS_TEXT)
        processReadOnly = state?.getBoolean("processReadOnly") ?: intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        editor.setText(initial)
        val start = (state?.getInt("selectionStart") ?: initial.length).coerceIn(0, initial.length)
        val end = (state?.getInt("selectionEnd") ?: start).coerceIn(0, initial.length)
        editor.setSelection(minOf(start, end), maxOf(start, end))
        returnButton.visibility = if (processText && !processReadOnly) View.VISIBLE else View.GONE
        showTab(state?.getInt("tab") ?: if (importedText(intent) != null) 1 else 0)
    }

    private fun importedText(value: Intent): String? = when (value.action) {
        Intent.ACTION_SEND -> value.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        Intent.ACTION_PROCESS_TEXT -> value.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
    }

    override fun onNewIntent(value: Intent) {
        super.onNewIntent(value)
        intent = value
        importedText(value)?.let {
            undo = null
            processText = value.action == Intent.ACTION_PROCESS_TEXT
            processReadOnly = value.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            returnButton.visibility = if (processText && !processReadOnly) View.VISIBLE else View.GONE
            editor.setText(it)
            editor.setSelection(it.length)
            showTab(1)
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("draft", editor.text.toString())
        out.putInt("selectionStart", editor.selectionStart)
        out.putInt("selectionEnd", editor.selectionEnd)
        out.putInt("tab", activeTab)
        out.putBoolean("processText", processText)
        out.putBoolean("processReadOnly", processReadOnly)
        super.onSaveInstanceState(out)
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) showTab(activeTab)
    }

    override fun onDestroy() {
        if (::panel.isInitialized) panel.dispose()
        super.onDestroy()
    }

    private fun showTab(index: Int) {
        activeTab = index.coerceIn(0, 2)
        tabs.forEachIndexed { i, button ->
            button.setTextColor(if (i == activeTab) Color.WHITE else Ui.GREEN)
            button.background = Ui.background(if (i == activeTab) Ui.GREEN else Color.WHITE, Ui.dp(this, 10))
        }
        content.removeAllViews()
        when (activeTab) {
            0 -> content.addView(Ui.scroll(this, home()))
            1 -> {
                (editorScreen.parent as? android.view.ViewGroup)?.removeView(editorScreen)
                content.addView(editorScreen)
                refreshAnalysis()
            }
            2 -> content.addView(Ui.scroll(this, settings()))
        }
    }

    private fun home(): LinearLayout {
        val page = Ui.column(this, 16)
        val hero = Ui.card(page)
        hero.addView(Ui.text(this, "Write with confidence", 25f, bold = true))
        hero.addView(Ui.text(this, "Grammar, spelling, rewrites and tone — processed locally, with no account required.", 16f, Ui.MUTED))
        hero.addView(Ui.button(this, "Open your editor", true) { showTab(1) })
        val status = Ui.card(page)
        status.addView(Ui.text(this, "Use it where you write", 19f, bold = true))
        status.addView(Ui.text(this, "${if (accessibilityEnabled()) "✓" else "○"} Writing assistant: ${if (accessibilityEnabled()) "enabled" else "not enabled"}", 15f))
        status.addView(Ui.text(this, "${if (accessibilityEnabled()) "✓" else "○"} Floating assistant: ${if (accessibilityEnabled()) "available" else "enable the writing assistant"}", 15f))
        status.addView(Ui.text(this, "Allow “Use Local Writer assistant” in Accessibility settings. The small circular widget appears while typing; tap it to review corrections. Passwords and excluded apps are skipped.", 13f, Ui.MUTED))
        status.addView(Ui.button(this, "Set up writing assistant", true) { accessibilitySettings() })
        val keyboard = Ui.card(page)
        keyboard.addView(Ui.text(this, "Optional writing keyboard", 19f, bold = true))
        keyboard.addView(Ui.text(this, "Type normally, then tap Check to review suggestions in the keyboard.", 14f, Ui.MUTED))
        Ui.addRow(keyboard, Ui.button(this, "Enable keyboard") { launch(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }, Ui.button(this, "Choose keyboard") { getSystemService(InputMethodManager::class.java).showInputMethodPicker() })
        val local = Ui.card(page)
        local.addView(Ui.text(this, if (!prefs.enabled) "Assistant is turned off" else if (prefs.pauseUntil > System.currentTimeMillis()) "Assistant is paused" else "Ready to help", 18f, bold = true))
        local.addView(Ui.text(this, "Bundled models work offline. Drafts stay in memory; only your settings and dictionary are saved.", 13f, Ui.MUTED))
        return page
    }

    private fun createEditor() {
        editorScreen = Ui.column(this, 0)
        val page = Ui.column(this, 16)
        val card = Ui.card(page)
        card.addView(Ui.text(this, "Your draft", 20f, bold = true))
        words = Ui.text(this, "", 12f, Ui.MUTED)
        card.addView(words)
        editor = EditText(this).apply {
            hint = "Start writing here…"
            textSize = 19f
            setTextColor(Ui.INK)
            setHintTextColor(Ui.MUTED)
            gravity = android.view.Gravity.TOP
            minLines = 6
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 12))
            background = Ui.background(Ui.PALE, Ui.dp(this@MainActivity, 10))
            // Android should not persist a draft through the view hierarchy.
            isSaveEnabled = false
        }
        card.addView(editor, LinearLayout.LayoutParams(-1, -2))
        undoButton = Ui.button(this, "Undo") { undoLast() }.apply { isEnabled = false }
        Ui.addRow(card, Ui.button(this, "Copy") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Draft", editor.text.toString()))
            Ui.toast(this, "Draft copied")
        }, Ui.button(this, "Share") {
            launch(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, editor.text.toString()) }, "Share your draft"))
        }, undoButton)
        returnButton = Ui.button(this, "Return edited text", true) {
            setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, editor.text.toString()))
            finish()
        }
        card.addView(returnButton)
        panel = AssistantPanel(this, ::applyEdits, { panel.visibility = View.GONE }, ::highlight, { count -> updateWords(count) }, selection = { editor.selectionStart to editor.selectionEnd })
        page.addView(Ui.button(this, "Check draft", true) {
            if (prefs.allowed(packageName)) { panel.visibility = View.VISIBLE; panel.update(editor.text.toString(), true) }
            else Ui.toast(this, "Turn on or resume the assistant in Settings")
        })
        page.addView(panel)
        val custom = Ui.card(page)
        custom.addView(Ui.text(this, "Rewrite your way", 16f, bold = true))
        val instruction = EditText(this).apply { hint = "Example: make this clearer and warmer"; textSize = 14f; maxLines = 3; isSaveEnabled = false }
        custom.addView(instruction)
        custom.addView(Ui.button(this, "Generate rewrite") {
            if (!prefs.allowed(packageName)) { Ui.toast(this, "Turn on or resume the assistant in Settings"); return@button }
            if (instruction.text.isBlank()) { Ui.toast(this, "Add a rewrite instruction first"); return@button }
            panel.visibility = View.VISIBLE
            panel.update(editor.text.toString())
            panel.rewrite("Custom", instruction.text.toString())
        })
        editorScreen.addView(Ui.scroll(this, page), LinearLayout.LayoutParams(-1, -1))
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (changing) return
                undoButton.isEnabled = undo?.after == s.toString()
                updateWords(0)
                refreshAnalysis()
            }
        })
    }

    private fun refreshAnalysis() {
        if (prefs.allowed(packageName)) panel.update(editor.text.toString())
        else { panel.dispose(); highlight(emptyList()); updateWords(0) }
    }

    private fun updateWords(count: Int) {
        val total = Regex("\\S+").findAll(editor.text.toString()).count()
        words.text = "$total word${if (total == 1) "" else "s"} · $count suggestion${if (count == 1) "" else "s"} · local processing"
    }

    private fun highlight(edits: List<Edit>) {
        val value = editor.text
        value.getSpans(0, value.length, CorrectionUnderline::class.java).forEach(value::removeSpan)
        edits.forEach { e ->
            if (e.begin in 0..e.end && e.end <= value.length) {
                val range = TextLogic.highlightRange(value.toString(), e) ?: return@forEach
                val (start, end) = range
                if (start < end) value.setSpan(CorrectionUnderline(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun applyEdits(source: String, edits: List<Edit>): Boolean {
        if (editor.text.toString() != source || !prefs.allowed(packageName)) return false
        val corrected = runCatching { TextLogic.apply(source, edits) }.getOrNull() ?: return false
        val start = editor.selectionStart.coerceAtLeast(0)
        val end = editor.selectionEnd.coerceAtLeast(0)
        undo = Undo(source, corrected, start, end)
        changing = true
        try {
            edits.sortedByDescending { it.begin }.forEach { editor.text.replace(it.begin, it.end, it.replacement) }
            editor.setSelection(mapCursor(start, edits).coerceIn(0, corrected.length), mapCursor(end, edits).coerceIn(0, corrected.length))
        } finally { changing = false }
        undoButton.isEnabled = true
        updateWords(0)
        return true
    }

    private fun mapCursor(position: Int, edits: List<Edit>): Int {
        var delta = 0
        for (edit in edits.sortedBy { it.begin }) {
            if (position < edit.begin) break
            if (position <= edit.end) return edit.begin + delta + edit.replacement.length
            delta += edit.replacement.length - (edit.end - edit.begin)
        }
        return position + delta
    }

    private fun undoLast() {
        val previous = undo ?: return
        if (editor.text.toString() != previous.after) { undo = null; undoButton.isEnabled = false; Ui.toast(this, "Draft changed; this edit can no longer be undone"); return }
        changing = true
        try {
            editor.text.replace(0, editor.length(), previous.before)
            editor.setSelection(previous.start.coerceIn(0, editor.length()), previous.end.coerceIn(0, editor.length()))
        } finally { changing = false }
        undo = null
        undoButton.isEnabled = false
        refreshAnalysis()
    }

    private fun settings(): LinearLayout {
        val page = Ui.column(this, 16)
        val controls = Ui.card(page)
        controls.addView(Ui.text(this, "Writing preferences", 20f, bold = true))
        controls.addView(Switch(this).apply {
            text = "Enable writing assistance"; isChecked = prefs.enabled
            setOnCheckedChangeListener { _, enabled -> prefs.enabled = enabled; refreshAnalysis() }
        })
        controls.addView(Ui.text(this, if (prefs.pauseUntil > System.currentTimeMillis()) "Paused until ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(prefs.pauseUntil))}" else "Assistant is available", 13f, Ui.MUTED))
        Ui.addRow(controls, Ui.button(this, "Pause 30 minutes") { prefs.pauseUntil = System.currentTimeMillis() + 30 * 60_000; refreshAnalysis(); showTab(2) }, Ui.button(this, "Resume") { prefs.pauseUntil = 0; refreshAnalysis(); showTab(2) })
        addChoice(controls, "English dialect", listOf("American", "British", "Indian", "Australian", "Canadian"), prefs.dialect) { prefs.dialect = it; if (prefs.allowed(packageName)) panel.update(editor.text.toString(), true) }
        val checkingModes = mapOf("Contextual" to "T5", "Off" to "Off")
        addChoice(controls, "Contextual checking", checkingModes.keys.toList(), checkingModes.entries.firstOrNull { it.value == prefs.contextModel }?.key ?: "Contextual") {
            prefs.contextModel = checkingModes.getValue(it); if (prefs.allowed(packageName)) panel.update(editor.text.toString(), true)
        }
        controls.addView(Ui.text(this, "Contextual checks run after a typing pause. Basic spelling and grammar checks remain available with contextual checking off.", 13f, Ui.MUTED))
        controls.addView(Switch(this).apply {
            text = "Include style advice"; isChecked = prefs.styleAdvice
            setOnCheckedChangeListener { _, checked -> prefs.styleAdvice = checked; if (prefs.allowed(packageName)) panel.update(editor.text.toString(), true) }
        })
        addChoice(controls, "Writing goal", listOf("General", "Academic", "Business", "Creative", "Casual"), prefs.goal) { prefs.goal = it }
        controls.addView(Switch(this).apply { text = "Keyboard vibration"; isChecked = prefs.vibrate; setOnCheckedChangeListener { _, checked -> prefs.vibrate = checked } })
        val dictionary = Ui.card(page)
        dictionary.addView(Ui.text(this, "Personal dictionary", 19f, bold = true))
        dictionary.addView(Ui.text(this, "Keep names and words you use from appearing as spelling suggestions.", 13f, Ui.MUTED))
        val input = EditText(this).apply { hint = "Add a word"; isSingleLine = true; isSaveEnabled = false }
        dictionary.addView(input)
        dictionary.addView(Ui.button(this, "Add word") {
            val value = input.text.toString().trim()
            if (!value.matches(Regex("[\\p{L}\\p{M}’'-]+"))) { Ui.toast(this, "Enter one word using letters"); return@button }
            prefs.dictionary(value, true); showTab(2)
        })
        if (prefs.dictionary.isEmpty()) dictionary.addView(Ui.text(this, "No saved words yet", 13f, Ui.MUTED))
        prefs.dictionary.sorted().forEach { word ->
            val row = Ui.row(this)
            row.addView(Ui.text(this, word), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Ui.button(this, "Remove") { prefs.dictionary(word, false); showTab(2) })
            dictionary.addView(row)
        }
        val privacy = Ui.card(page)
        privacy.addView(Ui.text(this, "Privacy and setup", 19f, bold = true))
        privacy.addView(Ui.text(this, "Excluded apps: ${prefs.blocked.size}. Assistance skips passwords, numeric fields and excluded apps. No account, network requests or text history.", 13f, Ui.MUTED))
        privacy.addView(Ui.button(this, "Choose excluded apps") { excludedApps() })
        privacy.addView(Ui.button(this, "Accessibility settings") { accessibilitySettings() })
        return page
    }

    private fun addChoice(parent: LinearLayout, title: String, options: List<String>, selected: String, change: (String) -> Unit) {
        parent.addView(Ui.text(this, title, 14f, bold = true))
        var currentChoice = selected
        parent.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(options.indexOf(selected).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) { if (options[position] != currentChoice) { currentChoice = options[position]; change(currentChoice) } }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
    }

    private fun excludedApps() {
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .distinctBy { it.activityInfo.packageName }.filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        val labels = apps.map { "${it.loadLabel(packageManager)}\n${it.activityInfo.packageName}" }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].activityInfo.packageName in prefs.blocked }
        AlertDialog.Builder(this).setTitle("Exclude apps from assistance")
            .setMultiChoiceItems(labels, checked) { _, which, block -> prefs.block(apps[which].activityInfo.packageName, block) }
            .setPositiveButton("Done") { _, _ -> showTab(2) }.show()
    }

    private fun accessibilityEnabled(): Boolean = getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == packageName && it.resolveInfo.serviceInfo.name.endsWith("WriterAccessibilityService") }

    private fun accessibilitySettings() {
        AlertDialog.Builder(this)
            .setTitle("Enable the floating writing assistant")
            .setMessage("Turn on “Use Local Writer assistant” in Android settings and allow access.\n\nThe small circular widget appears while you type and hides when you leave the field or close the keyboard. Tap it to open correction cards. An open correction sheet stays available while you review it.\n\nThe service reads the focused field and applies only the corrections you accept. Passwords and excluded apps are skipped.")
            .setPositiveButton("Open settings") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAccessibilitySettings() {
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(this, WriterAccessibilityService::class.java))
        if (!launch(details)) launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun launch(value: Intent): Boolean = runCatching { startActivity(value); true }.getOrElse {
        Ui.toast(this, "This action is unavailable on this device"); false
    }
}
