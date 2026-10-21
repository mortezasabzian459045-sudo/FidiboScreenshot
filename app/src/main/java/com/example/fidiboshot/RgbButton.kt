package com.example.fidiboshot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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
    /** اگر true باشد سه خط افقی (نشان اسکرین طولی) کشیده می‌شود */
    var linesIcon: Boolean = false
        set(v) { field = v; invalidate() }
    /** اگر true باشد آیکون دوربین (حالت عکس) کشیده می‌شود */
    var cameraIcon: Boolean = false
        set(v) { field = v; invalidate() }
    /** اگر true باشد آیکون فیلم/دوربین فیلمبرداری (حالت ویدئو) کشیده می‌شود */
    var filmIcon: Boolean = false
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
    private val path = Path()

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

        when {
            cropIcon -> {
                val s = r * 0.46f
                rect.set(cx - s, cy - s, cx + s, cy + s)
                iconPaint.color = iconColor
                iconPaint.pathEffect = DashPathEffect(floatArrayOf(4f * d, 3f * d), 0f)
                canvas.drawRoundRect(rect, 2f * d, 2f * d, iconPaint)
            }
            linesIcon -> {
                val s = r * 0.42f
                val gap = 4.2f * d
                iconPaint.color = iconColor
                iconPaint.pathEffect = null
                iconPaint.strokeCap = Paint.Cap.ROUND
                for (i in -1..1) {
                    val yy = cy + i * gap
                    canvas.drawLine(cx - s, yy, cx + s, yy, iconPaint)
                }
            }
            cameraIcon -> {
                iconPaint.color = iconColor
                iconPaint.pathEffect = null
                iconPaint.strokeCap = Paint.Cap.ROUND
                iconPaint.strokeJoin = Paint.Join.ROUND
                val w = r * 0.80f
                val h = r * 0.54f
                val top = cy - h / 2f + r * 0.08f
                rect.set(cx - w / 2f, top, cx + w / 2f, top + h)
                canvas.drawRoundRect(rect, 3f * d, 3f * d, iconPaint)
                canvas.drawCircle(cx, rect.centerY(), h * 0.27f, iconPaint)
                canvas.drawLine(cx - w * 0.16f, top - 2.4f * d, cx + w * 0.16f, top - 2.4f * d, iconPaint)
            }
            filmIcon -> {
                iconPaint.color = iconColor
                iconPaint.pathEffect = null
                iconPaint.strokeCap = Paint.Cap.ROUND
                iconPaint.strokeJoin = Paint.Join.ROUND
                val bw = r * 0.52f
                val bh = r * 0.46f
                val left = cx - r * 0.42f
                rect.set(left, cy - bh / 2f, left + bw, cy + bh / 2f)
                canvas.drawRoundRect(rect, 2.5f * d, 2.5f * d, iconPaint)
                path.reset()
                path.moveTo(rect.right + 1.5f * d, cy)
                path.lineTo(cx + r * 0.46f, cy - bh * 0.5f)
                path.lineTo(cx + r * 0.46f, cy + bh * 0.5f)
                path.close()
                canvas.drawPath(path, iconPaint)
            }
            else -> {
                textPaint.color = textColor
                textPaint.textSize = textSizeSp * d
                val y = cy - (textPaint.ascent() + textPaint.descent()) / 2f
                canvas.drawText(label, cx, y, textPaint)
            }
        }
    }
}
