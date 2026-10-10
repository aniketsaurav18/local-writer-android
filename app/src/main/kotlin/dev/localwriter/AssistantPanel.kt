package dev.localwriter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView

class AssistantPanel(
    context: Context,
    private val apply: (String, List<Edit>) -> Boolean,
    private val close: () -> Unit,
    private val highlight: (List<Edit>) -> Unit = {},
    private val count: (Int) -> Unit = {},
    private val selection: () -> Pair<Int, Int>? = { null }
) : LinearLayout(context) {
    private val session = AnalysisSession(context)
    private val status: TextView
    private val body: LinearLayout
    private var source = ""
    private var edits = emptyList<Edit>()
    private val ignored = mutableSetOf<String>()
    private var preview: String? = null
    private var selected: Edit? = null
    private val cards = mutableMapOf<Edit, View>()
    init {
        orientation = VERTICAL; setPadding(Ui.dp(context,12),Ui.dp(context,12),Ui.dp(context,12),Ui.dp(context,12)); background = Ui.background()
        val top = Ui.row(context)
        top.addView(Ui.text(context,"Writing assistant",18f,bold=true),LayoutParams(0,-2,1f))
        top.addView(Ui.button(context,"×") { close() }); addView(top)
        status = Ui.text(context,"Local models · English",13f,Ui.MUTED); addView(status)
        Ui.addRow(this,Ui.button(context,"Corrections") { checkNow() },Ui.button(context,"Rewrite") { rewriteMenu() },Ui.button(context,"Tone") { tone() })
        body = Ui.column(context,0); addView(body)
    }
    fun update(text: String, immediate: Boolean = false) {
        if (source == text && !immediate) return
        source = text; preview = null; selected = null; ignored.clear(); edits = emptyList(); highlight(emptyList()); count(0)
        if (source.isBlank()) { session.cancel(); status.text = "Start writing to see suggestions"; body.removeAllViews(); return }
        status.text = "Checking on this device…"; body.removeAllViews()
        session.check(source,if(immediate)0 else 550) { result ->
            result.onSuccess { edits = it; selected = it.firstOrNull(); renderCorrections() }.onFailure { status.text = "Unable to check this text. Tap Corrections to retry." }
        }
    }
    private fun checkNow() = update(source,true)
    private fun renderCorrections() {
        body.removeAllViews(); cards.clear(); val shown = edits.filter { it.key(source) !in ignored }
        if (selected != null && selected !in shown) selected = shown.firstOrNull()
        count(shown.size); highlight(shown)
        status.text = if(shown.isEmpty()) "No suggestions · checked locally" else "${shown.size} suggestion${if(shown.size==1)"" else "s"} · checked locally"
        if (shown.isNotEmpty()) {
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
            val card = Ui.card(body); val before = source.substring(e.begin,e.end)
            cards[e] = card
            val title = "${before.trim().ifEmpty { "Insert" }} → ${e.replacement.trim().ifEmpty { "Remove" }}"
            card.addView(Ui.button(context,title) { selected = if (selected == e) null else e; renderCorrections() })
            if (selected == e) {
                card.background = Ui.background(Color.rgb(247, 251, 249))
                val range = TextLogic.highlightRange(source,e)
                var start = (minOf(range?.first ?: e.begin,e.begin) - 45).coerceAtLeast(0)
                var end = (maxOf(range?.second ?: e.end,e.end) + 45).coerceAtMost(source.length)
                if (start > 0 && source[start].isLowSurrogate()) start--
                if (end < source.length && source[end].isLowSurrogate()) end++
                val contextText = source.substring(start,end)
                val relative = Edit(e.begin-start,e.end-start,e.replacement)
                card.addView(Ui.text(context,"",16f).apply { text = markedText(contextText,listOf(relative)) { selectCard(e) }; movementMethod = LinkMovementMethod.getInstance() })
                val difference = SpannableString("${before.ifBlank { "Insert" }}  →  ${e.replacement.ifEmpty { "Remove" }}")
                val oldLength = before.ifBlank { "Insert" }.length
                difference.setSpan(ForegroundColorSpan(Color.rgb(196,48,61)),0,oldLength,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (before.isNotBlank()) difference.setSpan(StrikethroughSpan(),0,oldLength,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                difference.setSpan(ForegroundColorSpan(Ui.GREEN),oldLength+5,difference.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                card.addView(Ui.text(context,"",17f,bold=true).apply { text = difference })
                card.addView(Ui.text(context,"Suggested grammar or spelling correction",12f,Ui.MUTED))
                Ui.addRow(card,Ui.button(context,"Dismiss") { ignored += e.key(source); renderCorrections() },Ui.button(context,"Accept",true) { replace(listOf(e)) })
                if (before.trim().matches(Regex("[\\p{L}’'-]+"))) card.addView(Ui.button(context,"Add “${before.trim()}” to dictionary") {
                    Prefs(context).dictionary(before,true); ignored += e.key(source); renderCorrections()
                })
            }
        }
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
            var scrollParent = parent
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
        else { status.text = "The field changed. Refresh suggestions before applying."; session.cancel() }
    }
    private fun rewriteMenu() {
        session.cancel(); body.removeAllViews(); status.text = "Rewrite on this device"
        body.addView(Ui.text(context,"Choose how you want your text to sound.",14f,Ui.MUTED))
        for (pair in listOf("Improve" to "Concise", "Professional" to "Friendly", "Expand" to "English"))
            Ui.addRow(body,Ui.button(context,pair.first) { rewrite(pair.first) },Ui.button(context,pair.second) { rewrite(pair.second) })
    }
    fun rewrite(kind: String, custom: String = "") {
        if(source.isBlank()) return
        val (start, end) = selectedRange()
        val input = source.substring(start, end)
        status.text = "Preparing a local rewrite…"; body.removeAllViews()
        body.addView(Ui.button(context,"Cancel") { session.cancel(); renderCorrections() })
        session.rewrite(input,kind,custom) { result ->
            result.onSuccess { value ->
                preview = value; body.removeAllViews(); status.text = "Review your rewrite"
                body.addView(Ui.text(context,value,16f))
                Ui.addRow(body,Ui.button(context,"Apply",true) { replace(listOf(Edit(start,end,value))) },Ui.button(context,"Copy") {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Rewrite",value)); Ui.toast(context,"Copied")
                },Ui.button(context,"Dismiss") { renderCorrections() })
            }.onFailure { status.text = "Rewrite unavailable for this text. Try a shorter selection."; body.removeAllViews() }
        }
    }
    private fun selectedRange(): Pair<Int, Int> {
        val s = selection()
        return if (s != null && s.first in 0..source.length && s.second in 0..source.length && s.first != s.second)
            minOf(s.first, s.second) to maxOf(s.first, s.second) else 0 to source.length
    }
    fun tone() {
        if(source.isBlank())return
        status.text = "Reading tone locally…"; body.removeAllViews()
        body.addView(Ui.button(context,"Cancel") { session.cancel(); renderCorrections() })
        val (start, end) = selectedRange()
        session.tone(source.substring(start,end)) { result -> result.onSuccess { value ->
            status.text = "Tone: $value"; body.removeAllViews(); body.addView(Ui.text(context,"Your writing sounds ${value.lowercase()}.",17f,bold=true))
            Ui.addRow(body,Ui.button(context,"Make professional") { rewrite("Professional") },Ui.button(context,"Make friendly") { rewrite("Friendly") })
        }.onFailure { status.text = "Tone unavailable. Try again with a shorter selection." } }
    }
    fun dispose() { session.cancel(); source = ""; edits = emptyList(); preview = null; selected = null; cards.clear(); ignored.clear() }
}
