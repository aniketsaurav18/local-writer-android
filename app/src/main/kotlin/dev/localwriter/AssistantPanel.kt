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
import android.widget.EditText
import android.widget.HorizontalScrollView

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
    private val inlineDrag: (View) -> Unit = {},
    private val inlineWidthChanged: (Int) -> Unit = {},
    private val inputFocusChanged: (Boolean) -> Unit = {},
    sharedStore: IssueStore? = null,
    private val requestCheck: (() -> Unit)? = null,
    private val expandForScroll: () -> Unit = {}
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
    private var detailOptions: Edit? = null
    private val composer = Ui.row(context)
    private val promptInput = EditText(context)
    private var rewriteKind = "Improve"
    private var rewriteCustom = ""
    private var correctionsReady = false
    private val cards = mutableMapOf<Edit, View>()
    init {
        orientation = VERTICAL
        setPadding(if (overlayStyle) 0 else Ui.dp(context,12),if (overlayStyle) 0 else Ui.dp(context,12),if (overlayStyle) 0 else Ui.dp(context,12),if (overlayStyle) 0 else Ui.dp(context,12))
        background = if (overlayStyle) null else Ui.background()
        header = Ui.row(context).apply {
            if (overlayStyle) setPadding(Ui.dp(context,16),Ui.dp(context,12),Ui.dp(context,16),Ui.dp(context,12))
        }
        heading = if (overlayStyle) OverlayUi.heading(context,"Review suggestions") else Ui.text(context,"Writing assistant",18f,bold=true)
        if (overlayStyle) heading.setTextColor(OverlayUi.floatingInk(context))
        header.addView(heading,LayoutParams(0,-2,1f))
        if (options.isNotEmpty() && !overlayStyle) {
            header.addView((if (overlayStyle) OverlayUi.icon(context,"⋯","Writing assistant options") { showOptions() } else Ui.button(context,"⋮") { showOptions() }).apply {
                contentDescription = "Writing assistant options"
            })
        }
        if (!overlayStyle) header.addView(Ui.button(context,"×") { close() })
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
        if (overlayStyle) addView(object : ScrollView(context) {
            private var startX = 0f
            private var startY = 0f
            private var expandedGesture = false
            override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX; startY = event.rawY; expandedGesture = false
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val upward = startY - event.rawY
                        if (!compact && !expandedGesture && upward > Ui.dp(context,24) &&
                            upward > kotlin.math.abs(event.rawX-startX)) {
                            expandedGesture = true
                            expandForScroll()
                        }
                    }
                }
                return super.dispatchTouchEvent(event)
            }
            override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean {
                if (!compact && event.action == android.view.MotionEvent.ACTION_SCROLL &&
                    event.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL) != 0f) expandForScroll()
                return super.onGenericMotionEvent(event)
            }
        }.apply { isFillViewport = false; isFocusable = false; addView(body) },LayoutParams(-1,0,1f))
        else addView(body)
        toolbar = Ui.row(context).apply { setPadding(Ui.dp(context,16),0,Ui.dp(context,16),Ui.dp(context,8)) }
        if (overlayStyle) {
            toolbar.addView(ImageView(context).apply {
                setImageDrawable(context.getDrawable(R.drawable.ic_writer_widget)?.apply { setTint(OverlayUi.accent(context)) })
                setPadding(Ui.dp(context,10),Ui.dp(context,10),Ui.dp(context,10),Ui.dp(context,10))
                contentDescription = "Review suggestions"; setOnClickListener { if (correctionsReady) renderCorrections() else checkNow() }
            },LayoutParams(Ui.dp(context,48),Ui.dp(context,48)))
            toolbar.addView(View(context),LayoutParams(0,1,1f))
            toolbar.addView(OverlayUi.icon(context,"⋯","Writing assistant options") { showOptions() }.apply { setTextColor(OverlayUi.floatingMuted(context)) })
            toolbar.addView(OverlayUi.icon(context,"✧","Rewrite and tone") { rewriteMenu() }.apply { setTextColor(OverlayUi.accent(context)) },LayoutParams(Ui.dp(context,48),Ui.dp(context,48)))
            toolbar.addView(OverlayUi.icon(context,"×","Close writing assistant menu") { close() }.apply {
                setTextColor(OverlayUi.floatingMuted(context))
            },LayoutParams(Ui.dp(context,48),Ui.dp(context,48)))
            addView(toolbar,0)
            composer.setPadding(Ui.dp(context,16),Ui.dp(context,12),Ui.dp(context,16),Ui.dp(context,12))
            composer.setBackgroundColor(if (OverlayUi.dark(context)) 0xff303030.toInt() else 0xfff0f2f1.toInt())
            promptInput.apply {
                hint = "Try “Make it sound professional”"; textSize = 14f
                setTextColor(OverlayUi.floatingInk(context)); setHintTextColor(OverlayUi.floatingMuted(context))
                background = Ui.background(OverlayUi.floatingSurface(context),Ui.dp(context,8))
                setPadding(Ui.dp(context,12),Ui.dp(context,12),Ui.dp(context,12),Ui.dp(context,12))
                isSingleLine = true; isFocusable = false
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                setOnTouchListener { _, event ->
                    if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                        inputFocusChanged(true)
                        isFocusableInTouchMode = true
                        post { requestFocus(); context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(this,0) }
                    }
                    false
                }
                setOnEditorActionListener { _, action, _ ->
                    if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { submitPrompt(); true } else false
                }
            }
            composer.addView(promptInput,LayoutParams(0,-2,1f))
            composer.addView(OverlayUi.icon(context,"➤","Send rewrite instruction") { submitPrompt() }.apply { setTextColor(OverlayUi.accent(context)) })
            addView(composer)
            setModeAppearance()
        }
    }

    private fun reportState(state: WritingCheckState) { panelState = state; checkState(state) }

    private fun setModeAppearance() {
        header.visibility = if (compact) GONE else VISIBLE
        toolbar.visibility = if (compact) GONE else VISIBLE
        status.visibility = GONE
        composer.visibility = if (!compact && presentation == "rewrite") VISIBLE else GONE
        body.setPadding(if (compact) 0 else Ui.dp(context,8),0,if (compact) 0 else Ui.dp(context,8),0)
    }

    fun collapseToPill() = expand(false)
    fun openSuggestions() = expand(true)

    fun expandedContentHeight(): Int {
        val scroll = body.parent as? ScrollView ?: return height
        val contentWidth = (scroll.width-scroll.paddingLeft-scroll.paddingRight).coerceAtLeast(1)
        body.measure(android.view.View.MeasureSpec.makeMeasureSpec(contentWidth,android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED))
        var fixedHeight = paddingTop + paddingBottom + scroll.paddingTop + scroll.paddingBottom
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child !== scroll && child.visibility != GONE) {
                val margins = child.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                fixedHeight += child.measuredHeight + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0)
            }
        }
        return fixedHeight + body.measuredHeight
    }

    private fun expand(expanded: Boolean) {
        if (!overlayStyle) return
        releasePrompt()
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
            text.setSpan(ForegroundColorSpan(if (overlayStyle && compact) WritingWidget.CORAL else OverlayUi.RED),0,old.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StrikethroughSpan(),0,old.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (replacement.isNotEmpty()) text.setSpan(ForegroundColorSpan(if (overlayStyle && compact) OverlayUi.floatingCorrection(context) else OverlayUi.GREEN),old.length+gap.length,text.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
    }

    private fun inlineRow(label: CharSequence, title: String, suggestions: Int, click: () -> Unit, dismiss: () -> Unit) {
        val row = Ui.row(context).apply {
            background = GradientDrawable().apply {
                setColor(OverlayUi.floatingSurface(context))
                val r = Ui.dp(context,19).toFloat()
                cornerRadii = floatArrayOf(r,r,r,r,r,r,0f,0f)
                setStroke(maxOf(1,Ui.dp(context,1)),OverlayUi.floatingLine(context))
            }
        }
        val badge = WritingWidget(context,inline = true).apply {
            update(suggestions,panelState); setOnClickListener { expand(true) }
            contentDescription = "Review all $suggestions suggestions"
        }
        inlineDrag(badge)
        val titleView = OverlayUi.text(context,title,10f,OverlayUi.floatingMuted(context)).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setLineHeight(Ui.dp(context,12))
        }
        val editView = OverlayUi.text(context,label,13f,OverlayUi.floatingInk(context)).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0,Ui.dp(context,2),0,0); setLineHeight(Ui.dp(context,16))
        }
        val pill = Ui.column(context,0).apply {
            setPadding(Ui.dp(context,10),Ui.dp(context,2),Ui.dp(context,8),Ui.dp(context,2))
            addView(titleView); addView(editView)
            contentDescription = "$title. $label"; setOnClickListener { click() }; isFocusable = false
        }
        inlineDrag(pill)
        val ignore = ImageView(context).apply {
            setImageResource(R.drawable.ic_ignore); setColorFilter(OverlayUi.floatingMuted(context))
            setPadding(Ui.dp(context,10),Ui.dp(context,10),Ui.dp(context,17),Ui.dp(context,10))
            contentDescription = if (suggestions > 0) "Dismiss suggestion" else "Close writing assistant"
            setOnClickListener { dismiss() }; isFocusable = false
        }
        inlineDrag(ignore)
        inlineDrag(row)
        row.addView(badge,LayoutParams(Ui.dp(context,38),Ui.dp(context,38)))
        row.addView(pill,LayoutParams(0,-2,1f))
        row.addView(ignore,LayoutParams(Ui.dp(context,44),Ui.dp(context,38)))
        body.addView(row,LayoutParams(-1,Ui.dp(context,38)).apply { topMargin = Ui.dp(context,5) })
        val textWidth = maxOf(titleView.paint.measureText(title),editView.paint.measureText(label.toString()))
        inlineWidthChanged(maxOf(Ui.dp(context,194),kotlin.math.ceil(textWidth).toInt()+Ui.dp(context,100)))
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
        val available = minOf(Ui.dp(context,210),resources.displayMetrics.widthPixels-Ui.dp(context,116))
        val wide = paint.measureText(label.toString()) > available
        val review = wide || !e.actionable
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
        if (presentation != "corrections") session.cancel()
        presentation = "options"
        if (overlayStyle) heading.text = "Options"
        if (overlayStyle) {
            releasePrompt()
            if (compact) { compact = false; modeChanged(false) }
            setModeAppearance()
        }
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
        if (overlayStyle) OverlayUi.action(context,label,primary,floating=true,click=action) else Ui.button(context,label,primary,action)
    private fun surfaceText(value: String, size: Float = 14f, color: Int = Ui.INK, bold: Boolean = false): TextView =
        if (overlayStyle) OverlayUi.text(context,value,size,if (color == Ui.INK) OverlayUi.floatingInk(context) else OverlayUi.floatingMuted(context),bold) else Ui.text(context,value,size,color,bold)
    private fun checkNow() = update(source,true)
    private fun renderCorrections() {
        if (presentation != "corrections") session.cancel()
        presentation = "corrections"
        if (overlayStyle) { releasePrompt(); setModeAppearance() }
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
            if (e.end > e.begin) setSpan(ForegroundColorSpan(if (overlayStyle) WritingWidget.CORAL else OverlayUi.RED),e.begin-start,e.end-start,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val prefix = (if (start > 0) "…" else "") + source.substring(start,e.begin)
        val old = if (overlayStyle) source.substring(e.begin,e.end) else ""
        val text = SpannableString(prefix + old + e.replacement + source.substring(e.end,end) + if (end < source.length) "…" else "")
        val oldStart = prefix.length; val newStart = oldStart+old.length; val newEnd = newStart+e.replacement.length
        if (old.isNotEmpty()) {
            text.setSpan(ForegroundColorSpan(if (OverlayUi.dark(context)) 0xffb8b8b8.toInt() else 0xff777777.toInt()),oldStart,newStart,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(BackgroundColorSpan(if (OverlayUi.dark(context)) 0xff424242.toInt() else 0xffe2e2e2.toInt()),oldStart,newStart,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StrikethroughSpan(),oldStart,newStart,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),oldStart,newStart,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (newEnd > newStart) {
            text.setSpan(ForegroundColorSpan(if (overlayStyle) OverlayUi.accent(context) else OverlayUi.GREEN),newStart,newEnd,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(BackgroundColorSpan(if (!overlayStyle) 0xffe9f6ef.toInt() else if (OverlayUi.dark(context)) 0xff07534c.toInt() else 0xffddf3ee.toInt()),newStart,newEnd,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (overlayStyle) text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),newStart,newEnd,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    private fun renderRevisionCard(e: Edit) {
        val expanded = selected == e
        val ink = if (overlayStyle) OverlayUi.floatingInk(context) else OverlayUi.INK
        val muted = if (overlayStyle) OverlayUi.floatingMuted(context) else OverlayUi.MUTED
        val word = source.substring(e.begin,e.end).trim()
        val dictionaryWord = e.category == "Spelling" && word.matches(Regex("[\\p{L}\\p{M}’'-]+"))
        val hasDetails = e.explanation.isNotBlank() || e.choices.size > 1 || dictionaryWord
        val showDetails = !overlayStyle || detailOptions == e
        val card = Ui.column(context,12).apply {
            background = Ui.background(if (!overlayStyle) Color.WHITE else if (expanded) {
                if (OverlayUi.dark(context)) 0xff2d2d2d.toInt() else 0xfff0f3f2.toInt()
            } else OverlayUi.floatingSurface(context),0)
        }
        body.addView(card); cards[e] = card
        val category = Ui.row(context)
        category.addView(View(context).apply { background = Ui.background(if (overlayStyle) WritingWidget.CORAL else OverlayUi.RED,Ui.dp(context,if (overlayStyle) 3 else 6)) },
            LayoutParams(Ui.dp(context,if (overlayStyle) 7 else 12),Ui.dp(context,if (overlayStyle) 10 else 12)).apply { marginEnd = Ui.dp(context,5) })
        category.addView(OverlayUi.text(context,issueTitle(e),11f,muted).apply { setLineHeight(Ui.dp(context,16)) },LayoutParams(0,-2,1f))
        if (!overlayStyle) category.addView(OverlayUi.text(context,if (expanded) "⌃" else "⌄",16f,muted))
        else if (expanded && hasDetails) category.addView(OverlayUi.text(context,"⋯",18f,muted).apply {
            gravity = android.view.Gravity.CENTER
            contentDescription = if (showDetails) "Hide correction details" else "Show correction details"
            setOnClickListener { detailOptions = if (showDetails) null else e; renderCorrections() }
        },LayoutParams(Ui.dp(context,40),Ui.dp(context,24)))
        card.addView(category)
        card.addView(View(context),LayoutParams(1,Ui.dp(context,12)))
        card.addView(OverlayUi.text(context,contextEdit(e,if (expanded) 50 else 24),16f,ink).apply {
            contentDescription = if (e.actionable) "Suggested text: $text" else text.toString()
            setLineHeight(Ui.dp(context,24))
            if (!expanded) { maxLines = if (overlayStyle) 1 else 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            setPadding(0,0,0,Ui.dp(context,if (!overlayStyle || expanded) 16 else 12))
        })
        card.setOnClickListener { detailOptions = null; if (expanded) { selected = null; renderCorrections() } else selectCard(e) }
        card.contentDescription = if (expanded) "Collapse correction" else "Expand correction"
        if (expanded) {
            if (e.actionable && !overlayStyle) card.addView(OverlayUi.text(context,shortEdit(e),13f).apply {
                setPadding(0,0,0,Ui.dp(context,12))
                contentDescription = "Change: " + source.substring(e.begin,e.end).trim().ifEmpty { "Insert" } +
                    " to " + e.replacement.trim().ifEmpty { "remove" }
            })
            if (showDetails && e.explanation.isNotBlank()) card.addView(OverlayUi.text(context,e.explanation,13f,muted).apply { setPadding(0,0,0,Ui.dp(context,12)) })
            if (showDetails) for (choice in e.choices.drop(1)) card.addView(OverlayUi.action(context,"Use: ${choice.ifEmpty { "Remove" }}",floating=overlayStyle) { replace(listOf(e.copy(replacement = choice, alternatives = emptyList()))) })
            val actions = Ui.row(context).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                setPadding(0,Ui.dp(context,4),0,Ui.dp(context,4))
            }
            val dismiss = OverlayUi.action(context,"Dismiss",floating=overlayStyle) { dismiss(e) }
            if (showDetails && dictionaryWord) card.addView(OverlayUi.action(context,"Add to dictionary",floating=overlayStyle) {
                Prefs(context).dictionary(word,true); dismiss(e); if (ownsStore) checkNow()
            })
            actions.addView(dismiss); actions.addView(View(context),LayoutParams(Ui.dp(context,8),1))
            if (e.actionable) actions.addView(OverlayUi.action(context,"Accept",true,floating=overlayStyle) { replace(listOf(e)) })
            card.addView(actions)
        }
        body.addView(View(context).apply { setBackgroundColor(if (overlayStyle) OverlayUi.floatingLine(context) else OverlayUi.LINE) },
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
    private fun releasePrompt() {
        if (!overlayStyle || !promptInput.hasFocus()) return
        context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(promptInput.windowToken,0)
        promptInput.clearFocus(); promptInput.isFocusable = false; inputFocusChanged(false)
    }

    private fun submitPrompt() {
        val instruction = promptInput.text.toString().trim()
        if (instruction.isEmpty()) return
        promptInput.text.clear()
        releasePrompt(); rewrite("Custom",instruction)
    }

    private fun rewriteMenu() {
        presentation = "rewrite"; checkingActive = false; session.cancel()
        if (overlayStyle) {
            if (compact) { compact = false; modeChanged(false) }
            setModeAppearance()
            val (start,end) = selectedRange()
            val corrections = IssueStore.bulk(issueStore.current.issues).filter { it.begin >= start && it.end <= end }
                .map { it.copy(begin = it.begin-start,end = it.end-start) }
            val value = TextLogic.apply(source.substring(start,end),corrections)
            rewriteKind = "Improve"; rewriteCustom = ""
            showRewritePreview(value,start,end,"Fix grammar errors")
            return
        }
        body.removeAllViews(); status.text = "Rewrite on this device"
        body.addView(surfaceText("Choose how you want your text to sound.",14f,Ui.MUTED))
        for (pair in listOf("Improve" to "Concise", "Professional" to "Friendly", "Expand" to "English"))
            Ui.addRow(body,surfaceButton(pair.first) { rewrite(pair.first) },surfaceButton(pair.second) { rewrite(pair.second) })
    }

    private fun rewriteChips() {
        val chips = Ui.row(context).apply { setPadding(0,Ui.dp(context,12),0,Ui.dp(context,12)) }
        for ((label,kind) in listOf("✦ Improve" to "Improve","Rephrase" to "Rephrase","Shorten" to "Concise","Friendly" to "Friendly","Professional" to "Professional","Expand" to "Expand","English" to "English")) {
            chips.addView(OverlayUi.action(context,label,floating=true) { rewrite(kind) }.apply {
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT); cornerRadius = Ui.dp(context,24).toFloat()
                    setStroke(Ui.dp(context,1),if (kind == rewriteKind) OverlayUi.accent(context) else OverlayUi.floatingMuted(context))
                }
            },LayoutParams(-2,Ui.dp(context,36)).apply { marginEnd = Ui.dp(context,8) })
        }
        body.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
    }

    private fun showRewritePreview(value: String, start: Int, end: Int, title: String) {
        preview = value; body.removeAllViews(); heading.text = title
        body.setPadding(Ui.dp(context,16),Ui.dp(context,8),Ui.dp(context,16),0)
        val previewSource = source
        val marked = SpannableString(value)
        var delta = 0
        for (edit in TextLogic.diff(source.substring(start,end),value).sortedBy { it.begin }) {
            val begin = edit.begin+delta
            val finish = begin+edit.replacement.length
            if (finish > begin && begin >= 0 && finish <= marked.length) {
                marked.setSpan(BackgroundColorSpan(if (OverlayUi.dark(context)) 0xff00584d.toInt() else 0xffd8f6ec.toInt()),begin,finish,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                marked.setSpan(ForegroundColorSpan(OverlayUi.accent(context)),begin,finish,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            delta += edit.replacement.length-(edit.end-edit.begin)
        }
        val text = surfaceText(value,14f).apply { text = marked; setLineHeight(Ui.dp(context,21)); maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END }
        body.addView(text)
        val more = surfaceButton("View more") {
            text.maxLines = if (text.maxLines == 4) Int.MAX_VALUE else 4
        }.apply { gravity = android.view.Gravity.START; setPadding(0,0,0,0) }
        more.setOnClickListener {
            val expand = text.maxLines == 4
            text.maxLines = if (expand) Int.MAX_VALUE else 4
            more.text = if (expand) "View less" else "View more"
        }
        body.addView(more)
        rewriteChips()
        val actions = Ui.row(context).apply { gravity = android.view.Gravity.END }
        actions.addView(surfaceButton("Dismiss") { renderCorrections() })
        actions.addView(surfaceButton("Use this version",true) {
            releasePrompt()
            if (source == previewSource) replace(listOf(Edit(start,end,value))) else rewriteMenu()
        })
        body.addView(actions)
        val tools = Ui.row(context)
        tools.addView(surfaceButton("↻ Try again") { rewrite(rewriteKind,rewriteCustom) })
        tools.addView(surfaceButton("Detect tone") { releasePrompt(); tone() })
        body.addView(tools)
    }

    fun rewrite(kind: String, custom: String = "") {
        presentation = "rewrite"; checkingActive = false
        rewriteKind = kind; rewriteCustom = custom
        if (overlayStyle) {
            if (compact) { compact = false; modeChanged(false) }
            setModeAppearance(); releasePrompt(); heading.text = if (kind == "Custom") "Rewrite" else kind
        }
        if(source.isBlank()) return
        val (start, end) = selectedRange()
        val input = source.substring(start, end)
        status.text = "Preparing a local rewrite…"; body.removeAllViews()
        if (overlayStyle) body.addView(surfaceText("Working on your text…",14f,OverlayUi.MUTED))
        body.addView(surfaceButton("Cancel") { session.cancel(); if (correctionsReady) renderCorrections() else checkNow() })
        session.rewrite(input,kind,custom) { result ->
            result.onSuccess { value ->
                if (overlayStyle) { showRewritePreview(value,start,end,if (kind == "Custom") "Your rewrite" else kind); return@onSuccess }
                preview = value; body.removeAllViews(); status.text = "Review your rewrite"
                body.addView(surfaceText(value,16f))
                Ui.addRow(body,surfaceButton("Apply",true) { replace(listOf(Edit(start,end,value))) },surfaceButton("Copy") {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Rewrite",value)); Ui.toast(context,"Copied")
                },surfaceButton("Dismiss") { renderCorrections() })
            }.onFailure {
                status.text = "Rewrite unavailable for this text. Try a shorter selection."; body.removeAllViews()
                if (overlayStyle) {
                    body.addView(surfaceText(status.text.toString(),14f,OverlayUi.MUTED))
                    body.addView(surfaceButton("Try again") { rewrite(kind,custom) })
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
        if (overlayStyle) { releasePrompt(); setModeAppearance() }
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
