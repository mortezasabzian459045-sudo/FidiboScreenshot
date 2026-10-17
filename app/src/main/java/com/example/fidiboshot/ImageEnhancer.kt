package com.example.fidiboshot

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * افزایش کیفیت خودکار و همیشه‌روشن برای اسکرین‌شات صفحات کتاب. مراحل:
 * ۱) تنظیم سطوح: زمینه‌ی صفحه (حتی اگر خاکستری یا کرمی باشد) سفید و متن تیره‌تر می‌شود.
 * ۲) بزرگ‌کردن ۲× با درون‌یابی Catmull-Rom (خیلی شارپ‌تر از بزرگ‌کردن معمولی).
 * ۳) تیز کردن لبه‌ی حروف.
 * ۴) منحنی کنتراست ملایم تا متن پررنگ و واضح شود.
 * همه‌ی کارها ردیف‌به‌ردیف انجام می‌شود تا حافظه‌ی زیادی مصرف نشود. باید روی ترد پس‌زمینه صدا زده شود.
 */
object ImageEnhancer {

    private const val MAX_UPSCALE_PIXELS = 4_700_000L

    /** منحنی S ملایم: سایه‌ها کمی تیره‌تر، روشن‌ها کمی روشن‌تر */
    private val curve: IntArray = IntArray(256) { i ->
        val x = i / 255f
        val s = x * x * (3f - 2f * x)
        ((x + 0.45f * (s - x)) * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    fun enhance(input: Bitmap): Bitmap {
        var bmp = ensureMutable(input)

        computeLut(bmp)?.let { applyLut(bmp, it) }

        var upscaled = false
        if (bmp.width.toLong() * bmp.height <= MAX_UPSCALE_PIXELS) {
            val big = try { upscale2x(bmp) } catch (e: OutOfMemoryError) { null }
            if (big != null) {
                bmp.recycle()
                bmp = big
                upscaled = true
            }
        }

        sharpen(bmp, if (upscaled) 0.8f else 0.5f)
        return bmp
    }

    private fun ensureMutable(b: Bitmap): Bitmap {
        if (b.isMutable && b.config == Bitmap.Config.ARGB_8888) return b
        val copy = b.copy(Bitmap.Config.ARGB_8888, true)
        b.recycle()
        return copy
    }

    // ---------------- تنظیم سطوح ----------------

    /** از روی هیستوگرام روشنایی، جدول تبدیل سطوح را می‌سازد؛ اگر لازم نبود null */
    private fun computeLut(bmp: Bitmap): IntArray? {
        val w = bmp.width
        val h = bmp.height
        val hist = IntArray(256)
        val row = IntArray(w)
        var total = 0
        var y = 0
        while (y < h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val p = row[x]
                val lum = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                hist[lum]++
                total++
                x += 2
            }
            y += 4
        }
        if (total == 0) return null

        // نقطه‌ی سیاه: ۱٪ پایینی هیستوگرام
        val lowTarget = (total * 0.01).toInt()
        var cum = 0
        var low = 0
        for (i in 0..255) {
            cum += hist[i]
            if (cum > lowTarget) { low = i; break }
        }
        low = min(low, 80)

        // نقطه‌ی سفید: رنگ غالب «کاغذ» در نیمه‌ی روشن هیستوگرام
        var peak = 255
        var best = -1
        for (i in 140..254) {
            val s = hist[i - 1] + hist[i] + hist[i + 1]
            if (s > best) { best = s; peak = i }
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

    private fun applyLut(bmp: Bitmap, lut: IntArray) {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                row[x] = (p and 0xFF000000.toInt()) or
                    (lut[(p shr 16) and 0xFF] shl 16) or
                    (lut[(p shr 8) and 0xFF] shl 8) or
                    lut[p and 0xFF]
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    // ---------------- بزرگ‌کردن ۲× (Catmull-Rom) ----------------
    // برای ۲× وزن‌ها ثابت‌اند: (-6, 58, 222, -18) و قرینه‌اش، روی ۲۵۶

    private fun clamp8(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    private fun mix(p0: Int, p1: Int, p2: Int, p3: Int, w0: Int, w1: Int, w2: Int, w3: Int): Int {
        val r = (w0 * ((p0 shr 16) and 255) + w1 * ((p1 shr 16) and 255) +
            w2 * ((p2 shr 16) and 255) + w3 * ((p3 shr 16) and 255)) shr 8
        val g = (w0 * ((p0 shr 8) and 255) + w1 * ((p1 shr 8) and 255) +
            w2 * ((p2 shr 8) and 255) + w3 * ((p3 shr 8) and 255)) shr 8
        val b = (w0 * (p0 and 255) + w1 * (p1 and 255) +
            w2 * (p2 and 255) + w3 * (p3 and 255)) shr 8
        return (0xFF shl 24) or (clamp8(r) shl 16) or (clamp8(g) shl 8) or clamp8(b)
    }

    /** null یعنی حافظه کافی نبود */
    private fun upscale2x(src: Bitmap): Bitmap? {
        val w = src.width
        val h = src.height
        val w2 = w * 2
        val out = try {
            Bitmap.createBitmap(w2, h * 2, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return null
        }

        val ring = Array(5) { IntArray(w2) }
        val ringRow = IntArray(5) { Int.MIN_VALUE }
        val srcRow = IntArray(w)
        val outRow = IntArray(w2)

        // ردیف مرحله‌ی افقی (بزرگ‌شده‌ی عرضی) را می‌سازد و در حلقه‌ی ۵تایی نگه می‌دارد
        fun hrow(r0: Int): IntArray {
            val r = r0.coerceIn(0, h - 1)
            val idx = r % 5
            if (ringRow[idx] == r) return ring[idx]
            src.getPixels(srcRow, 0, w, 0, r, w, 1)
            val dst = ring[idx]
            for (i in 0 until w) {
                val a = srcRow[max(i - 2, 0)]
                val b = srcRow[max(i - 1, 0)]
                val c = srcRow[i]
                val d = srcRow[min(i + 1, w - 1)]
                val e = srcRow[min(i + 2, w - 1)]
                dst[2 * i] = mix(a, b, c, d, -6, 58, 222, -18)
                dst[2 * i + 1] = mix(b, c, d, e, -18, 222, 58, -6)
            }
            ringRow[idx] = r
            return dst
        }

        for (y in 0 until h) {
            val r0 = hrow(y - 2)
            val r1 = hrow(y - 1)
            val r2 = hrow(y)
            val r3 = hrow(y + 1)
            for (x in 0 until w2) outRow[x] = mix(r0[x], r1[x], r2[x], r3[x], -6, 58, 222, -18)
            out.setPixels(outRow, 0, w2, 0, 2 * y, w2, 1)

            val r4 = hrow(y + 2)
            for (x in 0 until w2) outRow[x] = mix(r1[x], r2[x], r3[x], r4[x], -18, 222, 58, -6)
            out.setPixels(outRow, 0, w2, 0, 2 * y + 1, w2, 1)
        }
        return out
    }

    // ---------------- تیز کردن + منحنی کنتراست ----------------

    /** کرنل ۳×۳ ؛ ردیف‌به‌ردیف و درجا */
    private fun sharpen(bmp: Bitmap, amount: Float) {
        val w = bmp.width
        val h = bmp.height
        if (w < 3 || h < 3) return
        val a = (amount * 256).toInt()
        val center = 256 + 4 * a

        var prev = IntArray(w)
        var cur = IntArray(w)
        var next = IntArray(w)
        val out = IntArray(w)

        bmp.getPixels(cur, 0, w, 0, 0, w, 1)
        System.arraycopy(cur, 0, prev, 0, w)

        for (y in 0 until h) {
            if (y + 1 < h) bmp.getPixels(next, 0, w, 0, y + 1, w, 1)
            else System.arraycopy(cur, 0, next, 0, w)

            for (x in 0 until w) {
                val c = cur[x]
                val l = cur[if (x > 0) x - 1 else 0]
                val r = cur[if (x < w - 1) x + 1 else w - 1]
                val u = prev[x]
                val d = next[x]

                val rr = (center * ((c shr 16) and 0xFF) -
                    a * (((l shr 16) and 0xFF) + ((r shr 16) and 0xFF) + ((u shr 16) and 0xFF) + ((d shr 16) and 0xFF))) shr 8
                val gg = (center * ((c shr 8) and 0xFF) -
                    a * (((l shr 8) and 0xFF) + ((r shr 8) and 0xFF) + ((u shr 8) and 0xFF) + ((d shr 8) and 0xFF))) shr 8
                val bb = (center * (c and 0xFF) -
                    a * ((l and 0xFF) + (r and 0xFF) + (u and 0xFF) + (d and 0xFF))) shr 8

                out[x] = (c and 0xFF000000.toInt()) or
                    (curve[clamp8(rr)] shl 16) or
                    (curve[clamp8(gg)] shl 8) or
                    curve[clamp8(bb)]
            }
            bmp.setPixels(out, 0, w, 0, y, w, 1)

            val t = prev
            prev = cur
            cur = next
            next = t
        }
    }
}
