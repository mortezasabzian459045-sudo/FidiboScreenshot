package com.example.fidiboshot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.OutputStream

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
            // راست → چپ
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
            val resolver = contentResolver
            val fileName = "page_${String.format("%04d", currentPage)}.png"

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FidiboPages")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )

            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out: OutputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                Log.d(TAG, "Saved to gallery: $uri")
            } else {
                Log.e(TAG, "MediaStore insert failed")
            }
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
