package com.example.fidiboshot

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

        binding.toggleFormat.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !updating) Prefs.setPdf(this, id == R.id.btnFmtPdf)
        }

        binding.switchCrop.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            if (checked && Prefs.crop(this) == null) {
                // هنوز ناحیه‌ای انتخاب نشده
                Prefs.setCropEnabled(this, false)
                render()
                startAreaSelection()
                return@setOnCheckedChangeListener
            }
            Prefs.setCropEnabled(this, checked)
            render()
        }

        binding.btnSelectArea.setOnClickListener { startAreaSelection() }

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
    }

    /** بعد از چند ثانیه قاب انتخاب ناحیه روی صفحه‌ی فعلی (کتاب) نمایش داده می‌شود */
    private fun startAreaSelection() {
        if (FidiboScreenshotService.instance == null) {
            Toast.makeText(this, "اول Accessibility را روشن کن", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "۴ ثانیه وقت داری بری داخل کتاب فیدیبو", Toast.LENGTH_LONG).show()
        Handler(Looper.getMainLooper()).postDelayed({
            FidiboScreenshotService.instance?.showCropSelector()
        }, 4000)
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
        val processing = ShotState.processing

        // Accessibility
        binding.tvAccStatus.text = if (connected) "فعال" else "غیرفعال"
        tint(binding.accDot, if (connected) R.color.success else R.color.danger)
        binding.btnOpenAccessibility.text = if (connected) "تنظیمات" else "فعال‌سازی"

        // میانبر شناور
        binding.switchFloating.isEnabled = connected
        binding.switchFloating.isChecked = connected && Prefs.floating(this)

        // جهت و فرمت خروجی
        binding.toggleDirection.check(if (Prefs.rtl(this)) R.id.btnRtl else R.id.btnLtr)
        binding.toggleFormat.check(if (Prefs.pdf(this)) R.id.btnFmtPdf else R.id.btnFmtImg)

        // ناحیه‌ی برش
        val crop = Prefs.crop(this)
        binding.switchCrop.isChecked = crop != null && Prefs.cropEnabled(this)
        binding.tvCropSummary.text = if (crop == null) {
            "هنوز ناحیه‌ای انتخاب نشده (کل صفحه)"
        } else {
            val w = ((crop[2] - crop[0]) * 100).toInt()
            val h = ((crop[3] - crop[1]) * 100).toInt()
            "ناحیه‌ی ذخیره‌شده: $w٪ عرض × $h٪ ارتفاع"
        }
        binding.btnSelectArea.isEnabled = connected && !running && !processing
        binding.switchCrop.isEnabled = !running && !processing

        // وضعیت
        val total = (ShotState.end - ShotState.start + 1).coerceAtLeast(1)
        val done = (ShotState.current - ShotState.start).coerceIn(0, total)
        when {
            !connected -> {
                binding.tvStatusTitle.text = "نیاز به فعال‌سازی"
                binding.tvStatusSub.text = "سرویس Accessibility را روشن کن"
                tint(binding.statusDot, R.color.warning)
                binding.progress.progress = 0
            }
            processing -> {
                binding.tvStatusTitle.text = "در حال ساخت PDF…"
                binding.tvStatusSub.text = "کمی صبر کن، صفحه‌ها در حال جمع شدن هستند"
                tint(binding.statusDot, R.color.brand)
                binding.progress.max = total
                binding.progress.progress = total
            }
            running -> {
                binding.tvStatusTitle.text = "در حال اجرا"
                binding.tvStatusSub.text = "صفحه ${ShotState.current} از ${ShotState.end}"
                tint(binding.statusDot, R.color.brand)
                binding.progress.max = total
                binding.progress.progress = done
            }
            ShotState.resultText != null -> {
                binding.tvStatusTitle.text = if (ShotState.finished) "تمام شد" else "متوقف شد"
                binding.tvStatusSub.text = ShotState.resultText
                tint(binding.statusDot, if (ShotState.finished) R.color.success else R.color.warning)
                binding.progress.max = total
                binding.progress.progress = if (ShotState.finished) total else done
            }
            else -> {
                binding.tvStatusTitle.text = "آماده"
                binding.tvStatusSub.text = "تنظیمات را بررسی کن و شروع بزن"
                tint(binding.statusDot, R.color.text_secondary)
                binding.progress.progress = 0
            }
        }

        binding.btnStart.isEnabled = connected && !running && !processing
        binding.btnStop.isEnabled = running
        updating = false
    }
}
