package com.example.fidiboshot

import android.content.Context

/** تنظیمات ذخیره‌شده‌ی میانبر شناور */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("fidibo_prefs", Context.MODE_PRIVATE)

    /** تعداد صفحاتی که از صفحه‌ی فعلی کتاب گرفته می‌شود */
    fun count(c: Context) = sp(c).getInt("count", 10)
    fun setCount(c: Context, v: Int) = sp(c).edit().putInt("count", v).apply()

    /** تأخیر بین صفحات به میلی‌ثانیه */
    fun delay(c: Context) = sp(c).getLong("delay", 3000L)
    fun setDelay(c: Context, v: Long) = sp(c).edit().putLong("delay", v).apply()

    /** true = راست به چپ */
    fun rtl(c: Context) = sp(c).getBoolean("rtl", true)
    fun setRtl(c: Context, v: Boolean) = sp(c).edit().putBoolean("rtl", v).apply()

    /** آیا میانبر شناور باید نمایش داده شود (پیش‌فرض بله) */
    fun floating(c: Context) = sp(c).getBoolean("floating", true)
    fun setFloating(c: Context, v: Boolean) = sp(c).edit().putBoolean("floating", v).apply()

    /** true = خروجی PDF ، false = عکس */
    fun pdf(c: Context) = sp(c).getBoolean("pdf", false)
    fun setPdf(c: Context, v: Boolean) = sp(c).edit().putBoolean("pdf", v).apply()

    /** افزایش کیفیت خودکار (تنظیم روشنایی و کنتراست + تیز کردن) */
    fun enhance(c: Context) = sp(c).getBoolean("enhance", true)
    fun setEnhance(c: Context, v: Boolean) = sp(c).edit().putBoolean("enhance", v).apply()

    /** بزرگ‌کردن ۲ برابر با نرم‌سازی (حجم بیشتر) */
    fun upscale(c: Context) = sp(c).getBoolean("upscale", false)
    fun setUpscale(c: Context, v: Boolean) = sp(c).edit().putBoolean("upscale", v).apply()

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
