package com.example.fidiboshot

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * تطبیق پیکسلی برای اسکرین طولی؛ همهی توابع روی آرایهی پیکسلهای برشخورده
 * (IntArray با قالب ARGB) کار میکنند و باید روی ترد پسزمینه صدا زده شوند.
 * هیچ کتابخانهای استفاده نمیشود؛ ZNCC دستی با نمونهبرداری سبک.
 */
object OverlapMatcher {

    /** ارتفاع نوار تطبیق (پیکسل) */
    const val BAND_H = 100

    /** ارتفاع نوار «اثر انگشت» خط ابتدا/پایان (بلندتر = یکتاتر) */
    const val REF_BAND_H = 160

    /** اختلاف روشنایی کمتر از این بین دو قاب = پیکسل ثابت (والپیپر، هدر، دکمهی شناور) */
    private const val STATIC_DIFF = 3

    /** حداقل تعداد نمونهی متحرک برای ساختن نوار */
    private const val MIN_ACTIVE = 350

    /** حداقل نمرهی ZNCC برای پذیرش تطبیق */
    const val ZNCC_MIN = 0.85f

    /** نوار با پراکندگی کمتر از این مقدار «خالی» در نظر گرفته میشود */
    const val BLANK_SIGMA = 4.0f

    private const val COL_STEP = 6     // نمونهبرداری ستونی
    private const val ROW_STEP = 2     // نمونهبرداری ردیفی داخل نوار
    private const val EDGE_SKIP = 40   // حذف نوارهای کناری (اسکرولبار) از تطبیق

