package com.example.fidiboshot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * خط افقی قابل کشیدن برای نشان‌گذاری ابتدا/انتهای اسکرین طولی.
 * خودِ ویو فقط رسم می‌کند؛ جابه‌جایی را با onDrag به سرویس می‌دهد و
 * سرویس جای آن را روی پنجره تنظیم می‌کند (با محدودکردن به ناحیه‌ی محتوا).
 */
class MarkerLineView(context: Context) : View(context) {

    var label: String = ""
        set(v) {
            field = v
            invalidate()
        }
    var lineColor: Int = Color.WHITE
        set(v) {
            field = v
            invalidate()
        }

    /** کشیدن عمودی؛ مقدار جابه‌جایی از آخرین رویداد داده می‌شود */
    var onDrag: ((dy: Float) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2.5f * d }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 13f * d
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val rect = RectF()
    private var lastY = 0f

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        linePaint.color = lineColor
        canvas.drawLine(0f, cy, width.toFloat(), cy, linePaint)

        // دستگیره‌ی وسط
        val chipW = 84f * d
        val chipH = 34f * d
        rect.set(width / 2f - chipW / 2f, cy - chipH / 2f, width / 2f + chipW / 2f, cy + chipH / 2f)
        chipPaint.color = lineColor
        canvas.drawRoundRect(rect, chipH / 2f, chipH / 2f, chipPaint)

        val ty = cy - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(label, width / 2f, ty, textPaint)

        // فلش‌های کوچک بالا و پایین دو طرف دستگیره
        val axL = width / 2f - chipW / 2f - 14f * d
        val axR = width / 2f + chipW / 2f + 14f * d
        drawArrow(canvas, axL, cy, up = true)
        drawArrow(canvas, axR, cy, up = false)
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, up: Boolean) {
        val s = 4.5f * d
        val dir = if (up) -1 else 1
        val p = android.graphics.Path()
        p.moveTo(cx, cy + dir * s)
        p.lineTo(cx - s * 0.8f, cy - dir * s * 0.5f)
        p.lineTo(cx + s * 0.8f, cy - dir * s * 0.5f)
        p.close()
        canvas.drawPath(p, arrowPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastY = e.y
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - lastY
                lastY = e.y
                if (abs(dy) > 0.5f) onDrag?.invoke(dy)
            }
            MotionEvent.ACTION_UP -> performClick()
            MotionEvent.ACTION_CANCEL -> { /* هیچ */ }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
