package com.example.fidiboshot

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.fidiboshot.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etStartPage.setText(Prefs.start(this).toString())
        binding.etEndPage.setText(Prefs.end(this).toString())
        binding.etDelay.setText(Prefs.delay(this).toString())

        binding.toggleDirection.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !updating) Prefs.setRtl(this, id == R.id.btnRtl)
        }

        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Fidibo Screenshot را پیدا کن و روشن کن", Toast.LENGTH_LONG).show()
        }

        binding.switchFloating.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            val service = FidiboScreenshotService.instance
            if (service == null) {
                Toast.makeText(this, "اول Accessibility را روشن کن", Toast.LENGTH_LONG).show()
                render()
                return@setOnCheckedChangeListener
            }
            Prefs.setFloating(this, checked)
            if (checked) service.showOverlay() else service.hideOverlay()
        }

        binding.btnStart.setOnClickListener {
            val start = binding.etStartPage.text.toString().toIntOrNull() ?: 1
            val end = binding.etEndPage.text.toString().toIntOrNull() ?: 1
            val delay = binding.etDelay.text.toString().toLongOrNull() ?: 3000L
            val rtl = binding.toggleDirection.checkedButtonId == R.id.btnRtl

            if (end < start) {
                Toast.makeText(this, "صفحه پایان باید بزرگ‌تر از شروع باشد", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val service = FidiboScreenshotService.instance
            if (service == null) {
                Toast.makeText(this, "اول Accessibility را روشن کن", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Prefs.saveRange(this, start, end, delay)
            service.startCapture(start, end, delay, rtl)
            Toast.makeText(this, "برو داخل کتاب فیدیبو", Toast.LENGTH_LONG).show()
        }

        binding.btnStop.setOnClickListener {
            FidiboScreenshotService.instance?.stopCapture()
        }

        binding.btnSetCrop.setOnClickListener {
            val service = FidiboScreenshotService.instance
            if (service == null) {
                Toast.makeText(this, "اول Accessibility را روشن کن", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (ShotState.running) {
                Toast.makeText(this, "اول توقف بزن", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "برو داخل کتاب فیدیبو؛ ۵ ثانیه دیگر کادر نمایش داده می‌شود", Toast.LENGTH_LONG).show()
            service.showRegionSelectorDelayed(5000L)
            moveTaskToBack(true)
        }

        binding.btnResetCrop.setOnClickListener {
            Prefs.clearCrop(this)
            ShotState.notifyChanged()
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        ShotState.listener = { render() }
        render()
    }

    override fun onPause() {
        ShotState.listener = null
        saveFields()
        super.onPause()
    }

    private fun saveFields() {
        val s = binding.etStartPage.text.toString().toIntOrNull() ?: return
        val e = binding.etEndPage.text.toString().toIntOrNull() ?: return
        val d = binding.etDelay.text.toString().toLongOrNull() ?: return
        Prefs.saveRange(this, s, e, d)
    }

    private fun tint(view: android.view.View, colorRes: Int) {
        view.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun render() {
        updating = true
        val connected = FidiboScreenshotService.instance != null
        val running = ShotState.running

        // Accessibility
        binding.tvAccStatus.text = if (connected) "فعال" else "غیرفعال"
        tint(binding.accDot, if (connected) R.color.success else R.color.danger)
        binding.btnOpenAccessibility.text = if (connected) "تنظیمات" else "فعال‌سازی"

        // میانبر شناور
        binding.switchFloating.isEnabled = connected
        binding.switchFloating.isChecked = connected && Prefs.floating(this)

        // جهت
        binding.toggleDirection.check(if (Prefs.rtl(this)) R.id.btnRtl else R.id.btnLtr)

        // وضعیت
        val total = (ShotState.end - ShotState.start + 1).coerceAtLeast(1)
        when {
            !connected -> {
                binding.tvStatusTitle.text = "نیاز به فعال‌سازی"
                binding.tvStatusSub.text = "سرویس Accessibility را روشن کن"
                tint(binding.statusDot, R.color.warning)
                binding.progress.progress = 0
            }
            running -> {
                val done = (ShotState.current - ShotState.start).coerceIn(0, total)
                binding.tvStatusTitle.text = "در حال اجرا"
                binding.tvStatusSub.text = "صفحه ${ShotState.current} از ${ShotState.end}"
                tint(binding.statusDot, R.color.brand)
                binding.progress.max = total
                binding.progress.progress = done
            }
            ShotState.finished -> {
                binding.tvStatusTitle.text = "تمام شد"
                binding.tvStatusSub.text = "${total} صفحه در Pictures/FidiboPages ذخیره شد"
                tint(binding.statusDot, R.color.success)
                binding.progress.max = total
                binding.progress.progress = total
            }
            else -> {
                binding.tvStatusTitle.text = "آماده"
                binding.tvStatusSub.text = "تنظیمات را بررسی کن و شروع بزن"
                tint(binding.statusDot, R.color.text_secondary)
                binding.progress.progress = 0
            }
        }

        // محدوده اسکرین
        if (Prefs.hasCrop(this)) {
            val m = resources.displayMetrics
            val f = Prefs.crop(this)
            val w = ((f.right - f.left) * m.widthPixels).toInt()
            val h = ((f.bottom - f.top) * m.heightPixels).toInt()
            binding.tvCropStatus.text = "سفارشی • $w × $h پیکسل"
        } else {
            binding.tvCropStatus.text = "کل صفحه"
        }
        binding.btnSetCrop.isEnabled = connected && !running
        binding.btnResetCrop.isEnabled = Prefs.hasCrop(this) && !running

        binding.btnStart.isEnabled = connected && !running
        binding.btnStop.isEnabled = running
        updating = false
    }
}
