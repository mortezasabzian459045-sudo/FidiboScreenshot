package com.example.fidiboshot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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
        var instance: FidiboScreenshotService? = null
        private const val TAG = "FidiboShot"
    }

    private var isRunning = false
    private var currentPage = 1
    private var endPage = 1
    private var delayMs = 3000L
    private var swipeRightToLeft = true

    private val handler = Handler(Looper.getMainLooper())

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || currentPage > endPage) {
                stopCapture()
                return
            }

            Log.d(TAG, "Capturing page $currentPage")
            takeScreenshotAndSave()

            handler.postDelayed({
                turnNextPage()
                currentPage++
                if (isRunning && currentPage <= endPage) {
                    handler.postDelayed(this, delayMs)
                } else {
                    stopCapture()
                }
            }, 600)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Service connected")
    }

    fun startCapture(start: Int, end: Int, delay: Long, rtl: Boolean) {
        if (isRunning) return
        currentPage = start
        endPage = end
        delayMs = delay
        swipeRightToLeft = rtl
        isRunning = true
        handler.post(captureRunnable)
        Log.d(TAG, "Started: $start → $end | RTL=$rtl")
    }

    fun stopCapture() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Stopped")
    }

    private fun turnNextPage() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        val path = Path()
        if (swipeRightToLeft) {
            // راست → چپ (کتاب‌های فارسی/عربی)
            path.moveTo(width * 0.85f, height * 0.5f)
            path.lineTo(width * 0.15f, height * 0.5f)
        } else {
            // چپ → راست
            path.moveTo(width * 0.15f, height * 0.5f)
            path.lineTo(width * 0.85f, height * 0.5f)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.e(TAG, "Need Android 11+")
            return
        }

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val buffer = screenshot.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        buffer.close()

                        if (bitmap != null) {
                            saveBitmap(bitmap)
                        } else {
                            Log.e(TAG, "Bitmap null")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Screenshot process error", e)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "takeScreenshot failed code=$errorCode")
                }
            }
        )
    }

    private fun saveBitmap(bitmap: Bitmap) {
        try {
            val dir = File(getExternalFilesDir(null), "FidiboPages")
            if (!dir.exists()) dir.mkdirs()

            val fileName = "page_${String.format("%04d", currentPage)}.png"
            val file = File(dir, fileName)

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.d(TAG, "Saved → ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Save failed", e)
        } finally {
            bitmap.recycle()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopCapture()
    }

    override fun onDestroy() {
        instance = null
        stopCapture()
        super.onDestroy()
    }
}
