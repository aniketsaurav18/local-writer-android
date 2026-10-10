package dev.localwriter

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.TextView

/** Overlay-specific typography and controls, without native Button insets. */
object OverlayUi {
    const val INK = 0xff2e2e2e.toInt()
    const val MUTED = 0xff6d758d.toInt()
    const val LINE = 0xffe7e9f0.toInt()
    const val RED = 0xffd43147.toInt()
    const val GREEN = WritingWidget.GREEN

    fun text(c: Context, value: CharSequence, size: Float = 14f, color: Int = INK, medium: Boolean = false) = TextView(c).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
        typeface = c.resources.getFont(if (medium) R.font.inter_medium else R.font.inter_regular)
        setPadding(0,0,0,0)
    }

    fun heading(c: Context, value: String) = text(c,value,16f).apply {
        typeface = Typeface.create(c.resources.getFont(R.font.inter_tight_semibold),700,false)
        setLineHeight(Ui.dp(c,24))
    }

    fun action(c: Context, value: String, primary: Boolean = false, click: () -> Unit) = text(c,value,14f,if (primary) Color.WHITE else INK,true).apply {
        gravity = Gravity.CENTER; minHeight = Ui.dp(c,36)
        setPadding(Ui.dp(c,16),Ui.dp(c,8),Ui.dp(c,16),Ui.dp(c,8))
        val shape = GradientDrawable().apply {
            setColor(if (primary) GREEN else Color.WHITE); cornerRadius = Ui.dp(c,24).toFloat()
            if (!primary) setStroke(Ui.dp(c,1),LINE)
        }
        background = RippleDrawable(ColorStateList.valueOf(0x1a000000),shape,null)
        setOnClickListener { click() }; isFocusable = false
    }

    fun icon(c: Context, glyph: String, description: String, click: () -> Unit) = text(c,glyph,22f,INK).apply {
        gravity = Gravity.CENTER; contentDescription = description
        minWidth = Ui.dp(c,40); minHeight = Ui.dp(c,40)
        background = RippleDrawable(ColorStateList.valueOf(0x10000000),null,Ui.background(Color.WHITE,Ui.dp(c,20)))
        setOnClickListener { click() }; isFocusable = false
    }
}
