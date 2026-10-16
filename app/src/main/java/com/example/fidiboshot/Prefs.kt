package com.example.fidiboshot

import android.content.Context

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

    /** true = خروجی PDF ، false = عکس */
    fun pdf(c: Context) = sp(c).getBoolean("pdf", false)
    fun setPdf(c: Context, v: Boolean) = sp(c).edit().putBoolean("pdf", v).apply()

    /** برش ناحیه */
    fun cropEnabled(c: Context) = sp(c).getBoolean("crop_on", false)
    fun setCropEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("crop_on", v).apply()

    /** [left, top, right, bottom] به صورت کسری از 0 تا 1 ؛ اگر انتخاب نشده null */
    fun crop(c: Context): FloatArray? {
        val s = sp(c)
        if (!s.contains("crop_l")) return null
        return floatArrayOf(
            s.getFloat("crop_l", 0f), s.getFloat("crop_t", 0f),
            s.getFloat("crop_r", 1f), s.getFloat("crop_b", 1f)
        )
    }

    fun setCrop(c: Context, l: Float, t: Float, r: Float, b: Float) =
        sp(c).edit().putFloat("crop_l", l).putFloat("crop_t", t)
            .putFloat("crop_r", r).putFloat("crop_b", b).apply()
}
