package com.example.fidiboshot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Activity نامرئی برای گرفتن مجوزها قبل از ضبط ویدئو (چون سرویس Accessibility نمی‌تواند خودش دیالوگ نشان دهد):
 *   اعلان (اندروید ۱۳+؛ برای دکمه‌ی توقف) → میکروفون/صدا (اگر صدا روشن است) → مجوز ضبط صفحه.
 * بعد سرویس ScreenRecordService را شروع می‌کند و بسته می‌شود.
 */
class ProjectionRequestActivity : AppCompatActivity() {

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) stepAudio()
            else fail("برای ضبط ویدئو مجوز اعلان لازم است (دکمه‌ی توقف ضبط در اعلان است)")
        }

    private val audioLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "مجوز صدا داده نشد؛ بدون صدا ضبط می‌شود", Toast.LENGTH_LONG).show()
            }
            stepProjection()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                val i = Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_START
                    putExtra(ScreenRecordService.EXTRA_CODE, res.resultCode)
                    putExtra(ScreenRecordService.EXTRA_DATA, data)
                }
                ContextCompat.startForegroundService(this, i)
                finish()
            } else {
                fail("ضبط لغو شد")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stepNotif()
    }

    private fun granted(p: String) =
        checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun stepNotif() {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            stepAudio()
        }
    }

    private fun stepAudio() {
        if (Prefs.vAudio(this) != 0 && !granted(Manifest.permission.RECORD_AUDIO)) {
            audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            stepProjection()
        }
    }

    private fun stepProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun fail(msg: String) {
        FidiboScreenshotService.instance?.onVideoFinished(false, msg)
        finish()
    }
}
