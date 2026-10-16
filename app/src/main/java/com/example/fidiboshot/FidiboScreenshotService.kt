package com.example.fidiboshot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/** وضعیت اشتراکی برای نمایش در UI برنامه */
object ShotState {
    @Volatile var running = false
    var current = 0
    var start = 0
    var end = 0
    var finished = false
    var processing = false          // در حال ساخت PDF
    var resultText: String? = null  // پیام نتیجه‌ی آخرین اجرا
    var listener: (() -> Unit)? = null
    fun notifyChanged() { listener?.invoke() }
}

class FidiboScreenshotService : AccessibilityService() {

    companion object {
        var instance: FidiboScreenshotService? = null
        private const val TAG = "FidiboShot"
    }

    private var isRunning = false
    private var pdfMode = false
    private var currentPage = 1
    private var startPage = 1
    private var endPage = 1
    private var delayMs = 3000L
    private var resumePage: Int? = null   // اگر وسط کار متوقف شد (حالت عکس)، از همین صفحه ادامه بده
    private var pending = 0               // تعداد اسکرین‌شات‌های در انتظار پاسخ

    private val handler = Handler(Looper.getMainLooper())
    private val uiHandler = Handler(Looper.getMainLooper())   // جدا از handler تا با توقف پاک نشود
    private val io = Executors.newSingleThreadExecutor()

