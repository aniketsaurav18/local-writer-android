package dev.localwriter

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.*

object Ui {
    const val GREEN = 0xff06765b.toInt(); const val INK = 0xff182d28.toInt(); const val MUTED = 0xff65746f.toInt(); const val PALE = 0xffedf4f0.toInt()
    fun dp(c: Context, n: Int) = (n * c.resources.displayMetrics.density).toInt()
    fun background(color: Int = Color.WHITE, radius: Int = 16) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }
    fun column(c: Context, padding: Int = 16) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c,padding),dp(c,padding),dp(c,padding),dp(c,padding)) }
    fun row(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
    fun text(c: Context, value: String, size: Float = 15f, color: Int = INK, bold: Boolean = false) = TextView(c).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0,dp(c,4),0,dp(c,4)); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    fun button(c: Context, label: String, primary: Boolean = false, action: () -> Unit) = Button(c).apply {
        text = label; isAllCaps = false; textSize = 13f; minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setTextColor(if (primary) Color.WHITE else GREEN); background = background(if(primary) GREEN else PALE, dp(c,10))
        setPadding(dp(c,10),dp(c,8),dp(c,10),dp(c,8)); setOnClickListener { action() }
    }
    fun addRow(parent: LinearLayout, vararg views: View) {
        val row = row(parent.context)
        for (v in views) row.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(parent.context,2),dp(parent.context,4),dp(parent.context,2),dp(parent.context,4)) })
        parent.addView(row)
    }
    fun card(parent: LinearLayout): LinearLayout = column(parent.context,12).apply {
        background = background(); parent.addView(this, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(parent.context,12) })
    }
    fun scroll(c: Context, child: View) = ScrollView(c).apply { isFillViewport = true; addView(child) }
    fun toast(c: Context, value: String) = Toast.makeText(c,value,Toast.LENGTH_SHORT).show()
}
