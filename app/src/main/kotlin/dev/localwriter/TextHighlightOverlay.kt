package dev.localwriter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Draws only character rectangles supplied by the host app. It never intercepts typing or touches. */
internal class TextHighlightOverlay(private val context: Context, private val wm: WindowManager) {
    private var view: Marks? = null

    fun show(rectangles: List<RectF>, clip: Rect) {
        if (rectangles.isEmpty() || clip.isEmpty) { clear(); return }
        val marks = view ?: Marks(context).also { candidate ->
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.LEFT; title = "Local Writer correction marks" }
            if (runCatching { wm.addView(candidate, params) }.isFailure) return
            view = candidate
        }
        marks.rectangles = rectangles.map(::RectF)
        marks.clip = Rect(clip)
        marks.invalidate()
    }

    fun clear() {
        val old = view ?: return
        old.rectangles = emptyList()
        old.clip = Rect()
        old.invalidate()
        // Keep the transparent, non-touchable window: removing it here would cause a
        // TYPE_WINDOWS_CHANGED -> refresh -> addView loop whenever marks are redrawn.
    }

    fun dispose() {
        val old = view ?: return
        old.rectangles = emptyList()
        runCatching { wm.removeViewImmediate(old) }
        view = null
    }

    private class Marks(context: Context) : View(context) {
        var rectangles: List<RectF> = emptyList()
        var clip = Rect()
        private val screenLocation = IntArray(2)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(218, 53, 65)
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 2
            strokeCap = Paint.Cap.ROUND
        }
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isFocusable = false; isClickable = false
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            getLocationOnScreen(screenLocation)
            canvas.save()
            canvas.translate(-screenLocation[0].toFloat(), -screenLocation[1].toFloat())
            canvas.clipRect(clip)
            for (character in rectangles) {
                val y = character.bottom - paint.strokeWidth / 2
                canvas.drawLine(character.left, y, character.right, y, paint)
            }
            canvas.restore()
        }
    }
}