    fun lum(p: Int): Int =
        (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

    /** روشنایی نرمشده (میانگین با همسایهی افقی/عمودی) برای مقاومت در برابر جابهجایی ۱ پیکسلی فونت هنگام اسکرول */
    private fun smoothLum(px: IntArray, w: Int, x: Int, y: Int): Int {
        val idx = y * w + x
        val c = lum(px[idx]) * 2
        val l = if (x > 0) lum(px[idx - 1]) else lum(px[idx])
        val r = if (x + 1 < w) lum(px[idx + 1]) else lum(px[idx])
        return (c + l + r) shr 2
    }

    /** نوار آماده برای تطبیق؛ norm میانگینگیری و نرمال شده است */
    class Band(
        val cols: IntArray, val rows: Int, val h: Int, val norm: FloatArray, val sigma: Float,
        /** اگر null نباشد فقط همین نمونهها (ردیف/ستون) در تطبیق شرکت میکنند */
        val selR: IntArray? = null, val selX: IntArray? = null
    )

    /** نتیجهی جستجوی نوار همراه با نمرهی دقیق تطبیق */
    class Match(val y: Int, val score: Float)

    /** نوار را از ردیف y0 به ارتفاع h میسازد؛ اگر ستون کافی نبود null */
    fun extractBand(px: IntArray, w: Int, y0: Int, h: Int): Band? {
        if (h < 8 || y0 < 0 || w <= 0) return null
        val fh = px.size / w
        if (y0 + h > fh) return null
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
            val yy = y0 + r
            for (i in cols.indices) raw[k++] = smoothLum(px, w, cols[i], yy).toFloat()
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
     * مثل extractBand ولی فقط نمونههای پرکنتراست (لبهی متن/حباب/عکس).
     * پسزمینهی یکدست و والپیپر نرم داخل اثر انگشت نمیآید تا بعد از اسکرول
     * هنوز بتوان همان محتوا را پیدا کرد.
     */
    fun extractDetailBand(px: IntArray, w: Int, y0: Int, h: Int): Band? {
        if (h < 8 || y0 < 0 || w <= 0) return null
        val fh = px.size / w
        if (y0 + h > fh) return null
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
            val yy = y0 + r
            val base = yy * w
            for (i in cols.indices) {
                val c = cols[i]
                val l = lum(px[base + c])
                val lR = if (c + 2 < w) lum(px[base + c + 2]) else l
                val lD = if (r + 2 < h) lum(px[(yy + 2) * w + c]) else l
                if (abs(l - lR) > 12 || abs(l - lD) > 12) {
                    rawV[n] = smoothLum(px, w, c, yy).toFloat()
                    selR[n] = r
                    selX[n] = c
                    n++
                }
            }
            r += ROW_STEP
        }
        if (n < 160) return null
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
     * نوار را از ردیف y0 قاب prev میسازد ولی فقط از پیکسلهایی که نسبت به قاب cur «تغییر کردهاند».
     * پیکسلهای ثابت (والپیپر تلگرام، هدر/فوتر چسبان، دکمههای شناور) در تطبیق شرکت نمیکنند؛
     * وگرنه تطبیق به جای حرکت واقعی محتوا، «بدون حرکت» را پیدا میکند.
     */
    fun extractMovingBand(prev: IntArray, cur: IntArray, w: Int, y0: Int, h: Int): Band? {
        if (h < 8 || y0 < 0 || w <= 0) return null
        val fh = prev.size / w
        if (y0 + h > fh) return null
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
            val yy = y0 + r
            val base = yy * w
            for (i in cols.indices) {
                val c = cols[i]
                val pl = lum(prev[base + c])
                val cl = lum(cur[base + c])
                if (abs(pl - cl) > STATIC_DIFF) {
                    rawV[n] = smoothLum(prev, w, c, yy).toFloat()
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
     * بهترین مکان نوار را در قاب بین yMin تا yMax (شامل) همراه با نمرهی تطبیق برمیگرداند.
     */
    fun findMatch(
        frame: IntArray, w: Int, fh: Int, band: Band, yMin: Int, yMax: Int,
        minScore: Float = ZNCC_MIN
    ): Match {
        if (band.sigma < BLANK_SIGMA || band.norm.isEmpty()) return Match(-1, 0f)
        val lo = yMin.coerceAtLeast(0)
        val hi = min(yMax, fh - band.h)
        if (hi < lo) return Match(-1, 0f)
        val cols = band.cols
        val n = band.norm.size
        val nn = n.toDouble()
        val sR = band.selR
        val sX = band.selX
        var bestY = -1
        var bestScore = minScore.toDouble()
        var y = lo
        while (y <= hi) {
            var s = 0.0
            var ss = 0.0
            var dot = 0.0
            if (sR == null || sX == null) {
                var k = 0
                var r = 0
                while (r < band.h) {
                    val yy = y + r
                    var ci = 0
                    while (ci < cols.size) {
                        val v = smoothLum(frame, w, cols[ci], yy).toDouble()
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
                    val v = smoothLum(frame, w, sX[j], y + sR[j]).toDouble()
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
        return Match(bestY, if (bestY >= 0) bestScore.toFloat() else 0f)
    }

    /**
     * بهترین مکان نوار را در قاب بین yMin تا yMax (شامل) میگردد.
     * خروجی: ردیف شروع نوار در قاب، یا -1 اگر نمرهی تطبیق از minScore کمتر بود.
     */
    fun findOffset(
        frame: IntArray, w: Int, fh: Int, band: Band, yMin: Int, yMax: Int,
        minScore: Float = ZNCC_MIN
    ): Int = findMatch(frame, w, fh, band, yMin, yMax, minScore).y

    /**
     * پیدا کردن جابهجایی عمودی (shift = yInCur - yInRef) بین دو قاب با استفاده از چند نوار مستقل.
     * فقط وقتی مقدار معتبر برمیگرداند که حداقل ۲ نوار مستقل روی یک جابهجایی یکسان (با خطای حداکثر ۵ پیکسل)
     * توافق داشته باشند، یا یک نوار پرجزئیات نمرهی بسیار بالا (>= 0.93) بگیرد.
     * خروجی: مقدار shift یا Int.MIN_VALUE اگر دو قاب همپوشانی مطمئنی ندارند.
     */
    fun findFrameShift(
        refPx: IntArray, curPx: IntArray, w: Int, h: Int,
        anchorY: Int
    ): Int {
        if (refPx.size != w * h || curPx.size != w * h || h < BAND_H * 2) return Int.MIN_VALUE
        val yHi = h - BAND_H
        // ساخت چند نوار کاندید از قاب مرجع حول نقطه لنگر و بخشهای دیگر قاب
        val candRows = ArrayList<Int>(7)
        val a0 = (anchorY - BAND_H / 2).coerceIn(0, yHi)
        candRows.add(a0)
        for (d in intArrayOf(-260, -130, 130, 260, 390, -390)) {
            val r = a0 + d
            if (r in 0..yHi && candRows.all { abs(it - r) >= 80 }) {
                candRows.add(r)
            }
        }
        val shifts = IntArray(candRows.size)
        val scores = FloatArray(candRows.size)
        var count = 0

        for (r0 in candRows) {
            val b = extractDetailBand(refPx, w, r0, BAND_H) ?: extractBand(refPx, w, r0, BAND_H)
            if (b == null || b.sigma < BLANK_SIGMA) continue
            val m = findMatch(curPx, w, h, b, 0, yHi, 0.82f)
            if (m.y >= 0) {
                shifts[count] = m.y - r0
                scores[count] = m.score
                count++
            }
        }
        if (count == 0) return Int.MIN_VALUE

        // بررسی توافق (Consensus) بین نوارها
        var bestClusterSize = 0
        var bestClusterScore = 0f
        var bestShiftSum = 0
        for (i in 0 until count) {
            var cSize = 0
            var cScore = 0f
            var sSum = 0
            for (j in 0 until count) {
                if (abs(shifts[i] - shifts[j]) <= 5) {
                    cSize++
                    cScore += scores[j]
                    sSum += shifts[j]
                }
            }
            if (cSize > bestClusterSize || (cSize == bestClusterSize && cScore > bestClusterScore)) {
                bestClusterSize = cSize
                bestClusterScore = cScore
                bestShiftSum = sSum
            }
        }
        if (bestClusterSize >= 2) {
            return Math.round(bestShiftSum.toFloat() / bestClusterSize)
        }
        // اگر فقط یک نوار مچ شد، فقط در صورتی قبول کن که نمرهاش فوقالعاده بالا باشد
        var maxIdx = 0
        for (i in 1 until count) if (scores[i] > scores[maxIdx]) maxIdx = i
        if (scores[maxIdx] >= 0.93f) return shifts[maxIdx]
        return Int.MIN_VALUE
    }

    /**
     * ردیفهای یکسانِ ابتدا و انتهای دو قاب (نوارهای چسبان مثل هدر/فوتر) را پیدا میکند.
     * ردیف سفید/خالی حتی اگر در دو قاب یکی باشد هدر حساب نمیشود (حاشیهی صفحات PDF).
     * خروجی: [پایان نوار ثابت بالا، شروع نوار ثابت پایین] ؛ ناحیهی مفید بین این دو است.
     */
    fun fixedBands(a: IntArray, b: IntArray, w: Int, h: Int, cap: Int): IntArray {
        val topCap = if (cap > h / 2) h / 2 else cap
        var t = 0
        while (t < topCap && rowEquals(a, b, w, t) && rowHasDetail(a, w, t)) t++
        val botCap = h - topCap
        var bt = h
        while (bt > botCap && rowEquals(a, b, w, bt - 1) && rowHasDetail(a, w, bt - 1)) bt--
        if (bt - t < h / 3) return intArrayOf(0, h)   // تشخیص مشکوک؛ نادیده گرفته میشود
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

    /** آیا دو قاب تقریباً یکساناند؟ (تشخیص ثبات صفحه یا بیحرکتی محتوا) */
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

    /** صفحهی کاملاً سیاه (اپهای محافظتشده با FLAG_SECURE) */
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