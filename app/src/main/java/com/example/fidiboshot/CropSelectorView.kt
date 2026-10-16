package com.example.fidiboshot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** قاب قابل کشیدن و تغییر اندازه برای انتخاب ناحیه‌ی اسکرین‌شات */
class CropSelectorView(context: Context) : View(context) {

    /** مقدار اولیه [l,t,r,b] به صورت کسری ؛ قبل از اولین layout تنظیم شود */
    var initial: FloatArray? = null

    private val rect = RectF()
    private var ready = false
    private val d = resources.displayMetrics.density

    private val dim = Paint().apply { color = Color.parseColor("#99000000") }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * d
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A73E8") }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val hitSlop = 28 * d
    private val minSize = 80 * d

    private companion object {
        const val LEFT = 1
        const val TOP = 2
        const val RIGHT = 4
        const val BOTTOM = 8
        const val MOVE = 16
    }

    private var mode = 0
    private var lastX = 0f
    private var lastY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (!ready && w > 0 && h > 0) {
            val f = initial ?: floatArrayOf(0.1f, 0.2f, 0.9f, 0.8f)
            rect.set(f[0] * w, f[1] * h, f[2] * w, f[3] * h)
            ready = true
        }
    }

    /** [left, top, right, bottom] نسبت به اندازه‌ی کل صفحه */
    fun fractions(): FloatArray = floatArrayOf(
        (rect.left / width).coerceIn(0f, 1f),
        (rect.top / height).coerceIn(0f, 1f),
        (rect.right / width).coerceIn(0f, 1f),
        (rect.bottom / height).coerceIn(0f, 1f)
    )

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.clipOutRect(rect)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.restore()

        canvas.drawRect(rect, border)

        val mx = (rect.left + rect.right) / 2
        val my = (rect.top + rect.bottom) / 2
        val points = arrayOf(
            rect.left to rect.top, rect.right to rect.top,
            rect.left to rect.bottom, rect.right to rect.bottom,
            mx to rect.top, mx to rect.bottom,
            rect.left to my, rect.right to my
        )
        for ((i, p) in points.withIndex()) {
            val r = if (i < 4) 11 * d else 8 * d
            canvas.drawCircle(p.first, p.second, r, handleRing)
            canvas.drawCircle(p.first, p.second, r - 3 * d, handleFill)
        }
    }

    private fun hitTest(x: Float, y: Float): Int {
        val nearL = abs(x - rect.left) <= hitSlop
        val nearR = abs(x - rect.right) <= hitSlop
        val nearT = abs(y - rect.top) <= hitSlop
        val nearB = abs(y - rect.bottom) <= hitSlop
        val inY = y >= rect.top - hitSlop && y <= rect.bottom + hitSlop
        val inX = x >= rect.left - hitSlop && x <= rect.right + hitSlop

        var m = 0
        if (nearL && inY) m = m or LEFT
        if (nearR && inY) m = m or RIGHT
        if (nearT && inX) m = m or TOP
        if (nearB && inX) m = m or BOTTOM
        if (m == 0 && rect.contains(x, y)) m = MOVE
        return m
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = hitTest(e.x, e.y)
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 0) return true
                val dx = e.x - lastX
                val dy = e.y - lastY
                lastX = e.x; lastY = e.y

                if (mode == MOVE) {
                    val w = rect.width()
                    val h = rect.height()
                    val nl = (rect.left + dx).coerceIn(0f, width - w)
                    val nt = (rect.top + dy).coerceIn(0f, height - h)
                    rect.set(nl, nt, nl + w, nt + h)
                } else {
                    if (mode and LEFT != 0) rect.left = (rect.left + dx).coerceIn(0f, rect.right - minSize)
                    if (mode and RIGHT != 0) rect.right = (rect.right + dx).coerceIn(rect.left + minSize, width.toFloat())
                    if (mode and TOP != 0) rect.top = (rect.top + dy).coerceIn(0f, rect.bottom - minSize)
                    if (mode and BOTTOM != 0) rect.bottom = (rect.bottom + dy).coerceIn(rect.top + minSize, height.toFloat())
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
        }
        return true
    }
}
