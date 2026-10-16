package com.example.fidiboshot

import android.content.Context
import android.graphics.RectF

/** تنظیمات مشترک بین برنامه و میانبر شناور */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("fidibo_prefs", Context.MODE_PRIVATE)

    fun start(c: Context) = sp(c).getInt("start", 1)
    fun end(c: Context) = sp(c).getInt("end", 10)
    fun delay(c: Context) = sp(c).getLong("delay", 3000L)
    fun saveRange(c: Context, start: Int, end: Int, delay: Long) =
        sp(c).edit().putInt("start", start).putInt("end", end).putLong("delay", delay).apply()

    /** true = راست به چپ */
    fun rtl(c: Context) = sp(c).getBoolean("rtl", true)
    fun setRtl(c: Context, v: Boolean) = sp(c).edit().putBoolean("rtl", v).apply()

    fun floating(c: Context) = sp(c).getBoolean("floating", false)
    fun setFloating(c: Context, v: Boolean) = sp(c).edit().putBoolean("floating", v).apply()

    // ---- محدوده اسکرین (به صورت نسبت 0..1 از صفحه، تا با هر وضوحی کار کند) ----
    private val FULL = RectF(0f, 0f, 1f, 1f)

    fun hasCrop(c: Context) = sp(c).getBoolean("crop_on", false)

    /** مستطیل نسبی؛ اگر تنظیم نشده باشد کل صفحه */
    fun crop(c: Context): RectF {
        if (!hasCrop(c)) return RectF(FULL)
        val p = sp(c)
        return RectF(
            p.getFloat("crop_l", 0f), p.getFloat("crop_t", 0f),
            p.getFloat("crop_r", 1f), p.getFloat("crop_b", 1f)
        )
    }

    fun setCrop(c: Context, r: RectF) = sp(c).edit()
        .putBoolean("crop_on", true)
        .putFloat("crop_l", r.left).putFloat("crop_t", r.top)
        .putFloat("crop_r", r.right).putFloat("crop_b", r.bottom)
        .apply()

    fun clearCrop(c: Context) = sp(c).edit().putBoolean("crop_on", false).apply()
}
