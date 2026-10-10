package dev.localwriter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.BackgroundColorSpan
import android.view.View
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView

class AssistantPanel(
    context: Context,
    private val apply: (String, List<Edit>) -> Boolean,
    private val close: () -> Unit,
    private val highlight: (List<Edit>) -> Unit = {},
    private val count: (Int) -> Unit = {},
    private val selection: () -> Pair<Int, Int>? = { null },
    private val checkState: (WritingCheckState) -> Unit = {},
    private val options: List<Pair<String, () -> Unit>> = emptyList(),
    private val overlayStyle: Boolean = false,
    private val modeChanged: (Boolean) -> Unit = {},
    private val inlineRight: Boolean = false,
    private val inlineDrag: (View) -> Unit = {},
    sharedStore: IssueStore? = null,
    private val requestCheck: (() -> Unit)? = null
) : LinearLayout(context) {
    private val session = AnalysisSession(context)
    private val issueStore = sharedStore ?: IssueStore()
    private val ownsStore = sharedStore == null
    private val status: TextView
    private val body: LinearLayout
    private val header: LinearLayout
    private val heading: TextView
    private val toolbar: LinearLayout
    private var compact = overlayStyle
    private var panelState = WritingCheckState.IDLE
    private var source = ""
    private var edits = emptyList<Edit>()
    private var presentation = "corrections"
    private var checkingActive = false
    private var lastConfig: CheckConfig? = null
    private var preview: String? = null
    private var selected: Edit? = null
    private var correctionsReady = false
    private val cards = mutableMapOf<Edit, View>()
    init {
        orientation = VERTICAL
        setPadding(if (overlayStyle) 0 else Ui.dp(context,12),if (overlayStyle) 0 else Ui.dp(context,12),if (overlayStyle) 0 else Ui.dp(context,12),if (overlayStyle) 0 else Ui.dp(context,12))
        background = if (overlayStyle) null else Ui.background()
        header = Ui.row(context).apply {
            if (overlayStyle) setPadding(Ui.dp(context,12),0,Ui.dp(context,12),0)
        }
        heading = if (overlayStyle) OverlayUi.heading(context,"Review suggestions") else Ui.text(context,"Writing assistant",18f,bold=true)
        header.addView(heading,LayoutParams(0,-2,1f))
        if (options.isNotEmpty()) {
            header.addView((if (overlayStyle) OverlayUi.icon(context,"⋯","Writing assistant options") { showOptions() } else Ui.button(context,"⋮") { showOptions() }).apply {
                contentDescription = "Writing assistant options"
            })
        }
        if (overlayStyle) header.addView(OverlayUi.icon(context,"⌄","Collapse to inline correction") { expand(false) })
        else header.addView(Ui.button(context,"×") { close() })
        addView(header)
        status = Ui.text(context,if (overlayStyle) "Checking…" else "Local models · English",13f,Ui.MUTED); addView(status)
        val tabs = Ui.column(context,0).apply {
            visibility = if (overlayStyle) GONE else VISIBLE
        }
        Ui.addRow(tabs,Ui.button(context,"Corrections") { if (correctionsReady) renderCorrections() else checkNow() },Ui.button(context,"Rewrite") { rewriteMenu() },Ui.button(context,"Tone") { tone() })
        addView(tabs)
        body = Ui.column(context,0).apply {
            if (overlayStyle) setPadding(Ui.dp(context,8),0,Ui.dp(context,8),0)
        }
        if (overlayStyle) addView(ScrollView(context).apply { isFillViewport = false; isFocusable = false; addView(body) },LayoutParams(-1,0,1f))
        else addView(body)
        toolbar = Ui.row(context).apply { setPadding(Ui.dp(context,16),0,Ui.dp(context,16),Ui.dp(context,8)) }
        if (overlayStyle) {
            toolbar.addView(ImageView(context).apply {
                setImageDrawable(context.getDrawable(R.drawable.ic_writer_widget)?.apply { setTint(OverlayUi.GREEN) })
                setPadding(Ui.dp(context,10),Ui.dp(context,10),Ui.dp(context,10),Ui.dp(context,10))
                contentDescription = "Review suggestions"; setOnClickListener { if (correctionsReady) renderCorrections() else checkNow() }
            },LayoutParams(Ui.dp(context,48),Ui.dp(context,48)))
            toolbar.addView(View(context),LayoutParams(0,1,1f))
            toolbar.addView(OverlayUi.icon(context,"✦","Rewrite and tone") { rewriteMenu() },LayoutParams(Ui.dp(context,48),Ui.dp(context,48)))
            addView(toolbar)
            setModeAppearance()
        }
    }

    private fun reportState(state: WritingCheckState) { panelState = state; checkState(state) }

    private fun setModeAppearance() {
        header.visibility = if (compact) GONE else VISIBLE
        toolbar.visibility = if (compact) GONE else VISIBLE
        status.visibility = if (compact) GONE else VISIBLE
        body.setPadding(if (compact) 0 else Ui.dp(context,8),0,if (compact) 0 else Ui.dp(context,8),0)
    }

    private fun expand(expanded: Boolean) {
        if (!overlayStyle) return
        compact = !expanded; setModeAppearance(); modeChanged(compact)
        if (correctionsReady) renderCorrections() else showCheckMessage(status.text.toString())
    }
    fun update(text: String, immediate: Boolean = false, knownCorrections: List<Edit>? = null) {
        if (source == text && !immediate) return
        session.cancel()
        checkingActive = false
        source = text; preview = null; selected = null; presentation = "corrections"
        if (ownsStore) {
            val prefs = Prefs(context)
            val config = CheckConfig(prefs.dialect, prefs.dictionary, prefs.styleAdvice, prefs.contextModel)
            if (lastConfig != null && lastConfig != config) issueStore.invalidate()
            lastConfig = config
        }
        issueStore.update(text); edits = issueStore.current.issues; correctionsReady = false
        if (source.isBlank()) {
            reportState(WritingCheckState.IDLE); highlight(emptyList()); count(0)
            status.text = "Start writing to see suggestions"; showCheckMessage(status.text.toString()); return
        }
        if (knownCorrections != null) {
            if (ownsStore) issueStore.publish(CheckUpdate(knownCorrections))
            acceptAnalysis(text, issueStore.current); return
        }
        if (requestCheck != null) { acceptAnalysis(text, issueStore.current); requestCheck.invoke(); return }
        checkingActive = true
        issueStore.publish(issueStore.current.copy(pending = true, unavailable = false))
        acceptAnalysis(text, issueStore.current)
        session.check(source,if(immediate)0 else 180) { result ->
            result.onSuccess { checkingActive = it.pending; acceptAnalysis(text, it) }.onFailure {
                checkingActive = false
                acceptAnalysis(text, CheckUpdate(edits, unavailable = true, message = "Couldn't check. Tap to retry."))
            }
        }
    }

    fun acceptAnalysis(text: String, update: CheckUpdate) {
        if (text != source) {
            session.cancel(); source = text; preview = null; selected = null
            issueStore.update(text); presentation = "corrections"; correctionsReady = false
        }
        val initial = !correctionsReady
        val previous = selected
        issueStore.publish(update)
        edits = issueStore.current.issues
        selected = previous?.let { old -> edits.firstOrNull { it.begin == old.begin && it.end == old.end && it.ruleId == old.ruleId } }
            ?: if (initial) edits.firstOrNull() else null
        correctionsReady = true
        reportState(issueStore.current.state)
        count(IssueStore.counted(edits)); highlight(edits)
        if (presentation == "corrections") {
            val scroll = body.parent as? ScrollView
            val y = scroll?.scrollY ?: 0
            renderCorrections()
            if (!initial) scroll?.post { scroll.scrollTo(0, y) }
        }
    }
    fun currentCorrections(text: String): List<Edit>? =
        if (source == text && correctionsReady) issueStore.current.issues else null

    private fun dismiss(e: Edit) { issueStore.dismiss(e); edits = issueStore.current.issues; renderCorrections() }

    private fun issueTitle(e: Edit): String {
        if (e.engine == "harper") return if (e.style) "Style suggestion" else e.category
        if (!e.actionable) return e.category
        val old = source.substring(e.begin,e.end).trim()
        val replacement = e.replacement.trim()
        return when {
            (old + replacement).none(Char::isLetterOrDigit) -> "Punctuation"
            old.isEmpty() && replacement.lowercase() in listOf("a","an","the") -> "Add article"
            old.isEmpty() -> "Add missing text"
            replacement.isEmpty() -> "Remove extra text"
            else -> "Suggested correction"
        }
    }

    private fun shortEdit(e: Edit): SpannableString {
        if (!e.actionable) return SpannableString(source.substring(e.begin,e.end))
        val old = source.substring(e.begin,e.end).trim()
        val replacement = e.replacement.trim()
        val gap = if (old.isNotEmpty() && replacement.isNotEmpty()) " → " else ""
        val text = SpannableString(old + gap + replacement)
        if (old.isNotEmpty()) {
            text.setSpan(ForegroundColorSpan(OverlayUi.RED),0,old.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StrikethroughSpan(),0,old.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (replacement.isNotEmpty()) text.setSpan(ForegroundColorSpan(OverlayUi.GREEN),old.length+gap.length,text.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
    }

    private fun inlineRow(label: CharSequence, title: String, suggestions: Int, click: () -> Unit, dismiss: () -> Unit) {
        val row = Ui.row(context)
        val badge = WritingWidget(context).apply {
            update(suggestions,panelState); setOnClickListener { expand(true) }
            contentDescription = "Review all $suggestions suggestions"
        }
        inlineDrag(badge)
        val pill = Ui.column(context,0).apply {
            background = Ui.background(Color.WHITE,Ui.dp(context,12)); elevation = Ui.dp(context,3).toFloat()
            setPadding(Ui.dp(context,10),Ui.dp(context,2),Ui.dp(context,8),Ui.dp(context,2))
            addView(OverlayUi.text(context,title,10f,OverlayUi.MUTED).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setLineHeight(Ui.dp(context,12))
            })
            addView(OverlayUi.text(context,label,13f).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0,Ui.dp(context,2),0,0); setLineHeight(Ui.dp(context,16))
            })
            contentDescription = "$title. $label"; setOnClickListener { click() }; isFocusable = false
        }
        val ignore = ImageView(context).apply {
            setImageResource(R.drawable.ic_ignore); setColorFilter(OverlayUi.INK)
            setPadding(Ui.dp(context,9),Ui.dp(context,9),Ui.dp(context,9),Ui.dp(context,9))
            contentDescription = if (suggestions > 0) "Dismiss suggestion" else "Close writing assistant"
            setOnClickListener { dismiss() }; isFocusable = false
        }
        if (inlineRight) row.addView(ignore,LayoutParams(Ui.dp(context,37),Ui.dp(context,37)))
        else row.addView(badge,LayoutParams(Ui.dp(context,48),Ui.dp(context,48)))
        row.addView(pill,LayoutParams(0,-2,1f))
        if (inlineRight) row.addView(badge,LayoutParams(Ui.dp(context,48),Ui.dp(context,48)))
        else row.addView(ignore,LayoutParams(Ui.dp(context,37),Ui.dp(context,37)))
        body.addView(row)
    }

    private fun renderInline(shown: List<Edit>) {
        val e = selected?.takeIf { it in shown } ?: shown.firstOrNull()
        if (e == null) {
            val update = issueStore.current
            inlineRow(if (update.pending) "No suggestions found so far" else if (update.unavailable) "Check unavailable. Tap to retry." else "No suggestions found",
                if (update.pending) "Checking your writing…" else "Writing assistant",0,
                { if (update.unavailable) checkNow() else expand(true) },close); return
        }
        selected = e
        val label = shortEdit(e)
        val paint = TextPaint().apply {
            textSize = 13f * resources.displayMetrics.scaledDensity
            typeface = resources.getFont(R.font.inter_regular)
        }
        val wide = paint.measureText(label.toString()) > resources.displayMetrics.widthPixels / 2f - Ui.dp(context,36)
        val review = wide || !e.actionable || e.choices.size > 1
        inlineRow(if (review) "Review suggestion" else label,issueTitle(e),IssueStore.counted(shown),
            { if (review) expand(true) else replace(listOf(e)) }, { dismiss(e) })
    }

    private fun showCheckMessage(message: String) {
        body.removeAllViews()
        if (!overlayStyle) return
        if (compact) inlineRow(message,"Writing assistant",0,
            { if (panelState == WritingCheckState.UNAVAILABLE) checkNow() else expand(true) },close)
        else body.addView(OverlayUi.text(context,message,14f,OverlayUi.MUTED).apply {
            gravity = android.view.Gravity.CENTER; setPadding(Ui.dp(context,16),Ui.dp(context,32),Ui.dp(context,16),Ui.dp(context,32))
            if (panelState == WritingCheckState.UNAVAILABLE) setOnClickListener { checkNow() }
        })
    }
    private fun showOptions() {
        presentation = "options"
        if (overlayStyle) heading.text = "Options"
        if (overlayStyle && compact) { compact = false; setModeAppearance(); modeChanged(false) }
        status.text = "Writing assistant options"; body.removeAllViews()
        val shown = currentCorrections(source)
        val bulk = IssueStore.bulk(shown.orEmpty())
        if (bulk.isNotEmpty()) body.addView(surfaceButton("Accept ${bulk.size} compatible corrections",true) { replace(bulk) })
        if (overlayStyle && shown != null) body.addView(surfaceButton("View marked text") {
            body.removeAllViews()
            body.addView(surfaceText("",16f).apply {
                text = markedText(source,shown) { selectCard(it) }; movementMethod = LinkMovementMethod.getInstance()
            })
            body.addView(surfaceButton("Back to corrections") { renderCorrections() })
        })
        for ((label, action) in options) body.addView(surfaceButton(label) { action() })
        body.addView(surfaceButton("Back to corrections") {
            if (correctionsReady) renderCorrections() else checkNow()
        })
    }
    private fun surfaceButton(label: String, primary: Boolean = false, action: () -> Unit): TextView =
        if (overlayStyle) OverlayUi.action(context,label,primary,action) else Ui.button(context,label,primary,action)
    private fun surfaceText(value: String, size: Float = 14f, color: Int = Ui.INK, bold: Boolean = false): TextView =
        if (overlayStyle) OverlayUi.text(context,value,size,color,bold) else Ui.text(context,value,size,color,bold)
    private fun checkNow() = update(source,true)
    private fun renderCorrections() {
        presentation = "corrections"
        if (ownsStore && issueStore.current.pending && !checkingActive && source.isNotBlank()) { checkNow(); return }
        if (overlayStyle) heading.text = "Review suggestions"
        body.removeAllViews(); cards.clear(); val shown = issueStore.current.issues
        edits = shown
        if (selected != null && selected !in shown) selected = shown.firstOrNull()
        val counted = IssueStore.counted(shown)
        count(counted); highlight(shown)
        val update = issueStore.current
        status.text = when {
            update.unavailable -> update.message.ifBlank { "Some checks are unavailable. Tap to retry." }
            update.pending -> "$counted correction${if(counted==1) "" else "s"} · checking…"
            else -> "$counted correction${if(counted==1) "" else "s"}" + if (shown.any { it.style || !it.actionable }) " · additional advice" else ""
        }
        status.setOnClickListener { if (update.unavailable) checkNow() }
        if (overlayStyle && compact) { renderInline(shown); return }
        if (overlayStyle && shown.isEmpty()) {
            body.addView(OverlayUi.text(context,if(update.pending) "Checking your writing…" else if (update.unavailable) "Checking incomplete" else "No suggestions found",16f,OverlayUi.INK,true).apply {
                gravity = android.view.Gravity.CENTER; setPadding(0,Ui.dp(context,32),0,Ui.dp(context,8))
            })
            body.addView(OverlayUi.text(context,if(update.pending) "No suggestions found so far" else update.message.ifBlank { "Checked on this device" },14f,OverlayUi.MUTED).apply { gravity = android.view.Gravity.CENTER })
        }
        if (shown.isNotEmpty() && !overlayStyle) {
            val draft = Ui.card(body)
            draft.setBackgroundColor(Ui.PALE)
            draft.addView(Ui.text(context,"Your text · tap a marked word",12f,Ui.MUTED))
            draft.addView(Ui.text(context,"",16f).apply {
                text = markedText(source, shown) { edit -> selectCard(edit) }
                movementMethod = LinkMovementMethod.getInstance()
                maxLines = 6
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val bulk = IssueStore.bulk(shown)
            if (bulk.isNotEmpty()) body.addView(Ui.button(context,"Accept ${bulk.size} compatible corrections",true) { replace(bulk) })
        }
        for (e in shown) renderRevisionCard(e)
    }

    private fun contextEdit(e: Edit, around: Int): SpannableString {
        var start = (e.begin-around).coerceAtLeast(0)
        var end = (e.end+around).coerceAtMost(source.length)
        if (start > 0 && source[start].isLowSurrogate()) start--
        if (end < source.length && source[end].isLowSurrogate()) end++
        if (!e.actionable) return SpannableString(source.substring(start,end)).apply {
            if (e.end > e.begin) setSpan(ForegroundColorSpan(OverlayUi.RED),e.begin-start,e.end-start,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val prefix = (if (start > 0) "…" else "") + source.substring(start,e.begin)
        val text = SpannableString(prefix + e.replacement + source.substring(e.end,end) + if (end < source.length) "…" else "")
        val newStart = prefix.length; val newEnd = newStart+e.replacement.length
        if (newEnd > newStart) {
            text.setSpan(ForegroundColorSpan(OverlayUi.GREEN),newStart,newEnd,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(BackgroundColorSpan(0xffe9f6ef.toInt()),newStart,newEnd,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    private fun renderRevisionCard(e: Edit) {
        val expanded = selected == e
        val card = Ui.column(context,12).apply { background = Ui.background(Color.WHITE,0) }
        body.addView(card); cards[e] = card
        val category = Ui.row(context)
        category.addView(View(context).apply { background = Ui.background(OverlayUi.RED,Ui.dp(context,6)) },
            LayoutParams(Ui.dp(context,12),Ui.dp(context,12)).apply { marginEnd = Ui.dp(context,4) })
        category.addView(OverlayUi.text(context,issueTitle(e),11f,OverlayUi.MUTED).apply { setLineHeight(Ui.dp(context,16)) },LayoutParams(0,-2,1f))
        category.addView(OverlayUi.text(context,if (expanded) "⌃" else "⌄",16f,OverlayUi.MUTED))
        card.addView(category)
        card.addView(View(context),LayoutParams(1,Ui.dp(context,12)))
        card.addView(OverlayUi.text(context,contextEdit(e,if (expanded) 50 else 24),16f).apply {
            contentDescription = if (e.actionable) "Suggested text: $text" else text.toString()
            setLineHeight(Ui.dp(context,24))
            if (!expanded) { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            setPadding(0,0,0,Ui.dp(context,16))
        })
        card.setOnClickListener { if (expanded) { selected = null; renderCorrections() } else selectCard(e) }
        card.contentDescription = if (expanded) "Collapse correction" else "Expand correction"
        if (expanded) {
            if (e.actionable) card.addView(OverlayUi.text(context,shortEdit(e),13f).apply {
                setPadding(0,0,0,Ui.dp(context,12))
                contentDescription = "Change: " + source.substring(e.begin,e.end).trim().ifEmpty { "Insert" } +
                    " to " + e.replacement.trim().ifEmpty { "remove" }
            })
            if (e.explanation.isNotBlank()) card.addView(OverlayUi.text(context,e.explanation,13f,OverlayUi.MUTED).apply { setPadding(0,0,0,Ui.dp(context,12)) })
            for (choice in e.choices.drop(1)) card.addView(OverlayUi.action(context,"Use: ${choice.ifEmpty { "Remove" }}") { replace(listOf(e.copy(replacement = choice, alternatives = emptyList()))) })
            val actions = Ui.row(context).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                setPadding(0,Ui.dp(context,4),0,Ui.dp(context,4))
            }
            val dismiss = OverlayUi.action(context,"Dismiss") { dismiss(e) }
            val word = source.substring(e.begin,e.end).trim()
            if (e.category == "Spelling" && word.matches(Regex("[\\p{L}\\p{M}’'-]+"))) card.addView(OverlayUi.action(context,"Add to dictionary") {
                Prefs(context).dictionary(word,true); dismiss(e); if (ownsStore) checkNow()
            })
            actions.addView(dismiss); actions.addView(View(context),LayoutParams(Ui.dp(context,8),1))
            if (e.actionable) actions.addView(OverlayUi.action(context,"Accept",true) { replace(listOf(e)) })
            card.addView(actions)
        }
        body.addView(View(context).apply { setBackgroundColor(OverlayUi.LINE) },
            LayoutParams(-1,Ui.dp(context,1)).apply { setMargins(Ui.dp(context,12),0,Ui.dp(context,12),0) })
    }
    private fun markedText(text: String, corrections: List<Edit>, click: (Edit) -> Unit): SpannableString {
        val marked = SpannableString(text)
        for (edit in corrections) {
            val (start,end) = TextLogic.highlightRange(text,edit) ?: continue
            marked.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) { click(edit) }
                override fun updateDrawState(paint: TextPaint) {
                    paint.isUnderlineText = true; paint.underlineColor = Color.rgb(218,53,65)
                    paint.underlineThickness = Ui.dp(context,2).toFloat()
                    paint.bgColor = Color.rgb(255,232,232)
                }
            },start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return marked
    }
    private fun selectCard(edit: Edit) {
        selected = edit; renderCorrections()
        post {
            val target = cards[edit] ?: return@post
            var scrollParent = if (overlayStyle) body.parent else parent
            while (scrollParent != null && scrollParent !is ScrollView) scrollParent = scrollParent.parent
            val scroll = scrollParent as? ScrollView ?: return@post
            val rect = android.graphics.Rect(0,0,target.width,target.height)
            scroll.offsetDescendantRectToMyCoords(target,rect)
            scroll.smoothScrollTo(0,(rect.top-Ui.dp(context,8)).coerceAtLeast(0))
        }
    }
    private fun replace(changes: List<Edit>) {
        if (changes.isEmpty() || changes.any { !it.actionable } || changes.indices.any { i -> changes.take(i).any { !IssueStore.compatible(it, changes[i]) } }) return
        val before = source
        val after = TextLogic.apply(before, changes)
        if(apply(before,changes)) {
            if (ownsStore && source == before) update(after,true)
            else {
                reportState(WritingCheckState.CHECKING)
                status.text = "Applying correction…"
            }
        }
        else {
            status.text = "The field changed. Refresh suggestions before applying."; session.cancel()
            if (overlayStyle) {
                correctionsReady = false; reportState(WritingCheckState.UNAVAILABLE)
                count(0); highlight(emptyList()); showCheckMessage("Text changed. Tap to refresh.")
            }
        }
    }
    private fun rewriteMenu() {
        presentation = "rewrite"
        checkingActive = false
        if (overlayStyle) heading.text = "Rewrite"
        if (overlayStyle && compact) { compact = false; setModeAppearance(); modeChanged(false) }
        session.cancel(); body.removeAllViews(); status.text = "Rewrite on this device"
        body.addView(surfaceText("Choose how you want your text to sound.",14f,Ui.MUTED))
        if (overlayStyle) body.addView(OverlayUi.action(context,"Detect tone") { tone() })
        for (pair in listOf("Improve" to "Concise", "Professional" to "Friendly", "Expand" to "English"))
            Ui.addRow(body,surfaceButton(pair.first) { rewrite(pair.first) },surfaceButton(pair.second) { rewrite(pair.second) })
    }
    fun rewrite(kind: String, custom: String = "") {
        presentation = "rewrite"
        checkingActive = false
        if (overlayStyle) heading.text = "Rewrite"
        if(source.isBlank()) return
        val (start, end) = selectedRange()
        val input = source.substring(start, end)
        status.text = "Preparing a local rewrite…"; body.removeAllViews()
        if (overlayStyle) body.addView(surfaceText("Working on your text…",14f,OverlayUi.MUTED))
        body.addView(surfaceButton("Cancel") { session.cancel(); if (correctionsReady) renderCorrections() else checkNow() })
        session.rewrite(input,kind,custom) { result ->
            result.onSuccess { value ->
                preview = value; body.removeAllViews(); status.text = "Review your rewrite"
                body.addView(surfaceText(value,16f))
                Ui.addRow(body,surfaceButton("Apply",true) { replace(listOf(Edit(start,end,value))) },surfaceButton("Copy") {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Rewrite",value)); Ui.toast(context,"Copied")
                },surfaceButton("Dismiss") { renderCorrections() })
            }.onFailure {
                status.text = "Rewrite unavailable for this text. Try a shorter selection."; body.removeAllViews()
                if (overlayStyle) {
                    body.addView(surfaceText(status.text.toString(),14f,OverlayUi.MUTED))
                    body.addView(surfaceButton("Back to suggestions") { if (correctionsReady) renderCorrections() else checkNow() })
                }
            }
        }
    }
    private fun selectedRange(): Pair<Int, Int> {
        val s = selection()
        return if (s != null && s.first in 0..source.length && s.second in 0..source.length && s.first != s.second)
            minOf(s.first, s.second) to maxOf(s.first, s.second) else 0 to source.length
    }
    fun tone() {
        presentation = "tone"
        checkingActive = false
        if (overlayStyle) heading.text = "Tone"
        if(source.isBlank())return
        status.text = "Reading tone locally…"; body.removeAllViews()
        if (overlayStyle) body.addView(surfaceText("Working on your text…",14f,OverlayUi.MUTED))
        body.addView(surfaceButton("Cancel") { session.cancel(); if (correctionsReady) renderCorrections() else checkNow() })
        val (start, end) = selectedRange()
        session.tone(source.substring(start,end)) { result -> result.onSuccess { value ->
            status.text = "Tone: $value"; body.removeAllViews(); body.addView(surfaceText("Your writing sounds ${value.lowercase()}.",17f,bold=true))
            Ui.addRow(body,surfaceButton("Make professional") { rewrite("Professional") },surfaceButton("Make friendly") { rewrite("Friendly") })
        }.onFailure {
            status.text = "Tone unavailable. Try again with a shorter selection."
            if (overlayStyle) {
                body.removeAllViews(); body.addView(surfaceText(status.text.toString(),14f,OverlayUi.MUTED))
                body.addView(surfaceButton("Back to suggestions") { if (correctionsReady) renderCorrections() else checkNow() })
            }
        } }
    }
    fun dispose() { session.cancel(); source = ""; edits = emptyList(); preview = null; selected = null; correctionsReady = false; cards.clear(); if (ownsStore) issueStore.reset() }
}
