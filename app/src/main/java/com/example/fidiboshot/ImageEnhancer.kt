package com.example.fidiboshot

import android.graphics.Bitmap

/**
 * افزایش کیفیت خودکار برای اسکرین‌شات صفحات کتاب:
 * ۱) تنظیم خودکار سطوح (سفیدِ صفحه‌ی اسکن‌شده سفید و متن پررنگ‌تر می‌شود؛ روی صفحه‌ی متنیِ تمیز تقریباً اثری ندارد)
 * ۲) بزرگ‌کردن ۲× با نرم‌سازی (اختیاری)
 * ۳) تیز کردن ملایم لبه‌ی حروف
 * همه‌ی کارها ردیف‌به‌ردیف انجام می‌شود تا حافظه‌ی زیادی مصرف نشود. باید روی ترد پس‌زمینه صدا زده شود.
 */
object ImageEnhancer {

    fun enhance(input: Bitmap, upscale: Boolean): Bitmap {
        var bmp = ensureMutable(input)

        computeLut(bmp)?.let { applyLut(bmp, it) }

        var upscaled = false
        if (upscale && bmp.width.toLong() * bmp.height <= 3_600_000L) {
            try {
                val scaled = Bitmap.createScaledBitmap(bmp, bmp.width * 2, bmp.height * 2, true)
                if (scaled !== bmp) {
                    bmp.recycle()
                    bmp = ensureMutable(scaled)
                    upscaled = true
                }
            } catch (e: OutOfMemoryError) {
                // حافظه کم است؛ بدون بزرگ‌کردن ادامه می‌دهیم
            }
        }

        sharpen(bmp, if (upscaled) 0.6f else 0.4f)
        return bmp
    }

    private fun ensureMutable(b: Bitmap): Bitmap {
        if (b.isMutable && b.config == Bitmap.Config.ARGB_8888) return b
        val copy = b.copy(Bitmap.Config.ARGB_8888, true)
        b.recycle()
        return copy
    }

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

        val lowTarget = (total * 0.005).toInt()
        val highTarget = (total * 0.995).toInt()
        var cum = 0
        var low = 0
        var high = 255
        var lowSet = false
        for (i in 0..255) {
            cum += hist[i]
            if (!lowSet && cum > lowTarget) { low = i; lowSet = true }
            if (cum >= highTarget) { high = i; break }
        }

        // حدها را محدود می‌کنیم تا صفحه‌ی تیره یا عکس خراب نشود
        low = minOf(low, 60)
        high = maxOf(high, 150)
        if (high - low < 60) return null
        if (low <= 6 && high >= 249) return null   // صفحه از قبل پرکنتراست است

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

    /** تیز کردن با کرنل ۳×۳ ؛ ردیف‌به‌ردیف و درجا */
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
                    (rr.coerceIn(0, 255) shl 16) or
                    (gg.coerceIn(0, 255) shl 8) or
                    bb.coerceIn(0, 255)
            }
            bmp.setPixels(out, 0, w, 0, y, w, 1)

            val t = prev
            prev = cur
            cur = next
            next = t
        }
    }
}
