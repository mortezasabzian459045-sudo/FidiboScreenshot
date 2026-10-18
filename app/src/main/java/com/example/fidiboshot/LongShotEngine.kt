package com.example.fidiboshot

import java.io.File
import java.io.OutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * موتور اسکرین طولی — همهی متدهای سنگین باید روی ترد پسزمینه صدا زده شوند.
 *
 * فاز ۱ (ضبط): قابها رو به بالا گرفته میشوند؛ آفست هر قاب روی «بوم» با تطبیق
 * پیکسلی نوارها زنجیر میشود؛ اثر انگشت خط ابتدا/پایان (نوار ~۱۶۰ پیکسلی دور خط)
 * جستجو میشود؛ هیستوگرام روشنایی کل تصویر به صورت سراسری انباشته میشود.
 *
 * فاز ۲ (مونتاژ): با ارتفاع معلوم، PNG ردیفبهردیف استریم میشود؛ یک جدول سطوح
 * سراسری (تا درز روشنایی بین قابها نیفتد) و تیز کردن + منحنی S هنگام مونتاژ
 * اعمال میشود. بزرگکردن ۲× برای تصویر طویل ممکن نیست و انجام نمیشود.
 */
class LongShotEngine {

    companion object {
        const val MIN_STEP = 8          // کمتر از این = محتوا حرکت نکرده
        const val MAX_HEIGHT = 65000    // سقف سخت ارتفاع خروجی
        const val START_ZNCC = 0.84f    // آستانهی تشخیص خط ابتدا
    }

    class FrameRec(val file: File, val offset: Int)
    class Ref(
        val band: OverlapMatcher.Band,
        val fullBand: OverlapMatcher.Band?,
        val lineRow: Int,
        val bandRow: Int
    )

    /** نتیجهی پردازش هر قاب */
    sealed class R {
        object Accepted : R()
        object Discard : R()
        object NoMove : R()
        object FoundStart : R()
        class Error(val msg: String) : R()
    }

    class AsmResult(val width: Int, val height: Int, val partial: Boolean)

    private var frameW = 0
    private var frameH = 0
    private val frames = ArrayList<FrameRec>()
    private var prevPx: IntArray? = null
    private var prevOffset = 0
    private var lastStep = 0

    val hist = IntArray(256)
    private var histTotal = 0L

    var refStart: Ref? = null
    var refEnd: Ref? = null
    /** کپی قاب لحظهی ثبت خط ابتدا برای تشخیص دقیق حالت بدون اسکرول / اسکرول کم */
    var refStartFramePx: IntArray? = null

    /** فاصلهی دو خط روی محتوا (پیکسل)؛ ۰ = نامعلوم. باید بعد از reset تنظیم شود */
    var spanHint = 0
    /** true = فاصله از روی خود صفحه اندازهگیری شده (قابل اعتماد) */
    var spanReliable = false
    private var expectedStart = Int.MIN_VALUE

    @Volatile var startLineCanvas = Int.MIN_VALUE
    @Volatile var endLineCanvas = Int.MIN_VALUE
    @Volatile var framesSaved = 0
        private set
    @Volatile private var minOff = 0

    // نوارهای ثابت (هدر/فوتر چسبان): سیاست محافظهکارانه = کمینه/بیشینهی همهی جفتها
    private var fixedTopTaken = Int.MAX_VALUE
    private var fixedBottomTaken = Int.MIN_VALUE

