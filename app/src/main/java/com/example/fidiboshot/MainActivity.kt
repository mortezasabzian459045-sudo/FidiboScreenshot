package com.example.fidiboshot

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fidiboshot.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Fidibo Screenshot را پیدا کن و روشن کن", Toast.LENGTH_LONG).show()
        }

        binding.btnStart.setOnClickListener {
            val start = binding.etStartPage.text.toString().toIntOrNull() ?: 1
            val end = binding.etEndPage.text.toString().toIntOrNull() ?: 1
            val delay = binding.etDelay.text.toString().toLongOrNull() ?: 3000L

            // جهت: true = راست به چپ (پیش‌فرض کتاب فارسی)، false = چپ به راست
            val swipeRightToLeft = binding.rgDirection.checkedRadioButtonId == binding.rbRtl.id

            if (end < start) {
                Toast.makeText(this, "صفحه پایان باید بزرگ‌تر از شروع باشد", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (FidiboScreenshotService.instance == null) {
                Toast.makeText(this, "اول Accessibility را روشن کن", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            FidiboScreenshotService.instance?.startCapture(start, end, delay, swipeRightToLeft)
            binding.tvStatus.text = "وضعیت: در حال اجرا ($start → $end)"
            Toast.makeText(this, "برو داخل کتاب فیدیبو", Toast.LENGTH_LONG).show()
        }

        binding.btnStop.setOnClickListener {
            FidiboScreenshotService.instance?.stopCapture()
            binding.tvStatus.text = "وضعیت: متوقف شد"
        }
    }
}
