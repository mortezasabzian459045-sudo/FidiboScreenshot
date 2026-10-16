package com.example.fidiboshot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.View
import kotlin.math.min

/** دکمه‌ی گرد با حلقه‌ی RGB چرخان؛ متن یا آیکون ناحیه (مربع خط‌چین) می‌کشد */
class RgbButton(context: Context) : View(context) {

    var label: String = ""
        set(v) { field = v; invalidate() }
    var fillColor: Int = Color.parseColor("#202124")
        set(v) { field = v; invalidate() }
    var textColor: Int = Color.WHITE
        set(v) { field = v; invalidate() }
    var textSizeSp: Float = 16f
        set(v) { field = v; invalidate() }
    /** اگر true باشد به جای متن، مربع خط‌چین ناحیه کشیده می‌شود */
    var cropIcon: Boolean = false
        set(v) { field = v; invalidate() }
    var iconColor: Int = Color.WHITE
        set(v) { field = v; invalidate() }
    /** زاویه‌ی چرخش حلقه؛ از بیرون توسط انیمیشن تنظیم می‌شود */
    var angle: Float = 0f

    private val d = resources.displayMetrics.density
    private val ringW = 2.5f * d
    private val matrix = Matrix()
    private var shader: SweepGradient? = null

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringW
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
    }
    private val rect = RectF()

    private val rgb = intArrayOf(
        0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
        0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt()
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shader = SweepGradient(w / 2f, h / 2f, rgb, null)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f

        fillPaint.color = fillColor
        canvas.drawCircle(cx, cy, r - ringW, fillPaint)

        shader?.let {
            matrix.setRotate(angle, cx, cy)
            it.setLocalMatrix(matrix)
            ringPaint.shader = it
            canvas.drawCircle(cx, cy, r - ringW / 2f, ringPaint)
        }

        if (cropIcon) {
            val s = r * 0.46f
            rect.set(cx - s, cy - s, cx + s, cy + s)
            iconPaint.color = iconColor
            iconPaint.pathEffect = DashPathEffect(floatArrayOf(4f * d, 3f * d), 0f)
            canvas.drawRoundRect(rect, 2f * d, 2f * d, iconPaint)
        } else {
            textPaint.color = textColor
            textPaint.textSize = textSizeSp * d
            val y = cy - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(label, cx, y, textPaint)
        }
    }
}
