package com.example.fidiboshot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.io.FileOutputStream

class FidiboScreenshotService : AccessibilityService() {

    companion object {
        const val ACTION_START = "com.example.fidiboshot.START"
        const val ACTION_STOP = "com.example.fidiboshot.STOP"
        const val EXTRA_START = "start"
        const val EXTRA_END = "end"
        const val EXTRA_DELAY = "delay"
        private const val TAG = "FidiboShot"
    }

    private var isRunning = false
    private var currentPage = 1
    private var endPage = 1
    private var delayMs = 3000L

    private val handler = Handler(Looper.getMainLooper())

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || currentPage > endPage) {
                stopCapture()
                return
            }

            Log.d(TAG, "Capturing page $currentPage")
            takeScreenshotAndSave()
            // کمی صبر بعد از اسکرین، بعد ورق بزن
            handler.postDelayed({
                turnNextPage()
                currentPage++
                handler.postDelayed(this, delayMs)
            }, 400)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentPage = intent.getIntExtra(EXTRA_START, 1)
                endPage = intent.getIntExtra(EXTRA_END, 1)
                delayMs = intent.getLongExtra(EXTRA_DELAY, 3000L)
                startCapture()
            }
            ACTION_STOP -> stopCapture()
        }
        return START_STICKY
    }

    private fun startCapture() {
        if (isRunning) return
        isRunning = true
        handler.post(captureRunnable)
        Log.d(TAG, "Started capture from $currentPage to $endPage")
    }

    private fun stopCapture() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Stopped")
    }

    private fun turnNextPage() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        // سوایپ از راست به چپ (مناسب بیشتر کتاب‌خوان‌ها)
        val path = Path().apply {
            moveTo(width * 0.82f, height * 0.5f)
            lineTo(width * 0.18f, height * 0.5f)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 280)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Page turned")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Gesture cancelled")
            }
        }, null)
    }

    private fun takeScreenshotAndSave() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val colorSpace = screenshot.colorSpace
                        val bitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        buffer.close()

                        bitmap?.let { saveBitmap(it) }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed: $errorCode")
                    }
                }
            )
        } else {
            Log.e(TAG, "Android 11+ required for takeScreenshot")
        }
    }

    private fun saveBitmap(bitmap: Bitmap) {
        try {
            val dir = File(getExternalFilesDir(null), "FidiboPages")
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, "page_${String.format("%04d", currentPage)}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.d(TAG, "Saved: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Save failed", e)
        } finally {
            bitmap.recycle()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // لازم نیست فعلاً چیزی گوش بدیم
    }

    override fun onInterrupt() {
        stopCapture()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
