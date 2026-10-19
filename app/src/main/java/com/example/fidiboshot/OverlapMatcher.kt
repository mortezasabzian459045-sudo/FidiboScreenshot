package com.example.fidiboshot

import kotlin.math.abs
import kotlin.math.max
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

    class ShiftCand(val shift: Int, val visible: Int, val matched: Int, val score: Float, val accepted: Boolean)

    /** نوارهای «بافت اطراف» خط: تا ±۶۴۰ پیکسل بالا و پایین نقطه‌ی لنگر، هر ۱۲۸ پیکسل */
    private fun contextBands(refPx: IntArray, w: Int, h: Int, anchorY: Int): List<Pair<Int, Band>> {
        val yHi = h - BAND_H
        val a0 = (anchorY - BAND_H / 2).coerceIn(0, yHi)
        val out = ArrayList<Pair<Int, Band>>()
        var d = -640
        while (d <= 640) {
            val r = a0 + d
            if (r in 0..yHi) {
                val b = extractDetailBand(refPx, w, r, BAND_H) ?: extractBand(refPx, w, r, BAND_H)
                if (b != null && b.sigma >= BLANK_SIGMA) out.add(Pair(r, b))
            }
            d += 128
        }
        return out
    }

    /** چند بهترین مکان نوار در قاب (با حذف همسایه‌های ±۴۰ پیکسلی) */
    fun findTopMatches(
        frame: IntArray, w: Int, fh: Int, band: Band, yMin: Int, yMax: Int,
        minScore: Float, k: Int
    ): List<Match> {
        if (band.sigma < BLANK_SIGMA || band.norm.isEmpty()) return emptyList()
        val lo = yMin.coerceAtLeast(0)
        val hi = min(yMax, fh - band.h)
        if (hi < lo) return emptyList()
        val cols = band.cols
        val n = band.norm.size
        val nn = n.toDouble()
        val sR = band.selR
        val sX = band.selX
        val scores = FloatArray(hi - lo + 1)
        var y = lo
        while (y <= hi) {
            var s = 0.0
            var ss = 0.0
            var dot = 0.0
            if (sR == null || sX == null) {
                var kk = 0
                var r = 0
                while (r < band.h) {
                    val yy = y + r
                    var ci = 0
                    while (ci < cols.size) {
                        val v = smoothLum(frame, w, cols[ci], yy).toDouble()
                        s += v
                        ss += v * v
                        dot += band.norm[kk++] * v
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
            scores[y - lo] = if (variance > 1e-4) (dot / (sqrt(variance) * nn)).toFloat() else -1f
            y++
        }
        val taken = BooleanArray(scores.size)
        val res = ArrayList<Match>()
        for (t in 0 until k) {
            var bi = -1
            var bs = minScore
            for (i in scores.indices) {
                if (!taken[i] && scores[i] > bs) {
                    bs = scores[i]
                    bi = i
                }
            }
            if (bi < 0) break
            res.add(Match(lo + bi, bs))
            for (j in max(0, bi - 40)..min(scores.size - 1, bi + 40)) taken[j] = true
        }
        return res
    }

    /**
     * کاندیدهای جابه‌جایی (cur − ref) را می‌سازد و هر کدام را با «بافت اطراف» راستی‌آزمایی می‌کند:
     * همه‌ی نوارهای اطراف خط، در جای مورد انتظارشان (با همان جابه‌جایی) باید دیده شوند.
     * عنصر تکراری (مثلاً کارت «برای بار کردن پیش‌نما…» در چند پیام) فقط خودش جور می‌شود و نوارهای بالا و پایینش
     * (متن‌های متفاوت) جور نمی‌شوند، پس رد می‌شود.
     */
    private fun contextCandidates(refPx: IntArray, curPx: IntArray, w: Int, h: Int, anchorY: Int): List<ShiftCand> {
        if (refPx.size != w * h || curPx.size != w * h || h < BAND_H * 2) return emptyList()
        val yHi = h - BAND_H
        val bands = contextBands(refPx, w, h, anchorY)
        if (bands.isEmpty()) return emptyList()
        val a0 = (anchorY - BAND_H / 2).coerceIn(0, yHi)
        val order = bands.sortedBy { abs(it.first - a0) }
        val seen = ArrayList<ShiftCand>()
        for (src in order.take(3)) {
            val tops = findTopMatches(curPx, w, h, src.second, 0, yHi, 0.80f, 4)
            for (m in tops) {
                val shift = m.y - src.first
                if (seen.any { abs(it.shift - shift) <= 3 }) continue
                var vis = 0
                var mat = 0
                var sc = 0f
                for ((r, b) in bands) {
                    val exp = r + shift
                    if (exp < 0 || exp > yHi) continue
                    vis++
                    val mm = findMatch(curPx, w, h, b, exp - 3, exp + 3, 0.75f)
                    if (mm.y >= 0) {
                        mat++
                        sc += mm.score
                    }
                }
                val accepted = when {
                    vis >= 3 -> mat >= 3 && mat * 100 >= vis * 65
                    vis == 2 -> mat == 2
                    // فقط یک نوار دیده می‌شود: فقط اگر کاملاً یکتا و بسیار شبیه باشد
                    else -> vis == 1 && mat == 1 && tops.size == 1 && m.score >= 0.93f
                }
                seen.add(ShiftCand(shift, vis, mat, if (mat > 0) sc / mat else 0f, accepted))
            }
            if (seen.any { it.accepted }) break
        }
        return seen
    }

    /**
     * جابه‌جایی عمودی خط (ردیف anchorY در قاب مرجع) تا قاب cur؛ Int.MIN_VALUE یعنی «مطمئن نیستم».
     * hintShift (اختیاری): جابه‌جایی تخمینی (مثلاً از رویدادهای اسکرول) برای انتخاب بین کاندیدهای هم‌ارز.
     */
    fun findFrameShift(
        refPx: IntArray, curPx: IntArray, w: Int, h: Int,
        anchorY: Int, hintShift: Int = Int.MIN_VALUE
    ): Int {
        val acc = contextCandidates(refPx, curPx, w, h, anchorY).filter { it.accepted }
        if (acc.isEmpty()) return Int.MIN_VALUE
        if (acc.size == 1) return acc[0].shift
        val sorted = acc.sortedWith(compareByDescending<ShiftCand> { it.matched }.thenByDescending { it.score })
        if (hintShift != Int.MIN_VALUE) {
            val near = sorted.minByOrNull { abs(it.shift - hintShift) }
            if (near != null && abs(near.shift - hintShift) <= 60) return near.shift
        }
        // چند کاندید هم‌ارز = محتوای تکراری؛ فقط اگر یکی به‌وضوح برتر باشد
        return if (sorted[0].matched - sorted[1].matched >= 2) sorted[0].shift else Int.MIN_VALUE
    }

    /** آیا این نقطه در همین قاب جای دیگری هم با کل بافت اطرافش تکرار شده؟ (برای هشدار هنگام ثبت خط) */
    fun isAmbiguous(px: IntArray, w: Int, h: Int, anchorY: Int): Boolean =
        contextCandidates(px, px, w, h, anchorY).any { it.accepted && abs(it.shift) > 8 }

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