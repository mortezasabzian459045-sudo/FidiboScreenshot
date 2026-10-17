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

    /** ارتفاع نوار «اثر انگشت» خط ابتدا/پایان (بلندتر = یکتاتر) */
    const val REF_BAND_H = 160

    /** اختلاف روشنایی کمتر از این بین دو قاب = پیکسل ثابت (والپیپر، هدر، دکمه‌ی شناور) */
    private const val STATIC_DIFF = 3

    /** حداقل تعداد نمونه‌ی متحرک برای ساختن نوار */
    private const val MIN_ACTIVE = 400

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
    class Band(
        val cols: IntArray, val rows: Int, val h: Int, val norm: FloatArray, val sigma: Float,
        /** اگر null نباشد فقط همین نمونه‌ها (ردیف/ستون) در تطبیق شرکت می‌کنند */
        val selR: IntArray? = null, val selX: IntArray? = null
    )

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
     * مثل extractBand ولی فقط نمونه‌های پرکنتراست (لبه‌ی متن/حباب/عکس).
     * پس‌زمینه‌ی یکدست و والپیپر نرم داخل اثر انگشت نمی‌آید تا بعد از اسکرول
     * هنوز بتوان همان محتوا را پیدا کرد.
     */
    fun extractDetailBand(px: IntArray, w: Int, y0: Int, h: Int): Band? {
        if (h < 8 || y0 < 0 || w <= 0) return null
        if (y0 + h > px.size / w) return null
        if (w < EDGE_SKIP * 2 + COL_STEP * 8) return null
        val cols = ArrayList<Int>(w / COL_STEP + 2)
        var x = EDGE_SKIP
        while (x < w - EDGE_SKIP) {
            cols.add(x)
            x += COL_STEP
        }
        if (cols.size < 8) return null
        val rowsN = (h + ROW_STEP - 1) / ROW_STEP
        val cap = cols.size * rowsN
        val rawV = FloatArray(cap)
        val selR = IntArray(cap)
        val selX = IntArray(cap)
        var n = 0
        var r = 0
        while (r < h) {
            val base = (y0 + r) * w
            for (i in cols.indices) {
                val c = cols[i]
                val l = lum(px[base + c])
                val lR = if (c + 2 < w) lum(px[base + c + 2]) else l
                val lD = if (r + 2 < h) lum(px[(y0 + r + 2) * w + c]) else l
                if (abs(l - lR) > 14 || abs(l - lD) > 14) {
                    rawV[n] = l.toFloat()
                    selR[n] = r
                    selX[n] = c
                    n++
                }
            }
            r += ROW_STEP
        }
        if (n < 180) return null
        var sum = 0.0
        for (i in 0 until n) sum += rawV[i]
        val mean = sum / n
        var vs = 0.0
        for (i in 0 until n) {
            val d = rawV[i] - mean
            vs += d * d
        }
        val sigma = sqrt(vs / n).toFloat()
        if (sigma < 0.5f) return null
        val norm = FloatArray(n) { ((rawV[it] - mean) / sigma).toFloat() }
        return Band(cols.toIntArray(), rowsN, h, norm, sigma, selR.copyOf(n), selX.copyOf(n))
    }

    /**
     * نوار را از ردیف y0 قاب prev می‌سازد ولی فقط از پیکسل‌هایی که نسبت به قاب cur «تغییر کرده‌اند».
     * پیکسل‌های ثابت (والپیپر تلگرام، هدر/فوتر چسبان، دکمه‌های شناور) در تطبیق شرکت نمی‌کنند؛
     * وگرنه تطبیق به جای حرکت واقعی محتوا، «بدون حرکت» را پیدا می‌کند.
     */
    fun extractMovingBand(prev: IntArray, cur: IntArray, w: Int, y0: Int, h: Int): Band? {
        if (h < 8) return null
        if (w < EDGE_SKIP * 2 + COL_STEP * 8) return null
        val cols = ArrayList<Int>(w / COL_STEP + 2)
        var x = EDGE_SKIP
        while (x < w - EDGE_SKIP) {
            cols.add(x)
            x += COL_STEP
        }
        if (cols.size < 8) return null
        val rowsN = (h + ROW_STEP - 1) / ROW_STEP
        val cap = cols.size * rowsN
        val rawV = FloatArray(cap)
        val selR = IntArray(cap)
        val selX = IntArray(cap)
        var n = 0
        var r = 0
        while (r < h) {
            val base = (y0 + r) * w
            for (i in cols.indices) {
                val c = cols[i]
                val pl = lum(prev[base + c])
                val cl = lum(cur[base + c])
                if (abs(pl - cl) > STATIC_DIFF) {
                    rawV[n] = pl.toFloat()
                    selR[n] = r
                    selX[n] = c
                    n++
                }
            }
            r += ROW_STEP
        }
        if (n < MIN_ACTIVE) return Band(cols.toIntArray(), rowsN, h, FloatArray(0), 0f)
        var sum = 0.0
        for (i in 0 until n) sum += rawV[i]
        val mean = sum / n
        var vs = 0.0
        for (i in 0 until n) {
            val d = rawV[i] - mean
            vs += d * d
        }
        val sigma = sqrt(vs / n).toFloat()
        if (sigma < 0.5f) return Band(cols.toIntArray(), rowsN, h, FloatArray(0), sigma)
        val norm = FloatArray(n) { ((rawV[it] - mean) / sigma).toFloat() }
        return Band(cols.toIntArray(), rowsN, h, norm, sigma, selR.copyOf(n), selX.copyOf(n))
    }

    /**
     * بهترین مکان نوار را در قاب بین yMin تا yMax (شامل) می‌گردد.
     * خروجی: ردیف شروع نوار در قاب، یا -1 اگر نمره‌ی تطبیق از minScore کمتر بود.
     */
    fun findOffset(
        frame: IntArray, w: Int, fh: Int, band: Band, yMin: Int, yMax: Int,
        minScore: Float = ZNCC_MIN
    ): Int {
        if (band.sigma < BLANK_SIGMA) return -1
        val hi = min(yMax, fh - band.h)
        if (hi < yMin) return -1
        val cols = band.cols
        val n = band.norm.size
        val nn = n.toDouble()
        val sR = band.selR
        val sX = band.selX
        var bestY = -1
        var bestScore = minScore.toDouble()
        var y = yMin
        while (y <= hi) {
            var s = 0.0
            var ss = 0.0
            var dot = 0.0
            if (sR == null || sX == null) {
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
            } else {
                var j = 0
                while (j < n) {
                    val v = lum(frame[(y + sR[j]) * w + sX[j]]).toDouble()
                    s += v
                    ss += v * v
                    dot += band.norm[j] * v
                    j++
                }
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
     * جابه‌جایی عمودی محتوا از قاب prev به cur، به پیکسل.
     * مثبت = محتوا پایین آمده (خط هم باید پایین برود).
     * فقط پیکسل‌های واقعاً متحرک سه نوار مقایسه می‌شوند تا والپیپر ثابت نتیجه را خراب نکند.
     * اگر مطمئن نباشد Int.MIN_VALUE برمی‌گرداند.
     */
    fun estimateShift(prev: IntArray, cur: IntArray, w: Int, h: Int): Int {
        if (w <= 0 || h <= BAND_H + 8) return Int.MIN_VALUE
        if (prev.size != w * h || cur.size != w * h) return Int.MIN_VALUE
        if (nearIdentical(prev, cur, w, h)) return 0
        val found = ArrayList<Int>(3)
        val anchors = floatArrayOf(0.18f, 0.40f, 0.62f)
        val yHi = h - BAND_H
        val cap = (h * 0.92f).toInt()
        for (f in anchors) {
            val row = (h * f).toInt().coerceIn(0, yHi)
            val band = extractMovingBand(prev, cur, w, row, BAND_H) ?: continue
            if (band.norm.isEmpty() || band.sigma < BLANK_SIGMA) continue
            val y = findOffset(cur, w, h, band, 0, yHi, 0.75f)
            if (y < 0) continue
            val shift = y - row
            if (abs(shift) > cap) continue
            found.add(shift)
        }
        if (found.isEmpty()) return Int.MIN_VALUE
        found.sort()
        if (found.size == 1) {
            // پرش بلند فقط با دست‌کم دو نوارِ هم‌رأی قبول می‌شود تا حباب مشابه خط را نپراند
            return if (abs(found[0]) <= (h * 0.35f).toInt()) found[0] else Int.MIN_VALUE
        }
        if (found.size == 2) {
            return if (abs(found[1] - found[0]) <= 48) (found[0] + found[1]) / 2 else Int.MIN_VALUE
        }
        val d01 = abs(found[1] - found[0])
        val d12 = abs(found[2] - found[1])
        return when {
            found[2] - found[0] <= 80 -> found[1]
            d01 <= 40 -> (found[0] + found[1]) / 2
            d12 <= 40 -> (found[1] + found[2]) / 2
            else -> Int.MIN_VALUE
        }
    }

    /**
     * ردیف‌های یکسانِ ابتدا و انتهای دو قاب (نوارهای چسبان مثل هدر/فوتر) را پیدا می‌کند.
     * ردیف سفید/خالی حتی اگر در دو قاب یکی باشد هدر حساب نمی‌شود (حاشیه‌ی صفحات PDF).
     * خروجی: [پایان نوار ثابت بالا، شروع نوار ثابت پایین] ؛ ناحیه‌ی مفید بین این دو است.
     */
    fun fixedBands(a: IntArray, b: IntArray, w: Int, h: Int, cap: Int): IntArray {
        val topCap = if (cap > h / 2) h / 2 else cap
        var t = 0
        while (t < topCap && rowEquals(a, b, w, t) && rowHasDetail(a, w, t)) t++
        val botCap = h - topCap
        var bt = h
        while (bt > botCap && rowEquals(a, b, w, bt - 1) && rowHasDetail(a, w, bt - 1)) bt--
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

    /** ردیف تقریباً یکدست (سفید/خالی) جزئیات ندارد و نباید نوار چسبان حساب شود */
    private fun rowHasDetail(px: IntArray, w: Int, y: Int): Boolean {
        val base = y * w
        var minL = 255
        var maxL = 0
        var x = 0
        while (x < w) {
            val l = lum(px[base + x])
            if (l < minL) minL = l
            if (l > maxL) maxL = l
            if (maxL - minL > 28) return true
            x += 8
        }
        return false
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