    /** همان منحنی S ملایم ImageEnhancer */
    private val curve = IntArray(256) { i ->
        val x = i / 255f
        val s = x * x * (3f - 2f * x)
        ((x + 0.45f * (s - x)) * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    fun reset(w: Int, h: Int) {
        frameW = w
        frameH = h
        frames.clear()
        prevPx = null
        prevOffset = 0
        lastStep = 0
        java.util.Arrays.fill(hist, 0)
        histTotal = 0L
        startLineCanvas = Int.MIN_VALUE
        endLineCanvas = Int.MIN_VALUE
        framesSaved = 0
        minOff = 0
        fixedTopTaken = Int.MAX_VALUE
        fixedBottomTaken = Int.MIN_VALUE
        expectedStart = Int.MIN_VALUE
    }

    /** اثر انگشتهای قبلی را پاک میکند (هنگام ورود به حالت خطگذاری) */
    fun clearRefs() {
        refStart = null
        refEnd = null
        refStartFramePx = null
    }

    fun cleanup() {
        prevPx = null
        refStartFramePx = null
    }

    private fun usableTop() = if (fixedTopTaken == Int.MAX_VALUE) 0 else fixedTopTaken
    private fun usableBottom() = if (fixedBottomTaken == Int.MIN_VALUE) frameH else fixedBottomTaken

    /** ارتفاع تقریبی بازهی ضبطشده تا این لحظه (برای سقف و شمارنده) */
    fun capturedSpan(): Int {
        val e = endLineCanvas
        return if (e == Int.MIN_VALUE) 0 else (e - minOff).coerceAtLeast(0)
    }

    /** جستجوی یک مرجع (اول با نوار جزئیات، در صورت نبود با نوار کامل) */
    fun findRefInFrame(px: IntArray, w: Int, h: Int, ref: Ref, yMin: Int, yMax: Int, minScore: Float): Int {
        var y = OverlapMatcher.findOffset(px, w, h, ref.band, yMin, yMax, minScore)
        if (y < 0 && ref.fullBand != null && ref.fullBand !== ref.band) {
            y = OverlapMatcher.findOffset(px, w, h, ref.fullBand, yMin, yMax, minScore)
        }
        return y
    }

    // ================= فاز ۱: ضبط =================

    /**
     * آیا خط ابتدا در این قاب (با آفست offset) رسیده؟ خروجی: ردیف خط روی بوم یا Int.MIN_VALUE.
     */
    private fun detectStart(px: IntArray, offset: Int, t: Int, firstFrame: Boolean): Int {
        val rs = refStart ?: return Int.MIN_VALUE
        val w = frameW
        val h = frameH
        val yHi = h - rs.band.h
        if (yHi <= 0) return Int.MIN_VALUE
        val lineOff = rs.lineRow - rs.bandRow

        if (expectedStart != Int.MIN_VALUE) {
            val rowInFrame = expectedStart - offset
            if (rowInFrame >= 0 && rowInFrame < h) {
                val bandTop = (rowInFrame - lineOff).coerceIn(0, yHi)
                val y = findRefInFrame(
                    px, w, h, rs,
                    (bandTop - 240).coerceIn(0, yHi), (bandTop + 240).coerceIn(0, yHi), 0.76f
                )
                if (y >= 0) {
                    val found = offset + y + lineOff
                    return if (kotlin.math.abs(found - expectedStart) <= 36) expectedStart else found
                }
                if (spanReliable) return expectedStart
            }
            if (spanReliable && rowInFrame >= h) return expectedStart
            if (spanReliable) return Int.MIN_VALUE
        }

        val searchLo = if (firstFrame) 0 else max(0, t - 20).coerceIn(0, yHi)
        val topLim = if (firstFrame) yHi
        else min(yHi, searchLo + max(lastStep + rs.band.h + 80, (h * 0.55f).toInt()))
        if (topLim < searchLo) return Int.MIN_VALUE
        val y = findRefInFrame(px, w, h, rs, searchLo, topLim, START_ZNCC)
        return if (y >= 0) offset + y + lineOff else Int.MIN_VALUE
    }

    /** قاب اول (لنگر با آفست صفر) — خط پایان در آن است */
    fun processFirst(px: IntArray, dir: File): R {
        val w = frameW
        val h = frameH
        if (px.size != w * h) return R.Error("اندازهی قاب نامعتبر است")
        val re = refEnd ?: return R.Error("خط پایان ثبت نشده است")

        val yHi = h - re.band.h
        if (yHi <= 0) return R.Error("ناحیهی محتوا برای تطبیق خیلی کوچک است")

        // صفحه از لحظهی ثبت خط پایان تکان نخورده؛ اول پیرامون همان ردیف، اگر نشد همان ردیف ثبتشده
        val y0 = re.bandRow.coerceIn(0, yHi)
        var y = findRefInFrame(px, w, h, re, y0, y0, 0.80f)
        if (y < 0) {
            y = findRefInFrame(
                px, w, h, re,
                (y0 - 120).coerceIn(0, yHi), (y0 + 120).coerceIn(0, yHi), 0.80f
            )
        }
        endLineCanvas = if (y >= 0) y + (re.lineRow - re.bandRow) else re.lineRow
        expectedStart = if (spanHint > 0) endLineCanvas - spanHint else Int.MIN_VALUE

        // اگر هر دو خط در همین قاب اول هستند (بدون اسکرول یا اسکرول کم)
        if (spanReliable && expectedStart >= 0 && expectedStart < endLineCanvas) {
            startLineCanvas = expectedStart
            acceptFrame(px, 0, 0, frameH, dir)
            return R.FoundStart
        }

        val sc = detectStart(px, 0, 0, true)
        if (sc != Int.MIN_VALUE) {
            if (sc >= endLineCanvas) {
                return R.Error("خط پایان باید پایینتر از خط ابتدا باشد")
            }
            startLineCanvas = sc
            acceptFrame(px, 0, 0, frameH, dir)
            return R.FoundStart
        }
        acceptFrame(px, 0, 0, frameH, dir)
        return R.Accepted
    }

    /** قابهای بعدی (اسکرول رو به بالا) — guessStep حدس اولیهی مقدار اسکرول است */
    fun processNext(px: IntArray, dir: File, guessStep: Int): R {
        val w = frameW
        val h = frameH
        if (px.size != w * h) return R.Error("اندازهی قاب نامعتبر است")
        val prev = prevPx ?: return R.Error("قاب قبلی موجود نیست")

        // محتوا حرکت نکرده؟ (رسیدن به بالای سند)
        if (OverlapMatcher.nearIdentical(prev, px, w, h)) return R.NoMove

        // نوارهای چسبان (فقط برای قابهای میانی): سیاست محافظهکارانه
        val fb = OverlapMatcher.fixedBands(prev, px, w, h, h / 4)
        if (fb[0] < fixedTopTaken) fixedTopTaken = fb[0]
        if (fb[1] > fixedBottomTaken) fixedBottomTaken = fb[1]
        if (fixedBottomTaken - fixedTopTaken < h / 2) {
            fixedTopTaken = Int.MAX_VALUE
            fixedBottomTaken = Int.MIN_VALUE
        }

        val t = usableTop()
        val b = usableBottom()
        if (b - t < OverlapMatcher.BAND_H * 2) return R.Error("ناحیهی محتوا برای تطبیق خیلی کوچک است")

        // بهترین نوار «متحرک» از قاب قبلی (پرجزئیاتترین؛ بدون پیکسلهای ثابت مثل والپیپر)
        val lo = max((h * 0.08f).toInt(), min(t, (h * 0.18f).toInt()))
        val hiRow = min(lo + (h * 0.32f).toInt(), h - OverlapMatcher.BAND_H)
        var band: OverlapMatcher.Band? = null
        var bandRow = lo
        var bestSigma = -1f
        var rr = lo
        while (rr <= hiRow) {
            val cand = OverlapMatcher.extractMovingBand(prev, px, w, rr, OverlapMatcher.BAND_H)
            if (cand != null && cand.sigma > bestSigma) {
                bestSigma = cand.sigma
                band = cand
                bandRow = rr
            }
            rr += 20
        }
        if (band == null || band.sigma < OverlapMatcher.BLANK_SIGMA) {
            // اگر نوار متحرک با جزئیات کافی نبود، از نوار معمولی بهترین ردیف استفاده کن
            rr = lo
            while (rr <= hiRow) {
                val cand = OverlapMatcher.extractBand(prev, w, rr, OverlapMatcher.BAND_H)
                if (cand != null && cand.sigma > bestSigma) {
                    bestSigma = cand.sigma
                    band = cand
                    bandRow = rr
                }
                rr += 24
            }
        }
        if (band == null) return R.Discard
        val yHi = h - OverlapMatcher.BAND_H     // جستجو در کل قاب

        val offset: Int
        if (band.sigma < OverlapMatcher.BLANK_SIGMA) {
            // نوار خالی است (متن ندارد)؛ به جابهجایی قبلی/حدس اولیه اعتماد میکنیم
            val step = if (lastStep > 0) lastStep else guessStep
            if (step < MIN_STEP || step > h) return R.Discard
            offset = prevOffset - step
        } else {
            val expected = bandRow + (if (lastStep > 0) lastStep else guessStep)
            val win = maxOf(h / 6, 100)
            var y = OverlapMatcher.findOffset(
                px, w, h, band,
                (expected - win).coerceIn(0, yHi), (expected + win).coerceIn(0, yHi), 0.82f
            )
            if (y < 0) y = OverlapMatcher.findOffset(px, w, h, band, 0, yHi, 0.82f)
            if (y < 0) return R.Discard
            offset = prevOffset + bandRow - y
        }

        val step = prevOffset - offset
        if (step < MIN_STEP) return R.NoMove
        if (step > h * 2) return R.Discard
        lastStep = step

        if (refStart == null) return R.Error("خط ابتدا ثبت نشده است")
        val sc = detectStart(px, offset, t, false)
        if (sc != Int.MIN_VALUE) {
            if (sc >= endLineCanvas) {
                return R.Error("خط پایان باید پایینتر از خط ابتدا باشد")
            }
            startLineCanvas = sc
            acceptFrame(px, offset, t, min(b, t + step), dir)
            return R.FoundStart
        }

        acceptFrame(px, offset, t, min(b, t + step), dir)
        return R.Accepted
    }

    private fun acceptFrame(px: IntArray, offset: Int, histFrom: Int, histTo: Int, dir: File) {
        val f = File(dir, String.format(Locale.US, "f%03d.png", frames.size))
        PngWriter.writeIntPng(f, px, frameW, frameH)
        frames.add(FrameRec(f, offset))
        if (offset < minOff) minOff = offset
        framesSaved = frames.size
        addHistogram(px, frameW, histFrom, histTo)
        prevPx = px
        prevOffset = offset
    }

    /** هیستوگرام روشنایی فقط از ردیفهای تازه (بدون شمارش تکراری ناحیهی همپوشان) */
    private fun addHistogram(px: IntArray, w: Int, y0: Int, y1: Int) {
        val from = y0.coerceIn(0, frameH)
        val to = y1.coerceIn(0, frameH)
        var total = histTotal
        var y = from
        while (y < to) {
            val base = y * w
            var x = 0
            while (x < w) {
                val p = px[base + x]
                hist[(((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000]++
                total++
                x += 2
            }
            y += 4
        }
        histTotal = total
    }

    // ================= فاز ۲: مونتاژ =================

    /** همان منطق سطوح ImageEnhancer، ولی روی هیستوگرام سراسری کل تصویر */
    fun levelsLut(): IntArray? {
        val total = histTotal
        if (total < 500L) return null
        val lowTarget = (total * 0.01).toInt()
        var cum = 0L
        var low = 0
        for (i in 0..255) {
            cum += hist[i]
            if (cum > lowTarget) {
                low = i
                break
            }
        }
        low = min(low, 80)

        var peak = 255
        var best = -1L
        for (i in 140..254) {
            val s = hist[i - 1].toLong() + hist[i] + hist[i + 1]
            if (s > best) {
                best = s
                peak = i
            }
        }
        val paperLike = best > total * 0.25
        val high = when {
            paperLike && peak >= 250 -> 255
            paperLike -> peak - 2
            else -> 255
        }
        if (high - low < 60) return null
        if (low <= 6 && high >= 249) return null   // از قبل پرکنتراست است
        val lut = IntArray(256)
        val range = (high - low).toFloat()
        for (i in 0..255) {
            lut[i] = (((i - low) / range) * 255f).toInt().coerceIn(0, 255)
        }
        return lut
    }

    /** مونتاژ و نوشتن استریمی خروجی دقیقاً از خط ابتدا تا خط پایان */
    fun assemble(out: OutputStream, onProgress: (Float) -> Unit): AsmResult {
        val w = frameW
        var t = usableTop()
        var b = usableBottom()
        if (b - t < OverlapMatcher.BAND_H) {
            t = 0
            b = frameH
        }
        // حاشیهی ایمنی فقط برای درز بین قابهای میانی
        val marginT = max(t, (frameH * 0.06f).toInt())
        val marginB = min(b, frameH - (frameH * 0.08f).toInt())
        val useT = if (marginB - marginT >= (frameH * 0.55f).toInt()) marginT else t
        val useB = if (marginB - marginT >= (frameH * 0.55f).toInt()) marginB else b

        val sorted = frames.sortedBy { it.offset }
        if (sorted.isEmpty()) throw IllegalStateException("قابی برای مونتاژ نیست")
        val last = sorted.lastIndex
        val partial = startLineCanvas == Int.MIN_VALUE

        // قاب بالاترین (شروع) و قاب پایینترین (پایان) هیچوقت از بالا/پایین بریده نمیشوند
        // تا خروجی دقیقاً از خط ابتدا تا خط پایان باشد
        fun tOf(k: Int) = if (k == 0) 0 else useT
        fun bOf(k: Int) = if (k == last) frameH else useB

        val hardBottom = sorted.last().offset + frameH
        val hardTop = sorted.first().offset
        val bottomLine = if (endLineCanvas > Int.MIN_VALUE) endLineCanvas.coerceIn(hardTop + 1, hardBottom) else hardBottom
        val topLine = if (startLineCanvas > Int.MIN_VALUE) startLineCanvas.coerceIn(hardTop, bottomLine - 1) else hardTop
        var h = (bottomLine - topLine).coerceAtLeast(1)
        if (h > MAX_HEIGHT) h = MAX_HEIGHT

        // مالکیت هر ردیف خروجی: تازهترین قابی که آن ردیف را در ناحیهی مفیدش دارد
        val owner = IntArray(h)
        val rowIn = IntArray(h)
        for (i in 0 until h) {
            val c = topLine + i
            var chosen = -1
            for (k in sorted.indices.reversed()) {
                val off = sorted[k].offset
                if (c >= off + tOf(k) && c < off + bOf(k)) {
                    chosen = k
                    rowIn[i] = (c - off).coerceIn(0, frameH - 1)
                    break
                }
            }
            if (chosen < 0) {
                // اگر در ناحیهی میانی نیفتاد، نزدیکترین قابی که این ردیف را پوشش میدهد انتخاب کن
                for (k in sorted.indices.reversed()) {
                    val off = sorted[k].offset
                    if (c >= off && c < off + frameH) {
                        chosen = k
                        rowIn[i] = (c - off).coerceIn(0, frameH - 1)
                        break
                    }
                }
            }
            if (chosen < 0) {
                chosen = 0
                rowIn[i] = (c - sorted[0].offset).coerceIn(0, frameH - 1)
            }
            owner[i] = chosen
        }

        val writer = PngWriter.StreamingPng(out, w, h)
        val lut = levelsLut()
        val cur = IntArray(w)
        val next = IntArray(w)
        val up = IntArray(w)
        val outRow = IntArray(w)

        var slotAIdx = -1
        var slotAPx: IntArray? = null
        var slotBIdx = -1
        var slotBPx: IntArray? = null
        fun pxOf(idx: Int): IntArray {
            if (idx == slotAIdx) return slotAPx!!
            if (idx == slotBIdx) {
                val p = slotBPx!!
                val ti = slotAIdx
                val tp = slotAPx
                slotAIdx = idx
                slotAPx = p
                slotBIdx = ti
                slotBPx = tp
                return p
            }
            val p = PngWriter.readFrame(sorted[idx].file, w, frameH)
            val ti = slotAIdx
            val tp = slotAPx
            slotAIdx = idx
            slotAPx = p
            slotBIdx = ti
            slotBPx = tp
            return p
        }

        for (i in 0 until h) {
            val pa = pxOf(owner[i])
            levelInto(pa, rowIn[i] * w, cur, lut, w)
            if (i + 1 < h) {
                val pb = pxOf(owner[i + 1])
                levelInto(pb, rowIn[i + 1] * w, next, lut, w)
            } else {
                System.arraycopy(cur, 0, next, 0, w)
            }

            val upRow = if (i == 0) cur else up
            sharpenRow(cur, upRow, next, outRow, w)

            writer.writeRow(outRow)
            System.arraycopy(cur, 0, up, 0, w)
            if ((i and 255) == 0) onProgress(i.toFloat() / h)
        }
        onProgress(1f)
        writer.finish()
        return AsmResult(w, h, partial)
    }

    /** اعمال جدول سطوح روی یک ردیف */
    private fun levelInto(px: IntArray, src: Int, dst: IntArray, lut: IntArray?, w: Int) {
        if (lut == null) {
            System.arraycopy(px, src, dst, 0, w)
            return
        }
        for (x in 0 until w) {
            val p = px[src + x]
            dst[x] = (p and 0xFF000000.toInt()) or
                (lut[(p shr 16) and 0xFF] shl 16) or
                (lut[(p shr 8) and 0xFF] shl 8) or
                lut[p and 0xFF]
        }
    }

    private fun clamp8(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    /** همان کرنل تیز کردن ImageEnhancer (مقدار ۰/۵ چون بزرگکردن ۲× نداریم) + منحنی S */
    private fun sharpenRow(cur: IntArray, up: IntArray, down: IntArray, out: IntArray, w: Int) {
        val a = 128
        val center = 256 + 4 * a
        for (x in 0 until w) {
            val c = cur[x]
            val l = cur[if (x > 0) x - 1 else 0]
            val r = cur[if (x < w - 1) x + 1 else w - 1]
            val u = up[x]
            val d = down[x]
            val rr = (center * ((c shr 16) and 0xFF) -
                a * (((l shr 16) and 0xFF) + ((r shr 16) and 0xFF) + ((u shr 16) and 0xFF) + ((d shr 16) and 0xFF))) shr 8
            val gg = (center * ((c shr 8) and 0xFF) -
                a * (((l shr 8) and 0xFF) + ((r shr 8) and 0xFF) + ((u shr 8) and 0xFF) + ((d shr 8) and 0xFF))) shr 8
            val bb = (center * (c and 0xFF) -
                a * ((l and 0xFF) + (r and 0xFF) + (u and 0xFF) + (d and 0xFF))) shr 8
            out[x] = (0xFF shl 24) or (curve[clamp8(rr)] shl 16) or
                (curve[clamp8(gg)] shl 8) or curve[clamp8(bb)]
        }
    }
}