package dev.localwriter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View

enum class WritingCheckState { IDLE, CHECKING, PARTIAL, READY, UNAVAILABLE }

/** A small visible badge inside a larger transparent touch target. */
class WritingWidget(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val logo = context.getDrawable(R.drawable.ic_writer_widget)!!
    private val density = resources.displayMetrics.density
    private val fontSize = 14f * resources.displayMetrics.scaledDensity
    private val font = resources.getFont(R.font.inter_medium)
    private var count = 0
    private var state = WritingCheckState.IDLE

    init { isFocusable = false }

    fun update(count: Int, state: WritingCheckState) {
        this.count = count.coerceAtLeast(0); this.state = state
        contentDescription = when (state) {
            WritingCheckState.CHECKING, WritingCheckState.PARTIAL -> "${this.count} suggestions, checking writing, open writing assistant"
            WritingCheckState.UNAVAILABLE -> "${this.count} suggestions, some checks unavailable, open writing assistant to retry"
            else -> "Open writing assistant, ${this.count} suggestions"
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f
        val radius = 15f * density
        paint.style = Paint.Style.FILL; paint.color = GREEN
        canvas.drawCircle(cx, cy, radius, paint)
        paint.color = Color.WHITE
        when {
            state == WritingCheckState.CHECKING || state == WritingCheckState.PARTIAL -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * density
                paint.strokeCap = Paint.Cap.ROUND
                val r = radius - (if (count > 0) 2f else 5f) * density
                val angle = (SystemClock.uptimeMillis() % 1200) * 360f / 1200f
                canvas.drawArc(RectF(cx-r, cy-r, cx+r, cy+r), angle, 250f, false, paint)
                if (count > 0) drawLabel(canvas, if (count > 99) "99+" else count.toString(), cx, cy)
                if (isAttachedToWindow) postInvalidateDelayed(50)
            }
            state == WritingCheckState.UNAVAILABLE -> {
                drawLabel(canvas, if (count > 0) (if (count > 99) "99+" else count.toString()) else "!", cx, cy)
                paint.style = Paint.Style.FILL; paint.color = 0xffffb347.toInt()
                canvas.drawCircle(cx + 11f * density, cy - 11f * density, 3f * density, paint)
            }
            count > 0 -> drawLabel(canvas, if (count > 99) "99+" else count.toString(), cx, cy)
            else -> {
                val r = (radius * 0.9f).toInt()
                logo.setBounds(cx.toInt()-r, cy.toInt()-r, cx.toInt()+r, cy.toInt()+r)
                logo.draw(canvas)
            }
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, cx: Float, cy: Float) {
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        paint.typeface = font
        paint.textSize = fontSize
        // Preserve the circle with enlarged system fonts and the three-character cap.
        val available = 26f * density
        if (paint.measureText(text) > available) paint.textSize *= available / paint.measureText(text)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, cx, cy - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    companion object { const val GREEN = 0xff027e6f.toInt() }
}
