package com.example.fidiboshot

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.min

/**
 * تطبیق پیکسلی برای اسکرین طولی؛ همه‌ی توابع روی آرایه‌ی پیکسل‌های برش‌خورده
 * (IntArray با قالب ARGB) کار می‌کنند و باید روی ترد پس‌زمینه صدا زده شوند.
 * هیچ کتابخانه‌ای استفاده نمی‌شود؛ ZNCC دستی با نمونه‌برداری سبک.
 */
object OverlapMatcher {

    /** ارتفاع نوار تطبیق (پیکسل) */
    const val BAND_H = 100

    /** حداقل نمره‌ی ZNCC برای پذیرش تطبیق */
    const val ZNCC_MIN = 0.85f

    /** نوار با پراکندگی کمتر از این مقدار «خالی» در نظر گرفته می‌شود */
    const val BLANK_SIGMA = 4.5f

    private const val COL_STEP = 6     // نمونه‌برداری ستونی
    private const val ROW_STEP = 2     // نمونه‌برداری ردیفی داخل نوار
    private const val EDGE_SKIP = 40   // حذف نوارهای کناری (اسکرول‌بار) از تطبیق

    fun lum(p: Int): Int =
        (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

    /** نوار آماده برای تطبیق؛ norm میانگین‌گیری و نرمال شده است */
    class Band(val cols: IntArray, val rows: Int, val h: Int, val norm: FloatArray, val sigma: Float)

    /** نوار را از ردیف y0 به ارتفاع h می‌سازد؛ اگر ستون کافی نبود null */
    fun extractBand(px: IntArray, w: Int, y0: Int, h: Int): Band? {
        if (h < 8) return null
        if (w < EDGE_SKIP * 2 + COL_STEP * 8) return null
        val cols = ArrayList<Int>(w / COL_STEP + 2)
        var x = EDGE_SKIP
        while (x < w - EDGE_SKIP) {
            cols.add(x)
            x += COL_STEP
        }
        if (cols.size < 8) return null
        val rows = (h + ROW_STEP - 1) / ROW_STEP
        val raw = FloatArray(cols.size * rows)
        var k = 0
        var r = 0
        while (r < h) {
            val base = (y0 + r) * w
            for (i in cols.indices) raw[k++] = lum(px[base + cols[i]]).toFloat()
            r += ROW_STEP
        }
        var sum = 0.0
        for (v in raw) sum += v
        val mean = sum / raw.size
        var vs = 0.0
        for (v in raw) {
            val d = v - mean
            vs += d * d
        }
        val sigma = sqrt(vs / raw.size).toFloat()
        if (sigma < 0.5f) return Band(cols.toIntArray(), rows, h, raw, sigma)
        val norm = FloatArray(raw.size) { ((raw[it] - mean) / sigma).toFloat() }
        return Band(cols.toIntArray(), rows, h, norm, sigma)
    }

    /**
     * بهترین مکان نوار را در قاب بین yMin تا yMax (شامل) می‌گردد.
     * خروجی: ردیف شروع نوار در قاب، یا -1 اگر تطبیق قابل قبولی نبود.
     */
    fun findOffset(frame: IntArray, w: Int, fh: Int, band: Band, yMin: Int, yMax: Int): Int {
        if (band.sigma < BLANK_SIGMA) return -1
        val hi = min(yMax, fh - band.h)
        if (hi < yMin) return -1
        val cols = band.cols
        val n = band.norm.size
        val nn = n.toDouble()
        var bestY = -1
        var bestScore = ZNCC_MIN.toDouble()
        var y = yMin
        while (y <= hi) {
            var s = 0.0
            var ss = 0.0
            var dot = 0.0
            var k = 0
            var r = 0
            while (r < band.h) {
                val base = (y + r) * w
                var ci = 0
                while (ci < cols.size) {
                    val v = lum(frame[base + cols[ci]]).toDouble()
                    s += v
                    ss += v * v
                    dot += band.norm[k++] * v
                    ci++
                }
                r += ROW_STEP
            }
            val mu = s / nn
            val variance = ss / nn - mu * mu
            if (variance > 1e-4) {
                val score = dot / (sqrt(variance) * nn)
                if (score > bestScore) {
                    bestScore = score
                    bestY = y
                }
            }
            y++
        }
        return bestY
    }

    /**
     * ردیف‌های یکسانِ ابتدا و انتهای دو قاب (نوارهای چسبان مثل هدر/فوتر) را پیدا می‌کند.
     * خروجی: [پایان نوار ثابت بالا، شروع نوار ثابت پایین] ؛ ناحیه‌ی مفید بین این دو است.
     */
    fun fixedBands(a: IntArray, b: IntArray, w: Int, h: Int, cap: Int): IntArray {
        val topCap = if (cap > h / 2) h / 2 else cap
        var t = 0
        while (t < topCap && rowEquals(a, b, w, t)) t++
        val botCap = h - topCap
        var bt = h
        while (bt > botCap && rowEquals(a, b, w, bt - 1)) bt--
        if (bt - t < h / 3) return intArrayOf(0, h)   // تشخیص مشکوک؛ نادیده گرفته می‌شود
        return intArrayOf(t, bt)
    }

    private fun rowEquals(a: IntArray, b: IntArray, w: Int, y: Int): Boolean {
        val base = y * w
        var x = 0
        while (x < w) {
            val pa = a[base + x]
            val pb = b[base + x]
            val d = abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF)) +
                abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF)) +
                abs((pa and 0xFF) - (pb and 0xFF))
            if (d > 12) return false
            x += 8
        }
        return true
    }

    /** آیا دو قاب تقریباً یکسان‌اند؟ (تشخیص ثبات صفحه یا بی‌حرکتی محتوا) */
    fun nearIdentical(a: IntArray, b: IntArray, w: Int, h: Int): Boolean {
        var diff = 0
        var total = 0
        var y = 0
        while (y < h) {
            val base = y * w
            var x = 0
            while (x < w) {
                val pa = a[base + x]
                val pb = b[base + x]
                val d = abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF)) +
                    abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF)) +
                    abs((pa and 0xFF) - (pb and 0xFF))
                if (d > 30) diff++
                total++
                x += 16
            }
            y += 4
        }
        return total > 0 && diff * 1000 <= total * 3   // حداکثر ۰/۳٪ متفاوت
    }

    /** صفحه‌ی کاملاً سیاه (اپ‌های محافظت‌شده با FLAG_SECURE) */
    fun allBlack(px: IntArray, w: Int, h: Int): Boolean {
        var y = 0
        while (y < h) {
            val base = y * w
            var x = 0
            while (x < w) {
                if ((px[base + x] and 0x00FFFFFF) != 0) return false
                x += 8
            }
            y += 4
        }
        return true
    }
}
