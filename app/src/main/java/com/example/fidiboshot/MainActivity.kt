package com.example.fidiboshot

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.view.View
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

        // خروجی
        binding.toggleOutput.check(if (Prefs.outputPdf(this)) R.id.btnOutputPdf else R.id.btnOutputImage)
        binding.toggleOutput.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !updating) Prefs.setOutputPdf(this, id == R.id.btnOutputPdf)
        }

        // اسکرین موضعی
        binding.switchCrop.isChecked = Prefs.cropEnabled(this)
        binding.layoutCropFields.visibility = if (Prefs.cropEnabled(this)) View.VISIBLE else View.GONE
        binding.etCropTop.setText(Prefs.cropTop(this).toString())
        binding.etCropBottom.setText(Prefs.cropBottom(this).toString())
        binding.etCropLeft.setText(Prefs.cropLeft(this).toString())
        binding.etCropRight.setText(Prefs.cropRight(this).toString())

        binding.switchCrop.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            Prefs.setCropEnabled(this, checked)
            binding.layoutCropFields.visibility = if (checked) View.VISIBLE else View.GONE
        }

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

            // ذخیره کراپ
            if (Prefs.cropEnabled(this)) {
                val top = binding.etCropTop.text.toString().toIntOrNull() ?: 0
                val bottom = binding.etCropBottom.text.toString().toIntOrNull() ?: 0
                val left = binding.etCropLeft.text.toString().toIntOrNull() ?: 0
                val right = binding.etCropRight.text.toString().toIntOrNull() ?: 0
                Prefs.saveCrop(this, top, bottom, left, right)
            }

            Prefs.saveRange(this, start, end, delay)
            Prefs.setOutputPdf(this, binding.toggleOutput.checkedButtonId == R.id.btnOutputPdf)
            Prefs.setRtl(this, rtl)

            service.startCapture(start, end, delay, rtl)
            Toast.makeText(this, "برو داخل کتاب فیدیبو", Toast.LENGTH_LONG).show()
        }

        binding.btnStop.setOnClickListener {
            FidiboScreenshotService.instance?.stopCapture()
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

        if (Prefs.cropEnabled(this)) {
            val top = binding.etCropTop.text.toString().toIntOrNull() ?: 0
            val bottom = binding.etCropBottom.text.toString().toIntOrNull() ?: 0
            val left = binding.etCropLeft.text.toString().toIntOrNull() ?: 0
            val right = binding.etCropRight.text.toString().toIntOrNull() ?: 0
            Prefs.saveCrop(this, top, bottom, left, right)
        }
        Prefs.setOutputPdf(this, binding.toggleOutput.checkedButtonId == R.id.btnOutputPdf)
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

        // خروجی
        binding.toggleOutput.check(if (Prefs.outputPdf(this)) R.id.btnOutputPdf else R.id.btnOutputImage)

        // اسکرین موضعی
        binding.switchCrop.isChecked = Prefs.cropEnabled(this)
        binding.layoutCropFields.visibility = if (Prefs.cropEnabled(this)) View.VISIBLE else View.GONE

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
                val out = if (Prefs.outputPdf(this)) "PDF" else "تصاویر"
                binding.tvStatusTitle.text = "تمام شد"
                binding.tvStatusSub.text = if (Prefs.outputPdf(this))
                    "$total صفحه به صورت PDF در Downloads/FidiboPages ذخیره شد"
                else
                    "$total صفحه در Pictures/FidiboPages ذخیره شد"
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

        binding.btnStart.isEnabled = connected && !running
        binding.btnStop.isEnabled = running
        updating = false
    }
}