    // ---- میانبر شناور ----
    private var wm: WindowManager? = null
    private var overlay: LinearLayout? = null
    private var overlayLp: WindowManager.LayoutParams? = null
    private var trashView: TextView? = null
    private var counterView: TextView? = null
    private var toggleBtn: TextView? = null
    private var chipRtl: TextView? = null
    private var chipLtr: TextView? = null
    private var cropBtn: TextView? = null
    private var selector: FrameLayout? = null

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || currentPage > endPage) {
                finishCapture()
                return
            }
            val page = currentPage
            // میانبر را موقتاً پنهان می‌کنیم تا داخل اسکرین‌شات نیفتد
            setOverlayVisible(false)

            handler.postDelayed({
                if (!isRunning) return@postDelayed
                Log.d(TAG, "Capturing page $page")
                takeScreenshotAndSave(page)

                handler.postDelayed({
                    if (!isRunning) return@postDelayed
                    if (page >= endPage) {
                        currentPage++
                        finishCapture()
                    } else {
                        turnNextPage()
                        currentPage++
                        syncState()
                        handler.postDelayed(this, delayMs)
                    }
                }, 600)
            }, 150)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (Prefs.floating(this)) showOverlay()
        syncState()
        Log.d(TAG, "Service connected")
    }

    // ================= کنترل ضبط =================

    fun startCapture(start: Int, end: Int, delay: Long, rtl: Boolean) {
        if (isRunning || ShotState.processing) return
        Prefs.setRtl(this, rtl)
        pdfMode = Prefs.pdf(this)
        resumePage = null
        ShotState.finished = false
        ShotState.resultText = null
        if (pdfMode) resetTempDir()
        startPage = start
        currentPage = start
        endPage = end
        delayMs = delay
        isRunning = true
        syncState()
        handler.post(captureRunnable)
        Log.d(TAG, "Started: $start → $end | RTL=$rtl | PDF=$pdfMode")
    }

    fun stopCapture() = finishCapture()

    private fun finishCapture() {
        val wasRunning = isRunning
        val completed = wasRunning && currentPage > endPage
        isRunning = false
        handler.removeCallbacksAndMessages(null)

        if (wasRunning) {
            resumePage = if (completed || pdfMode) null else currentPage
            val done = (currentPage - startPage).coerceIn(0, endPage - startPage + 1)
            ShotState.finished = completed
            if (pdfMode) {
                ShotState.processing = true
                ShotState.resultText = null
                buildPdfWhenReady(0)
            } else {
                ShotState.resultText = "$done صفحه در Pictures/FidiboPages ذخیره شد"
            }
        }
        setOverlayVisible(true)
        syncState()
        Log.d(TAG, "Stopped")
    }

    private fun startFromOverlay() {
        if (ShotState.processing) {
            Toast.makeText(this, "در حال ساخت PDF، کمی صبر کن", Toast.LENGTH_SHORT).show()
            return
        }
        val s = resumePage ?: Prefs.start(this)
        val e = Prefs.end(this)
        if (e < s) {
            Toast.makeText(this, "صفحه پایان باید بزرگ‌تر از شروع باشد", Toast.LENGTH_SHORT).show()
            return
        }
        startCapture(s, e, Prefs.delay(this), Prefs.rtl(this))
    }

    private fun syncState() {
        ShotState.running = isRunning
        ShotState.current = currentPage
        ShotState.start = startPage
        ShotState.end = endPage
        updateOverlayState()
        ShotState.notifyChanged()
    }

    private fun turnNextPage() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val rtl = Prefs.rtl(this)

        val path = Path()
        if (rtl) {
            path.moveTo(width * 0.85f, height * 0.5f)
            path.lineTo(width * 0.15f, height * 0.5f)
        } else {
            path.moveTo(width * 0.15f, height * 0.5f)
            path.lineTo(width * 0.85f, height * 0.5f)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                setOverlayVisible(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Gesture cancelled")
                setOverlayVisible(true)
            }
        }, null)
    }

    // ================= اسکرین‌شات، برش و ذخیره =================

    private fun takeScreenshotAndSave(page: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.e(TAG, "Need Android 11+")
            return
        }
        pending++
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
                        if (bitmap != null) io.execute { processAndSave(bitmap, page) }
                        else Log.e(TAG, "Bitmap null")
                    } catch (e: Exception) {
                        Log.e(TAG, "Screenshot process error", e)
                    } finally {
                        pending--
                    }
                }
                override fun onFailure(errorCode: Int) {
                    pending--
                    Log.e(TAG, "takeScreenshot failed code=$errorCode")
                }
            }
        )
    }

    /** روی ترد پس‌زمینه اجرا می‌شود */
    private fun processAndSave(source: Bitmap, page: Int) {
        val bmp = cropIfNeeded(source)
        try {
            if (pdfMode) saveTemp(bmp, page) else saveBitmap(bmp, page)
        } catch (e: Exception) {
            Log.e(TAG, "Save failed", e)
        } finally {
            bmp.recycle()
        }
    }

    private fun cropIfNeeded(src: Bitmap): Bitmap {
        if (!Prefs.cropEnabled(this)) return src
        val f = Prefs.crop(this) ?: return src
        val x = (f[0] * src.width).toInt().coerceIn(0, src.width - 1)
        val y = (f[1] * src.height).toInt().coerceIn(0, src.height - 1)
        val w = ((f[2] - f[0]) * src.width).toInt().coerceIn(1, src.width - x)
        val h = ((f[3] - f[1]) * src.height).toInt().coerceIn(1, src.height - y)
        val out = Bitmap.createBitmap(src, x, y, w, h)
        if (out !== src) src.recycle()
        return out
    }

    // ---- خروجی عکس ----
    private fun saveBitmap(bitmap: Bitmap, page: Int) {
        val resolver = contentResolver
        val fileName = "page_${String.format("%04d", page)}.png"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FidiboPages")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { out: OutputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            cv.clear()
            cv.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, cv, null, null)
        } else {
            Log.e(TAG, "MediaStore insert failed")
        }
    }

    // ---- خروجی PDF ----
    private fun tempDir() = File(cacheDir, "fidibo_pages")

    private fun resetTempDir() {
        tempDir().deleteRecursively()
        tempDir().mkdirs()
    }

    private fun saveTemp(bitmap: Bitmap, page: Int) {
        val dir = tempDir().apply { mkdirs() }
        val f = File(dir, "page_${String.format("%05d", page)}.jpg")
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    /** صبر می‌کند تا همه‌ی اسکرین‌شات‌های در راه تمام شوند، بعد PDF می‌سازد */
    private fun buildPdfWhenReady(tries: Int) {
        if (pending > 0 && tries < 25) {
            uiHandler.postDelayed({ buildPdfWhenReady(tries + 1) }, 200)
            return
        }
        io.execute { buildPdf() }
    }

    private fun buildPdf() {
        val dir = tempDir()
        val files = dir.listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) {
            reportPdf("صفحه‌ای برای ساخت PDF وجود نداشت")
            return
        }
        val doc = PdfDocument()
        try {
            var index = 0
            for (f in files) {
                val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue
                index++
                val info = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, index).create()
                val page = doc.startPage(info)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                doc.finishPage(page)
                bmp.recycle()
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                reportPdf("ذخیره‌ی PDF فقط روی اندروید ۱۰ به بالا پشتیبانی می‌شود")
                return
            }

            val name = "fidibo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".pdf"
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/FidiboPages")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            if (uri == null) {
                reportPdf("ساخت فایل PDF ناموفق بود")
                return
            }
            contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
            cv.clear()
            cv.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, cv, null, null)

            reportPdf("PDF با $index صفحه در Download/FidiboPages ذخیره شد")
        } catch (e: Exception) {
            Log.e(TAG, "PDF failed", e)
            reportPdf("خطا در ساخت PDF")
        } finally {
            doc.close()
            dir.deleteRecursively()
        }
    }

    private fun reportPdf(text: String) {
        uiHandler.post {
            ShotState.processing = false
            ShotState.resultText = text
            ShotState.notifyChanged()
            updateOverlayState()
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
    }

    // ================= میانبر شناور =================
    // با TYPE_ACCESSIBILITY_OVERLAY ساخته می‌شود؛ نیازی به مجوز «نمایش روی سایر برنامه‌ها» نیست.

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(color: Int, radiusDp: Int = 100) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    fun showOverlay() {
        if (overlay != null) return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(6), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2202124"))
                cornerRadius = dp(30).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            elevation = dp(8).toFloat()
        }

        val handle = TextView(this).apply {
            text = "F"; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            background = pill(Color.parseColor("#1A73E8"))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        root.addView(handle)

        counterView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        root.addView(counterView)

        fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label; gravity = Gravity.CENTER; textSize = 16f
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(6) }
            setOnClickListener { onClick() }
        }
        chipRtl = chip("→") { Prefs.setRtl(this, true); ShotState.notifyChanged(); updateOverlayState() }
        chipLtr = chip("←") { Prefs.setRtl(this, false); ShotState.notifyChanged(); updateOverlayState() }
        root.addView(chipRtl); root.addView(chipLtr)

        // دکمه‌ی انتخاب ناحیه (مربع خط‌چین)
        cropBtn = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(6) }
            setOnClickListener { showCropSelector() }
        }
        root.addView(cropBtn)

        toggleBtn = TextView(this).apply {
            gravity = Gravity.CENTER; textSize = 16f; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) }
            setOnClickListener { if (isRunning) stopCapture() else startFromOverlay() }
        }
        root.addView(toggleBtn)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(220) }

        overlay = root
        overlayLp = lp
        attachDrag(handle)
        manager.addView(root, lp)
        updateOverlayState()
    }

    fun hideOverlay() {
        hideTrash()
        overlay?.let { runCatching { wm?.removeView(it) } }
        overlay = null
        overlayLp = null
    }

    private fun updateOverlayState() {
        val rtl = Prefs.rtl(this)
        val on = Color.parseColor("#1A73E8")
        val gray = Color.parseColor("#9AA0A6")
        chipRtl?.apply { background = pill(if (rtl) on else Color.TRANSPARENT); setTextColor(if (rtl) Color.WHITE else gray) }
        chipLtr?.apply { background = pill(if (!rtl) on else Color.TRANSPARENT); setTextColor(if (!rtl) Color.WHITE else gray) }

        val cropOn = Prefs.cropEnabled(this) && Prefs.crop(this) != null
        cropBtn?.background = InsetDrawable(GradientDrawable().apply {
            setStroke(dp(2), if (cropOn) on else Color.WHITE, dp(4).toFloat(), dp(3).toFloat())
            cornerRadius = dp(3).toFloat()
        }, dp(9))

        val busy = ShotState.processing
        toggleBtn?.apply {
            text = if (busy) "…" else if (isRunning) "■" else "▶"
            background = pill(Color.parseColor(if (busy) "#5F6368" else if (isRunning) "#D93025" else "#1E8E3E"))
        }
        counterView?.apply {
            if (isRunning) { text = "$currentPage/$endPage"; visibility = View.VISIBLE }
            else visibility = View.GONE
        }
    }

    private fun setOverlayVisible(visible: Boolean) {
        val o = overlay ?: return
        val lp = overlayLp ?: return
        o.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        lp.flags = if (visible) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm?.updateViewLayout(o, lp) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(handle: View) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var longPressed = false

        val longPress = Runnable {
            longPressed = true
            handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showTrash()
        }

        handle.setOnTouchListener { _, e ->
            val lp = overlayLp ?: return@setOnTouchListener true
            val o = overlay ?: return@setOnTouchListener true
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    moved = false; longPressed = false
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        if (!longPressed) handler.removeCallbacks(longPress)
                    }
                    if (moved) {
                        lp.x = (startX + dx).toInt(); lp.y = (startY + dy).toInt()
                        runCatching { wm?.updateViewLayout(o, lp) }
                        if (longPressed) highlightTrash(isOverTrash(e.rawY))
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    when {
                        longPressed && isOverTrash(e.rawY) -> {
                            Prefs.setFloating(this, false)
                            hideOverlay()
                            ShotState.notifyChanged()
                        }
                        longPressed -> { hideTrash(); snapToEdge(lp, o) }
                        moved -> snapToEdge(lp, o)
                        e.action == MotionEvent.ACTION_UP -> openApp()
                    }
                }
            }
            true
        }
    }

    private fun snapToEdge(lp: WindowManager.LayoutParams, o: View) {
        val sw = resources.displayMetrics.widthPixels
        val target = if (lp.x + o.width / 2 < sw / 2) dp(8) else sw - o.width - dp(8)
        ValueAnimator.ofInt(lp.x, target).apply {
            duration = 180
            addUpdateListener {
                lp.x = it.animatedValue as Int
                if (overlay === o) runCatching { wm?.updateViewLayout(o, lp) }
            }
            start()
        }
    }

    private fun openApp() {
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(it)
        }
    }

    // ---- سطل حذف ----
    private fun isOverTrash(rawY: Float) = rawY > resources.displayMetrics.heightPixels - dp(170)

    private fun showTrash() {
        if (trashView != null) return
        trashView = TextView(this).apply {
            text = "✕"; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); textSize = 22f
            background = pill(Color.parseColor("#99000000"))
        }
        val p = WindowManager.LayoutParams(
            dp(64), dp(64),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(60) }
        wm?.addView(trashView, p)
    }

    private fun highlightTrash(on: Boolean) {
        trashView?.background = pill(Color.parseColor(if (on) "#D93025" else "#99000000"))
        trashView?.animate()?.scaleX(if (on) 1.25f else 1f)?.scaleY(if (on) 1.25f else 1f)?.setDuration(120)?.start()
    }

    private fun hideTrash() {
        trashView?.let { runCatching { wm?.removeView(it) } }
        trashView = null
    }

    // ================= انتخاب ناحیه =================

    fun showCropSelector() {
        if (selector != null || isRunning || ShotState.processing) return
        val manager = wm ?: (getSystemService(WINDOW_SERVICE) as WindowManager).also { wm = it }

        val frame = FrameLayout(this)
        val cropView = CropSelectorView(this)
        Prefs.crop(this)?.let { cropView.initial = it }
        frame.addView(cropView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val hint = TextView(this).apply {
            text = "ناحیه را بکش یا از گوشه‌ها و لبه‌ها تغییر اندازه بده"
            setTextColor(Color.WHITE); textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = pill(Color.parseColor("#CC202124"), 20)
        }
        panel.addView(hint)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = pill(Color.parseColor("#F2202124"), 28)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        fun action(label: String, color: String, onClick: () -> Unit) = TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = pill(Color.parseColor(color), 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4); marginEnd = dp(4) }
            setOnClickListener { onClick() }
        }

        bar.addView(action("تأیید", "#1E8E3E") {
            val f = cropView.fractions()
            Prefs.setCrop(this, f[0], f[1], f[2], f[3])
            Prefs.setCropEnabled(this, true)
            closeCropSelector()
        })
        bar.addView(action("کل صفحه", "#1A73E8") {
            Prefs.setCropEnabled(this, false)
            closeCropSelector()
        })
        bar.addView(action("انصراف", "#5F6368") { closeCropSelector() })
        panel.addView(bar)

        frame.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(60) })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        selector = frame
        setOverlayVisible(false)
        manager.addView(frame, lp)
    }

    private fun closeCropSelector() {
        selector?.let { runCatching { wm?.removeView(it) } }
        selector = null
        setOverlayVisible(true)
        updateOverlayState()
        ShotState.notifyChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopCapture()
    }

    override fun onDestroy() {
        instance = null
        stopCapture()
        closeCropSelector()
        hideOverlay()
        io.shutdown()
        super.onDestroy()
    }
}
