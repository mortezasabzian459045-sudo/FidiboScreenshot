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
}
