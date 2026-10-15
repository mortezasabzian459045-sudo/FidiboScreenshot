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

    // ---- خروجی نهایی ----
    /** true = PDF ، false = تصویر PNG */
    fun outputPdf(c: Context) = sp(c).getBoolean("output_pdf", false)
    fun setOutputPdf(c: Context, v: Boolean) = sp(c).edit().putBoolean("output_pdf", v).apply()

    // ---- اسکرین موضعی (کراپ) ----
    fun cropEnabled(c: Context) = sp(c).getBoolean("crop_enabled", false)
    fun setCropEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("crop_enabled", v).apply()

    /** درصد برش از هر طرف (۰ تا ۴۰) */
    fun cropTop(c: Context) = sp(c).getInt("crop_top", 8)
    fun cropBottom(c: Context) = sp(c).getInt("crop_bottom", 8)
    fun cropLeft(c: Context) = sp(c).getInt("crop_left", 0)
    fun cropRight(c: Context) = sp(c).getInt("crop_right", 0)

    fun saveCrop(c: Context, top: Int, bottom: Int, left: Int, right: Int) =
        sp(c).edit()
            .putInt("crop_top", top.coerceIn(0, 40))
            .putInt("crop_bottom", bottom.coerceIn(0, 40))
            .putInt("crop_left", left.coerceIn(0, 40))
            .putInt("crop_right", right.coerceIn(0, 40))
            .apply()
}
