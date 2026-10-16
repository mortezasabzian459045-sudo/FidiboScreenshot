package com.example.fidiboshot

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * این Activity هیچ صفحه‌ای نشان نمی‌دهد.
 * - اگر سرویس Accessibility روشن باشد: میانبر شناور را نمایش می‌دهد و بسته می‌شود.
 * - اگر روشن نباشد: تنظیمات Accessibility را باز می‌کند تا کاربر یک بار روشنش کند.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Prefs.setFloating(this, true)
        val service = FidiboScreenshotService.instance

        when {
            service != null -> service.showOverlay()
            isServiceEnabled() -> {
                // سرویس روشن است ولی هنوز وصل نشده؛ بعد از اتصال خودش میانبر را نشان می‌دهد
                Toast.makeText(this, "چند لحظه‌ی دیگر میانبر ظاهر می‌شود", Toast.LENGTH_SHORT).show()
            }
            else -> {
                Toast.makeText(
                    this,
                    "Fidibo Screenshot را پیدا کن و روشن کن، بعد دوباره برنامه را باز کن",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        finish()
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it)?.packageName == packageName
        }
    }
}
