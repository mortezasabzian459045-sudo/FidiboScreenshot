package com.example.fidiboshot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * نوشتن PNG کاملاً دستی (بدون کتابخانه) برای اسکرین طولی:
 *  - فریم‌های موقت بی‌افت (lossless) روی دیسک ذخیره می‌شوند تا درز بین قاب‌ها
 *    آرتیفکت فشرده‌سازی نداشته باشد.
 *  - تصویر نهایی ردیف‌به‌ردیف داخل فایل استریم می‌شود تا مصرف حافظه تقریباً ثابت
 *    بماند؛ برای همین ارتفاع کل از قبل معلوم است (مونتاژ دو مرحله‌ای).
 * فشرده‌سازی سبک است (Deflater BEST_SPEED) که برای متن و رابط کاربری کافی است.
 */
object PngWriter {

    private const val IDAT_BATCH = 256 * 1024

    private fun be32(a: ByteArray, i: Int, v: Int) {
        a[i] = (v ushr 24).toByte()
        a[i + 1] = (v ushr 16).toByte()
        a[i + 2] = (v ushr 8).toByte()
        a[i + 3] = v.toByte()
    }

    private fun writeChunk(out: OutputStream, type: String, data: ByteArray) {
        val head = ByteArray(8)
        be32(head, 0, data.size)
        val t = type.toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(t, 0, head, 4, 4)
        out.write(head)
        out.write(data)
        val crc = CRC32()
        crc.update(t)
        crc.update(data)
        val tail = ByteArray(4)
        be32(tail, 0, crc.value.toInt())
        out.write(tail)
    }

    /** نوشتننده‌ی استریمی؛ ردیف‌ها از بالا به پایین داده می‌شوند و IDAT تکه‌تکه نوشته می‌شود */
    class StreamingPng(private val out: OutputStream, private val w: Int, private val h: Int) {

        private val deflater = Deflater(Deflater.BEST_SPEED)
        private val buf = ByteArray(64 * 1024)
        private val rowBytes = ByteArray(1 + w * 3)
        private val idat = ByteArrayOutputStream(IDAT_BATCH + 65 * 1024)
        private var row = 0

        init {
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            val ihdr = ByteArray(13)
            be32(ihdr, 0, w)
            be32(ihdr, 4, h)
            ihdr[8] = 8    // عمق بیت
            ihdr[9] = 2    // RGB واقعی
            ihdr[10] = 0   // روش فشرده‌سازی
            ihdr[11] = 0   // روش فیلتر
            ihdr[12] = 0   // درهم‌تنیدگی
            writeChunk(out, "IHDR", ihdr)
        }

        /** یک ردیف ARGB را می‌نویسد؛ off محل شروع ردیف در آرایه است */
        fun writeRow(argb: IntArray, off: Int = 0) {
            if (row >= h) return
            var k = 0
            rowBytes[k++] = 0   // فیلتر None
            for (x in 0 until w) {
                val p = argb[off + x]
                rowBytes[k++] = ((p shr 16) and 0xFF).toByte()
                rowBytes[k++] = ((p shr 8) and 0xFF).toByte()
                rowBytes[k++] = (p and 0xFF).toByte()
            }
            deflater.setInput(rowBytes, 0, rowBytes.size)
            while (!deflater.needsInput()) {
                val n = deflater.deflate(buf)
                if (n > 0) {
                    idat.write(buf, 0, n)
                    if (idat.size() >= IDAT_BATCH) flushIdat()
                }
            }
            row++
        }

        private fun flushIdat() {
            if (idat.size() > 0) writeChunk(out, "IDAT", idat.toByteArray())
            idat.reset()
        }

        fun finish() {
            deflater.finish()
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n > 0) idat.write(buf, 0, n)
            }
            flushIdat()
            writeChunk(out, "IEND", ByteArray(0))
            deflater.end()
            out.flush()
        }
    }

    /** ذخیره‌ی بی‌افت یک قاب (آرایه‌ی پیکسل) روی دیسک */
    fun writeIntPng(file: File, px: IntArray, w: Int, h: Int) {
        FileOutputStream(file).use { fs ->
            val png = StreamingPng(BufferedOutputStream(fs, 64 * 1024), w, h)
            for (y in 0 until h) png.writeRow(px, y * w)
            png.finish()
        }
    }

    /** خواندن یک قاب از دیسک به آرایه‌ی پیکسل */
    fun readFrame(file: File, w: Int, h: Int): IntArray {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: throw java.io.IOException("frame decode failed: ${file.name}")
        if (bmp.width != w || bmp.height != h) {
            bmp.recycle()
            throw java.io.IOException("frame size mismatch: ${file.name}")
        }
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return px
    }
}
