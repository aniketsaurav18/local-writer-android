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
    private val inlineDrag: (View) -> Unit = {}
) : LinearLayout(context) {
    private val session = AnalysisSession(context)
    private val status: TextView
    private val body: LinearLayout
    private val header: LinearLayout
    private val heading: TextView
    private val toolbar: LinearLayout
    private var compact = overlayStyle
    private var panelState = WritingCheckState.IDLE
    private var source = ""
    private var edits = emptyList<Edit>()
    private val ignored = mutableSetOf<String>()
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
        status.visibility = GONE
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
        source = text; preview = null; selected = null; ignored.clear(); edits = emptyList(); correctionsReady = false
        if (source.isBlank()) {
            reportState(WritingCheckState.IDLE); highlight(emptyList()); count(0)
            status.text = "Start writing to see suggestions"; showCheckMessage(status.text.toString()); return
        }
        if (knownCorrections != null) {
            edits = knownCorrections; selected = edits.firstOrNull(); correctionsReady = true
            reportState(WritingCheckState.READY); renderCorrections(); return
        }
        reportState(WritingCheckState.CHECKING); highlight(emptyList()); count(0)
        status.text = if (overlayStyle) "Checking your writing…" else "Checking on this device…"; showCheckMessage(status.text.toString())
        session.check(source,if(immediate)0 else 550) { result ->
            result.onSuccess {
                edits = it; selected = it.firstOrNull(); correctionsReady = true
                reportState(WritingCheckState.READY); renderCorrections()
            }.onFailure {
                reportState(WritingCheckState.UNAVAILABLE); count(0)
                status.text = "Unable to check this text. Tap Corrections to retry."
                if (overlayStyle) showCheckMessage("Couldn't check. Tap to retry.")
            }
        }
    }
    fun currentCorrections(text: String): List<Edit>? =
        if (source == text && correctionsReady) edits.filter { it.key(source) !in ignored } else null

    private fun issueTitle(e: Edit): String {
        val old = source.substring(e.begin,e.end).trim()
        val replacement = e.replacement.trim()
        return when {
            old.isEmpty() && replacement.lowercase() in listOf("a","an","the") -> "Add article"
            old.isEmpty() -> "Add missing text"
            replacement.isEmpty() -> "Remove extra text"
            (old + replacement).none(Char::isLetterOrDigit) -> "Punctuation"
            else -> "Suggested correction"
        }
    }

    private fun shortEdit(e: Edit): SpannableString {
        val old = source.substring(e.begin,e.end).trim()
        val replacement = e.replacement.trim()
        val gap = if (old.isNotEmpty() && replacement.isNotEmpty()) " " else ""
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
            inlineRow("No suggestions","You're all set",0,{ expand(true) },close); return
        }
        selected = e
        val label = shortEdit(e)
        val paint = TextPaint().apply {
            textSize = 13f * resources.displayMetrics.scaledDensity
            typeface = resources.getFont(R.font.inter_regular)
        }
        val wide = paint.measureText(label.toString()) > resources.displayMetrics.widthPixels / 2f - Ui.dp(context,36)
        inlineRow(if (wide) "Review suggestions" else label,issueTitle(e),shown.size,
            { if (wide) expand(true) else replace(listOf(e)) },
            { ignored += e.key(source); renderCorrections() })
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
        if (overlayStyle) heading.text = "Options"
        if (overlayStyle && compact) { compact = false; setModeAppearance(); modeChanged(false) }
        status.text = "Writing assistant options"; body.removeAllViews()
        val shown = currentCorrections(source)
        if (!shown.isNullOrEmpty()) body.addView(surfaceButton("Accept all suggestions",true) { replace(shown) })
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
        if (overlayStyle) heading.text = "Review suggestions"
        body.removeAllViews(); cards.clear(); val shown = edits.filter { it.key(source) !in ignored }
        if (selected != null && selected !in shown) selected = shown.firstOrNull()
        count(shown.size); highlight(shown)
        status.text = (if(shown.isEmpty()) "No suggestions" else "${shown.size} suggestion${if(shown.size==1)"" else "s"}") + if (overlayStyle) "" else " · checked locally"
        if (overlayStyle && compact) { renderInline(shown); return }
        if (overlayStyle && shown.isEmpty()) {
            body.addView(OverlayUi.text(context,"You're all set",16f,OverlayUi.INK,true).apply {
                gravity = android.view.Gravity.CENTER; setPadding(0,Ui.dp(context,32),0,Ui.dp(context,8))
            })
            body.addView(OverlayUi.text(context,"No suggestions for this text",14f,OverlayUi.MUTED).apply { gravity = android.view.Gravity.CENTER })
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
            body.addView(Ui.button(context,"Accept all",true) { replace(shown) })
        }
        for (e in shown) {
            if (overlayStyle) { renderRevisionCard(e); continue }
            val card = Ui.card(body); val before = source.substring(e.begin,e.end)
            if (overlayStyle) (card.layoutParams as LayoutParams).bottomMargin = 0
            cards[e] = card
            val title = "${before.trim().ifEmpty { "Insert" }} → ${e.replacement.trim().ifEmpty { "Remove" }}"
            if (overlayStyle && selected == e) {
                val category = Ui.row(context)
                category.addView(View(context).apply { background = Ui.background(Color.rgb(218,53,65),Ui.dp(context,6)) },
                    LayoutParams(Ui.dp(context,12),Ui.dp(context,12)).apply { marginEnd = Ui.dp(context,4) })
                category.addView(Ui.text(context,"Grammar and spelling",12f,Ui.MUTED))
                category.contentDescription = "Collapse correction"
                category.setOnClickListener { selected = null; renderCorrections() }
                card.addView(category)
                card.addView(View(context),LayoutParams(1,Ui.dp(context,12)))
            } else card.addView(Ui.button(context,title) { selected = if (selected == e) null else e; renderCorrections() }.apply {
                if (overlayStyle) {
                    background = null; gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    setTextColor(Ui.INK)
                }
            })
            if (selected == e) {
                card.background = Ui.background(if (overlayStyle) Color.WHITE else Color.rgb(247, 251, 249))
                val range = TextLogic.highlightRange(source,e)
                var start = (minOf(range?.first ?: e.begin,e.begin) - 45).coerceAtLeast(0)
                var end = (maxOf(range?.second ?: e.end,e.end) + 45).coerceAtMost(source.length)
                if (start > 0 && source[start].isLowSurrogate()) start--
                if (end < source.length && source[end].isLowSurrogate()) end++
                val contextText = source.substring(start,end)
                val relative = Edit(e.begin-start,e.end-start,e.replacement)
                card.addView(Ui.text(context,"",16f).apply {
                    text = markedText(contextText,listOf(relative)) { selectCard(e) }; movementMethod = LinkMovementMethod.getInstance()
                    if (overlayStyle) setPadding(0,0,0,Ui.dp(context,16))
                })
                val difference = SpannableString("${before.ifBlank { "Insert" }}  →  ${e.replacement.ifEmpty { "Remove" }}")
                val oldLength = before.ifBlank { "Insert" }.length
                difference.setSpan(ForegroundColorSpan(Color.rgb(196,48,61)),0,oldLength,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (before.isNotBlank()) difference.setSpan(StrikethroughSpan(),0,oldLength,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                difference.setSpan(ForegroundColorSpan(if (overlayStyle) WritingWidget.GREEN else Ui.GREEN),oldLength+5,difference.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                card.addView(Ui.text(context,"",17f,bold=true).apply { text = difference })
                if (!overlayStyle) card.addView(Ui.text(context,"Suggested grammar or spelling correction",12f,Ui.MUTED))
                val dismiss = Ui.button(context,"Dismiss") { ignored += e.key(source); renderCorrections() }
                val accept = Ui.button(context,"Accept",true) { replace(listOf(e)) }
                if (overlayStyle) {
                    dismiss.setTextColor(WritingWidget.GREEN)
                    dismiss.background = GradientDrawable().apply {
                        setColor(Color.WHITE); cornerRadius = Ui.dp(context,24).toFloat()
                        setStroke(Ui.dp(context,1),Color.rgb(204,213,210))
                    }
                    accept.background = Ui.background(WritingWidget.GREEN,Ui.dp(context,24))
                    val actions = Ui.row(context).apply {
                        gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                        setPadding(0,Ui.dp(context,4),0,Ui.dp(context,4))
                    }
                    actions.addView(dismiss); actions.addView(View(context),LayoutParams(Ui.dp(context,8),1)); actions.addView(accept)
                    card.addView(actions)
                } else Ui.addRow(card,dismiss,accept)
                if (before.trim().matches(Regex("[\\p{L}’'-]+"))) card.addView(Ui.button(context,"Add “${before.trim()}” to dictionary") {
                    Prefs(context).dictionary(before,true); ignored += e.key(source); renderCorrections()
                })
            }
            if (overlayStyle) body.addView(View(context).apply { setBackgroundColor(Color.rgb(231,235,234)) },
                LayoutParams(-1,Ui.dp(context,1)).apply { setMargins(Ui.dp(context,12),0,Ui.dp(context,12),0) })
        }
    }

    private fun contextEdit(e: Edit, around: Int): SpannableString {
        var start = (e.begin-around).coerceAtLeast(0)
        var end = (e.end+around).coerceAtMost(source.length)
        if (start > 0 && source[start].isLowSurrogate()) start--
        if (end < source.length && source[end].isLowSurrogate()) end++
        val old = source.substring(e.begin,e.end)
        val separator = if (old.isNotEmpty() && e.replacement.isNotEmpty() && !old.last().isWhitespace()) " " else ""
        val prefix = (if (start > 0) "…" else "") + source.substring(start,e.begin)
        val text = SpannableString(prefix + old + separator + e.replacement + source.substring(e.end,end) + if (end < source.length) "…" else "")
        val oldStart = prefix.length; val oldEnd = oldStart+old.length
        if (oldEnd > oldStart) {
            text.setSpan(ForegroundColorSpan(OverlayUi.RED),oldStart,oldEnd,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StrikethroughSpan(),oldStart,oldEnd,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val newStart = oldEnd+separator.length; val newEnd = newStart+e.replacement.length
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
            setLineHeight(Ui.dp(context,24))
            if (!expanded) { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            setPadding(0,0,0,Ui.dp(context,16))
        })
        card.setOnClickListener { if (expanded) { selected = null; renderCorrections() } else selectCard(e) }
        card.contentDescription = if (expanded) "Collapse correction" else "Expand correction"
        if (expanded) {
            val actions = Ui.row(context).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                setPadding(0,Ui.dp(context,4),0,Ui.dp(context,4))
            }
            val dismiss = OverlayUi.action(context,"Dismiss") { ignored += e.key(source); renderCorrections() }
            val word = source.substring(e.begin,e.end).trim()
            if (word.matches(Regex("[\\p{L}’'-]+"))) dismiss.setOnLongClickListener {
                Prefs(context).dictionary(word,true); ignored += e.key(source); renderCorrections(); true
            }
            actions.addView(dismiss); actions.addView(View(context),LayoutParams(Ui.dp(context,8),1))
            actions.addView(OverlayUi.action(context,"Accept",true) { replace(listOf(e)) })
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
        val before = source
        val after = TextLogic.apply(before, changes)
        if(apply(before,changes)) { if (source == before) update(after,true) }
        else {
            status.text = "The field changed. Refresh suggestions before applying."; session.cancel()
            if (overlayStyle) {
                correctionsReady = false; reportState(WritingCheckState.UNAVAILABLE)
                count(0); highlight(emptyList()); showCheckMessage("Text changed. Tap to refresh.")
            }
        }
    }
    private fun rewriteMenu() {
        if (overlayStyle) heading.text = "Rewrite"
        if (overlayStyle && compact) { compact = false; setModeAppearance(); modeChanged(false) }
        session.cancel(); body.removeAllViews(); status.text = "Rewrite on this device"
        body.addView(surfaceText("Choose how you want your text to sound.",14f,Ui.MUTED))
        if (overlayStyle) body.addView(OverlayUi.action(context,"Detect tone") { tone() })
        for (pair in listOf("Improve" to "Concise", "Professional" to "Friendly", "Expand" to "English"))
            Ui.addRow(body,surfaceButton(pair.first) { rewrite(pair.first) },surfaceButton(pair.second) { rewrite(pair.second) })
    }
    fun rewrite(kind: String, custom: String = "") {
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
    fun dispose() { session.cancel(); source = ""; edits = emptyList(); preview = null; selected = null; correctionsReady = false; cards.clear(); ignored.clear() }
}
