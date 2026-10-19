package com.example.fidiboshot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
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
import kotlin.math.max
import kotlin.math.roundToInt

class FidiboScreenshotService : AccessibilityService() {

    companion object {
        var instance: FidiboScreenshotService? = null
        private const val TAG = "FidiboShot"

        /** مدت ثابت هر صفحه (پنهان شدن + اسکرین + ورق زدن) به میلیثانیه */
        private const val PAGE_OVERHEAD_MS = 750L
        private const val DELAY_MIN = 500L
        private const val DELAY_MAX = 30000L
        private const val DELAY_STEP = 500L

        // ---- اسکرین طولی ----
        private const val LS_MAX_FRAMES = 40          // سقف تعداد اسکرول
        private const val LS_MAX_SPAN = 65000         // سقف ارتفاع بوم
        private const val LS_MIN_DISK_MB = 200L       // حداقل فضای خالی دیسک
        private const val LS_PROBE_MS = 420L          // فاصلهی پروبهای ثبات
        private const val LS_SETTLE_TIMEOUT = 3600L   // حداکثر انتظار برای ثبات صفحه
        private const val LS_SWIPE_MS = 650L          // مدت حرکت سوایپ
        private const val LS_WARN_PX = 16000          // آستانهی هشدار سازگاری گالری

        private val BLUE = Color.parseColor("#1A73E8")
        private val GREEN = Color.parseColor("#1E8E3E")
        private val RED = Color.parseColor("#D93025")
        private val DARK = Color.parseColor("#2B2D31")
        private val GRAY = Color.parseColor("#9AA0A6")
        private val PANEL_BG = Color.parseColor("#F2202124")
        private val CHIP_BG = Color.parseColor("#3C4043")
    }

    // ---- وضعیت ضبط ----
    private var isRunning = false
    private var processing = false      // در حال ساخت PDF
    private var pdfMode = false
    private var currentPage = 1
    private var startPage = 1
    private var endPage = 1
    private var resumePage: Int? = null // اگر وسط کار متوقف شد (حالت عکس)، از همین صفحه ادامه بده
    private var pending = 0             // تعداد اسکرینشاتهای در انتظار پاسخ
    private var etaAt = 0L              // زمان تخمینی پایان (elapsedRealtime)
    private var doneFlash = false

    private val handler = Handler(Looper.getMainLooper())     // چرخهی ضبط
    private val uiHandler = Handler(Looper.getMainLooper())   // تایمر و UI
    private val io = Executors.newSingleThreadExecutor()
    private val queued = java.util.concurrent.atomic.AtomicInteger(0)  // صفحههای در صف پردازش

    // ---- میانبر شناور ----
    private var wm: WindowManager? = null
    private var overlay: LinearLayout? = null
    private var overlayLp: WindowManager.LayoutParams? = null
    private var trashView: TextView? = null
    private var fBtn: RgbButton? = null
    private var chipRtl: RgbButton? = null
    private var chipLtr: RgbButton? = null
    private var cropBtn: RgbButton? = null
    private var longBtn: RgbButton? = null
    private var toggleBtn: RgbButton? = null
    private val rgbViews = mutableListOf<RgbButton>()
    private var rgbAnim: ValueAnimator? = null
    private var selector: FrameLayout? = null

    // ---- پنل کشویی ----
    private var panelRoot: FrameLayout? = null
    private var mainPage: LinearLayout? = null
    private var keyPage: LinearLayout? = null
    private var countValue: TextView? = null
    private var delayValue: TextView? = null
    private var fmtImg: TextView? = null
    private var fmtPdf: TextView? = null
    private var keyDisplay: TextView? = null
    private var keyBuf = ""
    private var stepRunnable: Runnable? = null

    // ---- اسکرین طولی ----
    private val ls = LongShotEngine()
    private var lsState = 0            // 0=خاموش 1=خط ابتدا 2=اسکرول آزاد 3=خط پایان 4=ضبط 5=مونتاژ 6=ناحیه
    @Volatile private var lsSession = 0
    private var markerRoot: FrameLayout? = null
    private var markerLine1: MarkerLineView? = null
    private var markerLine2: MarkerLineView? = null
    private var markerInfo: TextView? = null
    private var markerBar: LinearLayout? = null
    private var endChip: TextView? = null
    private var lsEndPill: LinearLayout? = null     // دکمههای فاز ۲ (پنجرهی جدا تا اسکرول آزاد بماند)
    private var lsNoMove = 0
    private var lsAreaRoot: FrameLayout? = null    // انتخاب ناحیهی محتوا (قبل از خطگذاری)
    private var lsSpanHint = 0
    private var lsSpanReliable = false
    private var markerRect: Rect? = null
    private var lsScreenW = 0
    private var lsScreenH = 0
    private var lsWinOffX = 0
    private var lsWinOffY = 0

    // ---- خط ابتدا: چسبیده به محتوا (با اسکرول بالا می‌رود) ----
    private var lsLineRow = Int.MIN_VALUE        // ردیف خط ابتدا نسبت به بالای ناحیه؛ MIN = روی صفحه دیده نمی‌شود
    private var lsEvtOk = true                   // آیا رویدادهای اسکرول این برنامه با حرکت واقعی می‌خوانند؟
    private var lsEvtMoved = false               // از آخرین بررسی، خط با رویداد جابه‌جا شده؟
    private var lsEvtSeq = 0
    private var lsMoving = false                 // حین اسکرول (وقتی جای خط نامطمئن است) پنهان می‌ماند
    private var lsCheckBusy = false
    private var lsSettleRunnable: Runnable? = null
    private var lsPollRunnable: Runnable? = null
    private var lsSilentPx: IntArray? = null     // فقط ترد io
    private var lsLineY1 = 0f
    private var lsLineY2 = 0f
    private var stopPill: TextView? = null
    private var lsPillLive = false
    private var lsScrollAction = true
    private var lsFailStreak = 0
    private var lsStopPending = false
    private var lsBlackStreak = 0
    private var lsStablePrev: IntArray? = null      // فقط روی ترد io
    private var lsScrollDelta = 0
    private var lsFinishMsg: String? = null
    private var lsProgressLabel: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            updateFButton()
            if (isRunning) uiHandler.postDelayed(this, 1000)
        }
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || currentPage > endPage) {
                finishCapture()
                return
            }
            // اگر پردازش تصاویر عقب افتاده، کمی صبر میکنیم تا حافظه پر نشود
            if (queued.get() > 3) {
                handler.postDelayed(this, 300)
                return
            }
            val page = currentPage
            etaAt = SystemClock.elapsedRealtime() + estimateMs(endPage - page + 1)
            // میانبر را موقتاً پنهان میکنیم تا داخل اسکرینشات نیفتد
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
                        handler.postDelayed(this, Prefs.delay(this@FidiboScreenshotService))
                    }
                }, 600)
            }, 150)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (Prefs.floating(this)) showOverlay()
        Log.d(TAG, "Service connected")
    }

    // ================= کنترل ضبط =================

    private fun estimateMs(pagesLeft: Int): Long {
        val n = pagesLeft.coerceAtLeast(0)
        return n * PAGE_OVERHEAD_MS + (n - 1).coerceAtLeast(0) * Prefs.delay(this)
    }

    private fun startCapture(start: Int, end: Int) {
        if (isRunning || processing) return
        closePanel()
        pdfMode = Prefs.pdf(this)
        resumePage = null
        if (pdfMode) resetTempDir()
        startPage = start
        currentPage = start
        endPage = end
        isRunning = true
        etaAt = SystemClock.elapsedRealtime() + estimateMs(end - start + 1)
        syncState()
        uiHandler.removeCallbacks(ticker)
        uiHandler.post(ticker)
        handler.post(captureRunnable)
        Log.d(TAG, "Started: $start → $end | PDF=$pdfMode")
    }

    fun stopCapture() = finishCapture()

    private fun finishCapture() {
        if (lsState != 0) {
            if (lsState == 4) {
                lsAbortRecording("ضبط طولی متوقف شد")
            } else if (lsState == 5) {
                lsSession++
                lsState = 0
                processing = false
                lsCancelUi()
            } else {
                lsCancelMarker()
            }
        }
        val wasRunning = isRunning
        val completed = wasRunning && currentPage > endPage
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        uiHandler.removeCallbacks(ticker)

        if (wasRunning) {
            resumePage = if (completed || pdfMode) null else currentPage
            val done = (currentPage - startPage).coerceIn(0, endPage - startPage + 1)
            if (pdfMode) {
                processing = true
                buildPdfWhenReady(0)
            } else {
                if (done > 0) {
                    Toast.makeText(this, "$done صفحه در Pictures/FidiboPages ذخیره شد", Toast.LENGTH_LONG).show()
                }
                if (completed) flashDone()
            }
        }
        setOverlayVisible(true)
        syncState()
        Log.d(TAG, "Stopped")
    }

    private fun startFromOverlay() {
        if (lsState != 0) {
            Toast.makeText(this, "در حال اسکرین طولی هستی", Toast.LENGTH_SHORT).show()
            return
        }
        if (processing) {
            Toast.makeText(this, "در حال ساخت PDF، کمی صبر کن", Toast.LENGTH_SHORT).show()
            return
        }
        val total = Prefs.count(this)
        val s = resumePage?.takeIf { it <= total } ?: 1
        startCapture(s, total)
    }

    private fun syncState() {
        updateOverlayState()
        updatePanelState()
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

    // ================= اسکرینشات، برش و ذخیره =================

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
                            ?.copy(Bitmap.Config.ARGB_8888, true)
                        buffer.close()
                        if (bitmap != null) {
                            queued.incrementAndGet()
                            io.execute {
                                try { processAndSave(bitmap, page) } finally { queued.decrementAndGet() }
                            }
                        }
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

    /** روی ترد پسزمینه اجرا میشود */
    private fun processAndSave(source: Bitmap, page: Int) {
        var bmp = cropIfNeeded(source)
        val logicalW = bmp.width     // اندازهی منطقی صفحه (قبل از بزرگکردن)
        val logicalH = bmp.height
        try {
            bmp = ImageEnhancer.enhance(bmp)   // افزایش کیفیت همیشه روشن است
            if (pdfMode) saveTemp(bmp, page, logicalW, logicalH) else saveBitmap(bmp, page)
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

    private fun saveTemp(bitmap: Bitmap, page: Int, logicalW: Int, logicalH: Int) {
        val dir = tempDir().apply { mkdirs() }
        // اندازهی منطقی صفحه در نام فایل ذخیره میشود تا PDF با همان اندازه ساخته شود
        val f = File(dir, "page_${String.format("%05d", page)}_${logicalW}x${logicalH}.jpg")
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    /** صبر میکند تا همهی اسکرینشاتهای در راه تمام شوند، بعد PDF میسازد */
    private fun buildPdfWhenReady(tries: Int) {
        if (pending > 0 && tries < 25) {
            uiHandler.postDelayed({ buildPdfWhenReady(tries + 1) }, 200)
            return
        }
        io.execute { buildPdf() }
    }

    private class PdfPage(val file: File, val pw: Int, val ph: Int, val lw: Int, val lh: Int)

    private class CountingOut(val out: OutputStream) {
        var pos = 0L
        fun w(s: String) { w(s.toByteArray(Charsets.ISO_8859_1)) }
        fun w(b: ByteArray, len: Int = b.size) { out.write(b, 0, len); pos += len }
    }

    private fun buildPdf() {
        val dir = tempDir()
        try {
            val re = Regex("_(\\d+)x(\\d+)\\.jpg$")
            val pages = dir.listFiles { f -> f.name.endsWith(".jpg") }
                ?.sortedBy { it.name }.orEmpty()
                .mapNotNull { f ->
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.absolutePath, o)
                    if (o.outWidth <= 0 || o.outHeight <= 0) return@mapNotNull null
                    val m = re.find(f.name)
                    val lw = m?.groupValues?.get(1)?.toIntOrNull() ?: o.outWidth
                    val lh = m?.groupValues?.get(2)?.toIntOrNull() ?: o.outHeight
                    PdfPage(f, o.outWidth, o.outHeight, lw, lh)
                }

            if (pages.isEmpty()) {
                reportPdf("صفحهای برای ساخت PDF وجود نداشت", false)
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                reportPdf("ذخیرهی PDF فقط روی اندروید ۱۰ به بالا پشتیبانی میشود", false)
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
                reportPdf("ساخت فایل PDF ناموفق بود", false)
                return
            }
            contentResolver.openOutputStream(uri)?.use { os ->
                writePdf(os.buffered(), pages)
            }
            cv.clear()
            cv.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, cv, null, null)

            reportPdf("PDF با ${pages.size} صفحه در Download/FidiboPages ذخیره شد", true)
        } catch (e: Exception) {
            Log.e(TAG, "PDF failed", e)
            reportPdf("خطا در ساخت PDF", false)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** PDF را دستی مینویسد؛ هر JPEG بدون دوبارهفشردهسازی و بدون نگهداشتن در حافظه داخل صفحه قرار میگیرد */
    private fun writePdf(os: OutputStream, pages: List<PdfPage>) {
        val out = CountingOut(os)
        val n = pages.size
        val total = 2 + 3 * n
        val offsets = LongArray(total + 1)

        out.w("%PDF-1.4\n")
        out.w(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))

        offsets[1] = out.pos
        out.w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

        val kids = (0 until n).joinToString(" ") { "${3 + 3 * it} 0 R" }
        offsets[2] = out.pos
        out.w("2 0 obj\n<< /Type /Pages /Kids [$kids] /Count $n >>\nendobj\n")

        val buf = ByteArray(65536)
        pages.forEachIndexed { i, pg ->
            val pageObj = 3 + 3 * i
            val contObj = pageObj + 1
            val imgObj = pageObj + 2

            offsets[pageObj] = out.pos
            out.w(
                "$pageObj 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${pg.lw} ${pg.lh}] " +
                    "/Resources << /XObject << /Im0 $imgObj 0 R >> >> /Contents $contObj 0 R >>\nendobj\n"
            )

            val content = "q ${pg.lw} 0 0 ${pg.lh} 0 0 cm /Im0 Do Q"
            offsets[contObj] = out.pos
            out.w("$contObj 0 obj\n<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")

            offsets[imgObj] = out.pos
            out.w(
                "$imgObj 0 obj\n<< /Type /XObject /Subtype /Image /Width ${pg.pw} /Height ${pg.ph} " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${pg.file.length()} >>\nstream\n"
            )
            pg.file.inputStream().use { ins ->
                while (true) {
                    val r = ins.read(buf)
                    if (r < 0) break
                    out.w(buf, r)
                }
            }
            out.w("\nendstream\nendobj\n")
        }

        val xrefPos = out.pos
        val sb = StringBuilder("xref\n0 ${total + 1}\n0000000000 65535 f \n")
        for (k in 1..total) sb.append(String.format(Locale.US, "%010d 00000 n \n", offsets[k]))
        sb.append("trailer\n<< /Size ${total + 1} /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
        out.w(sb.toString())
        out.out.flush()
    }

    private fun reportPdf(text: String, ok: Boolean) {
        uiHandler.post {
            processing = false
            syncState()
            if (ok) flashDone() else updateFButton()
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
    }

    // ================= میانبر شناور =================
    // با TYPE_ACCESSIBILITY_OVERLAY ساخته میشود؛ نیازی به مجوز «نمایش روی سایر برنامهها» نیست.

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(color: Int, radiusDp: Int = 100) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    private fun rgbButton(sizeDp: Int, first: Boolean = false, onClick: () -> Unit) = RgbButton(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
            if (!first) marginStart = dp(6)
        }
        setOnClickListener { onClick() }
        rgbViews.add(this)
    }

    fun showOverlay() {
        if (overlay != null) return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2202124"))
                cornerRadius = dp(32).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            elevation = dp(8).toFloat()
        }

        fBtn = rgbButton(48, first = true) { togglePanel() }
        chipRtl = rgbButton(40) { Prefs.setRtl(this, true); updateOverlayState() }
        chipLtr = rgbButton(40) { Prefs.setRtl(this, false); updateOverlayState() }
        cropBtn = rgbButton(40) { showCropSelector() }.also { it.cropIcon = true }
        longBtn = rgbButton(40) { lsEnterMarkerMode() }.also { it.linesIcon = true }
        toggleBtn = rgbButton(40) { if (isRunning) stopCapture() else startFromOverlay() }

        chipRtl?.label = "→"
        chipLtr?.label = "←"
        for (b in listOf(fBtn, chipRtl, chipLtr, cropBtn, longBtn, toggleBtn)) root.addView(b)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.LEFT; x = dp(8); y = dp(220) }

        overlay = root
        overlayLp = lp
        attachDrag(fBtn!!)
        manager.addView(root, lp)
        updateOverlayState()
        startRgb()
    }

    fun hideOverlay() {
        closePanel()
        hideTrash()
        stopRgb()
        overlay?.let { runCatching { wm?.removeView(it) } }
        overlay = null
        overlayLp = null
        fBtn = null; chipRtl = null; chipLtr = null; cropBtn = null; longBtn = null; toggleBtn = null
        rgbViews.clear()
    }

    /** خروج: ضبط متوقف و میانبر برداشته میشود. با زدن آیکون برنامه دوباره باز میشود */
    private fun exitShortcut() {
        closePanel()
        stopCapture()
        Prefs.setFloating(this, false)
        hideOverlay()
        Toast.makeText(this, "میانبر بسته شد. برای باز شدن دوباره، آیکون برنامه را بزن", Toast.LENGTH_LONG).show()
    }

    // ---- انیمیشن RGB ----
    private fun startRgb() {
        if (rgbAnim != null || overlay == null) return
        rgbAnim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 3500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val v = a.animatedValue as Float
                for (b in rgbViews) { b.angle = v; b.invalidate() }
            }
            start()
        }
    }

    private fun stopRgb() {
        rgbAnim?.cancel()
        rgbAnim = null
    }

    // ---- وضعیت ظاهری ----
    private fun fmtTime(ms: Long): String {
        val sec = (ms.coerceAtLeast(0) + 999) / 1000
        return String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)
    }

    private fun flashDone() {
        doneFlash = true
        updateFButton()
        uiHandler.postDelayed({ doneFlash = false; updateFButton() }, 3500)
    }

    private fun updateFButton() {
        fBtn?.apply {
            when {
                lsState == 5 -> { label = lsProgressLabel ?: "…"; textSizeSp = 13f; fillColor = BLUE }
                processing -> { label = "…"; textSizeSp = 20f; fillColor = BLUE }
                isRunning -> {
                    label = fmtTime(etaAt - SystemClock.elapsedRealtime())
                    textSizeSp = 11f; fillColor = BLUE
                }
                doneFlash -> { label = "✓"; textSizeSp = 22f; fillColor = GREEN }
                else -> { label = "F"; textSizeSp = 18f; fillColor = BLUE }
            }
        }
    }

    private fun updateOverlayState() {
        val rtl = Prefs.rtl(this)
        chipRtl?.apply { fillColor = if (rtl) BLUE else DARK; textColor = if (rtl) Color.WHITE else GRAY; textSizeSp = 17f }
        chipLtr?.apply { fillColor = if (!rtl) BLUE else DARK; textColor = if (!rtl) Color.WHITE else GRAY; textSizeSp = 17f }

        val cropOn = Prefs.cropEnabled(this) && Prefs.crop(this) != null
        cropBtn?.apply { fillColor = DARK; iconColor = if (cropOn) Color.parseColor("#6EA8FF") else Color.WHITE }

        longBtn?.apply { fillColor = DARK; iconColor = Color.WHITE }

        toggleBtn?.apply {
            label = if (processing) "…" else if (isRunning) "■" else "▶"
            fillColor = if (processing) Color.parseColor("#5F6368") else if (isRunning) RED else GREEN
            textSizeSp = 16f
        }
        updateFButton()
    }

    private fun setOverlayVisible(visible: Boolean) {
        val o = overlay ?: return
        val lp = overlayLp ?: return
        o.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        lp.flags = if (visible) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm?.updateViewLayout(o, lp) }
        if (visible) startRgb() else stopRgb()
    }

    // ---- کشیدن و نگهداشتن ----
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
            val sw = resources.displayMetrics.widthPixels
            val sh = resources.displayMetrics.heightPixels
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    moved = false; longPressed = false
                    uiHandler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        if (!longPressed) uiHandler.removeCallbacks(longPress)
                    }
                    if (moved) {
                        lp.x = (startX + dx).toInt().coerceIn(0, (sw - o.width).coerceAtLeast(0))
                        lp.y = (startY + dy).toInt().coerceIn(dp(64), (sh - dp(140)).coerceAtLeast(dp(64)))
                        runCatching { wm?.updateViewLayout(o, lp) }
                        if (longPressed) highlightTrash(isOverTrash(e.rawY))
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    uiHandler.removeCallbacks(longPress)
                    when {
                        longPressed && isOverTrash(e.rawY) -> exitShortcut()
                        longPressed -> { hideTrash(); snapToEdge(lp, o) }
                        moved -> snapToEdge(lp, o)
                        e.action == MotionEvent.ACTION_UP -> togglePanel()
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

    // ================= پنل کشویی =================

    private fun togglePanel() {
        if (panelRoot != null) closePanel() else openPanel()
    }

    private fun locked() = isRunning || processing

    private fun guardIdle(): Boolean {
        if (locked()) {
            Toast.makeText(this, "این گزینه حین ضبط قابل تغییر نیست", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(GRAY)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun matchWrap(weight: Float = 0f, top: Int = 0) =
        LinearLayout.LayoutParams(
            if (weight > 0f) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, weight
        ).apply { topMargin = top }

    @SuppressLint("ClickableViewAccessibility")
    private fun stepButton(text: String, delta: Long) = TextView(this).apply {
        this.text = text; gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        background = pill(CHIP_BG, 14)
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(42))
        setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    changeDelay(delta)
                    val r = object : Runnable {
                        override fun run() { changeDelay(delta); uiHandler.postDelayed(this, 110) }
                    }
                    stepRunnable = r
                    uiHandler.postDelayed(r, 450)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stepRunnable?.let { uiHandler.removeCallbacks(it) }
                    stepRunnable = null
                }
            }
            true
        }
    }

    private fun changeDelay(delta: Long) {
        val d = (Prefs.delay(this) + delta).coerceIn(DELAY_MIN, DELAY_MAX)
        Prefs.setDelay(this, d)
        delayValue?.text = fmtDelay(d)
    }

    private fun fmtDelay(ms: Long) = String.format(Locale.US, "%.1f", ms / 1000.0) + " ثانیه"

    private fun segment(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; gravity = Gravity.CENTER; textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(3); marginEnd = dp(3) }
        setOnClickListener { onClick() }
    }

    private fun setFormat(pdf: Boolean) {
        if (!guardIdle()) return
        Prefs.setPdf(this, pdf)
        updatePanelState()
    }

    private fun updatePanelState() {
        if (panelRoot == null) return
        val lock = locked()
        val pdf = Prefs.pdf(this)
        countValue?.text = "${Prefs.count(this)} صفحه"
        delayValue?.text = fmtDelay(Prefs.delay(this))
        fmtImg?.apply {
            background = pill(if (!pdf) BLUE else CHIP_BG, 14)
            setTextColor(if (!pdf) Color.WHITE else Color.parseColor("#BDC1C6"))
            alpha = if (lock) 0.4f else 1f
        }
        fmtPdf?.apply {
            background = pill(if (pdf) BLUE else CHIP_BG, 14)
            setTextColor(if (pdf) Color.WHITE else Color.parseColor("#BDC1C6"))
            alpha = if (lock) 0.4f else 1f
        }
        countValue?.alpha = if (lock) 0.4f else 1f
    }

    private fun showMain() {
        mainPage?.visibility = View.VISIBLE
        keyPage?.visibility = View.GONE
        updatePanelState()
    }

    private fun showKeypad() {
        keyBuf = ""
        updateKeyDisplay()
        mainPage?.visibility = View.GONE
        keyPage?.visibility = View.VISIBLE
    }

    private fun updateKeyDisplay() {
        keyDisplay?.apply {
            text = if (keyBuf.isEmpty()) Prefs.count(this@FidiboScreenshotService).toString() else keyBuf
            setTextColor(if (keyBuf.isEmpty()) GRAY else Color.WHITE)
        }
    }

    private fun onKey(k: String) {
        when (k) {
            "⌫" -> keyBuf = keyBuf.dropLast(1)
            "✓" -> {
                if (keyBuf.isNotEmpty()) {
                    Prefs.setCount(this, keyBuf.toInt().coerceIn(1, 9999))
                }
                keyBuf = ""
                showMain()
                return
            }
            else -> {
                if (keyBuf.length < 4 && !(keyBuf.isEmpty() && k == "0")) keyBuf += k
            }
        }
        updateKeyDisplay()
    }

    private fun buildMainPage(): LinearLayout {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // تعداد صفحات
        val countRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        countRow.addView(label("تعداد صفحات"), matchWrap(weight = 1f))
        countValue = TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = pill(CHIP_BG, 14)
            setOnClickListener { if (guardIdle()) showKeypad() }
        }
        countRow.addView(countValue)
        page.addView(countRow, matchWrap())

        // تأخیر
        val delayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        delayRow.addView(label("تأخیر بین صفحات"), matchWrap(weight = 1f))
        val stepper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        stepper.addView(stepButton("−", -DELAY_STEP))
        delayValue = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            minWidth = dp(78)
        }
        stepper.addView(delayValue)
        stepper.addView(stepButton("+", DELAY_STEP))
        delayRow.addView(stepper)
        page.addView(delayRow, matchWrap(top = dp(14)))

        // فرمت خروجی
        page.addView(label("فرمت خروجی"), matchWrap(top = dp(14)))
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        fmtImg = segment("عکس (PNG)") { setFormat(false) }
        fmtPdf = segment("فایل PDF") { setFormat(true) }
        seg.addView(fmtImg); seg.addView(fmtPdf)
        page.addView(seg, matchWrap(top = dp(8)))

        page.addView(TextView(this).apply {
            text = "عکسها: Pictures/FidiboPages  •  PDF: Download/FidiboPages"
            textSize = 10.5f; setTextColor(GRAY)
        }, matchWrap(top = dp(12)))
        return page
    }

    private fun buildKeyPage(): LinearLayout {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(label("تعداد صفحات را وارد کن"), matchWrap())

        keyDisplay = TextView(this).apply {
            textSize = 28f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = pill(CHIP_BG, 16)
        }
        page.addView(keyDisplay, matchWrap(top = dp(8)))

        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")
        for (r in 0 until 4) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            for (c in 0 until 3) {
                val k = keys[r * 3 + c]
                val color = when (k) { "✓" -> GREEN; "⌫" -> Color.parseColor("#5F6368"); else -> CHIP_BG }
                row.addView(TextView(this).apply {
                    text = k; gravity = Gravity.CENTER; textSize = 20f; setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                    background = pill(color, 14)
                    layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    }
                    setOnClickListener { onKey(k) }
                })
            }
            page.addView(row, matchWrap(top = if (r == 0) dp(8) else 0))
        }
        return page
    }

    private fun openPanel() {
        if (panelRoot != null || selector != null || markerRoot != null) return
        val manager = wm ?: return
        val o = overlay ?: return
        val lp = overlayLp ?: return
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val panelW = minOf(sw - dp(24), dp(320))
        val gap = dp(10)

        // پسزمینهی شفاف: کلیک بیرون از پنل آن را میبندد
        val root = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setOnClickListener { closePanel() }
        }

        val main = buildMainPage()
        val keyp = buildKeyPage().apply { visibility = View.GONE }
        mainPage = main
        keyPage = keyp

        val content = FrameLayout(this)
        content.addView(main)
        content.addView(keyp)

        // ارتفاع هر دو صفحه یکسان شود تا پنل بالا و پایین نپرد
        val inner = panelW - dp(32)
        val wSpec = View.MeasureSpec.makeMeasureSpec(inner, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        main.measure(wSpec, hSpec)
        keyp.measure(wSpec, hSpec)
        content.minimumHeight = max(main.measuredHeight, keyp.measuredHeight)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(PANEL_BG)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            elevation = dp(8).toFloat()
            isClickable = true
            addView(content)
        }
        card.measure(View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY), hSpec)
        val panelH = card.measuredHeight

        // دکمهی خروج
        val exit = TextView(this).apply {
            text = "✕  خروج"; textSize = 14f; setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = pill(RED, 22)
            elevation = dp(8).toFloat()
            setOnClickListener { exitShortcut() }
        }
        exit.measure(hSpec, hSpec)
        val exitW = exit.measuredWidth
        val exitH = exit.measuredHeight

        // پنل زیر میانبر؛ اگر جا نبود بالای آن
        val oh = o.height
        val spaceBelow = sh - (lp.y + oh) - gap
        val below = panelH <= spaceBelow || spaceBelow >= lp.y - gap
        val cardTop = if (below) lp.y + oh + gap else (lp.y - gap - panelH).coerceAtLeast(dp(4))
        val exitTop = (if (below) lp.y - gap - exitH else lp.y + oh + gap).coerceAtLeast(dp(4))
        val cardLeft = lp.x.coerceIn(dp(12), (sw - panelW - dp(12)).coerceAtLeast(dp(12)))
        val exitLeft = lp.x.coerceIn(dp(8), (sw - exitW - dp(8)).coerceAtLeast(dp(8)))

        root.addView(card, FrameLayout.LayoutParams(
            panelW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT
        ).apply { leftMargin = cardLeft; topMargin = cardTop })
        root.addView(exit, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.LEFT
        ).apply { leftMargin = exitLeft; topMargin = exitTop })

        val wlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }

        panelRoot = root
        manager.addView(root, wlp)
        updatePanelState()
    }

    private fun closePanel() {
        stepRunnable?.let { uiHandler.removeCallbacks(it) }
        stepRunnable = null
        panelRoot?.let { runCatching { wm?.removeView(it) } }
        panelRoot = null
        mainPage = null; keyPage = null
        countValue = null; delayValue = null
        fmtImg = null; fmtPdf = null; keyDisplay = null
        keyBuf = ""
    }

    // ================= انتخاب ناحیه =================

    fun showCropSelector() {
        if (selector != null || isRunning || processing || lsState != 0) return
        closePanel()
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

        panel.addView(TextView(this).apply {
            text = "ناحیه را بکش یا از گوشهها و لبهها تغییر اندازه بده"
            setTextColor(Color.WHITE); textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = pill(Color.parseColor("#CC202124"), 20)
        })

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

        fun action(text: String, color: String, onClick: () -> Unit) = TextView(this).apply {
            this.text = text; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
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
    }

    // ================= اسکرین طولی =================

    private fun lsTempDir() = File(cacheDir, "fidibo_long")

    /** ابعاد واقعی کل صفحه نمایش (منطبق با بیتمپ اسکرینشات) */
    private fun lsRealScreenSize(): Pair<Int, Int> {
        val manager = wm ?: (getSystemService(WINDOW_SERVICE) as WindowManager).also { wm = it }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = manager.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun lsCancelUi() {
        markerRoot?.let { runCatching { wm?.removeView(it) } }
        markerRoot = null
        markerLine1 = null
        markerLine2 = null
        markerInfo = null
        markerBar = null
        endChip = null
        markerRect = null
        lsAreaRoot?.let { runCatching { wm?.removeView(it) } }
        lsAreaRoot = null
        lsStopTracking()
        lsHideEndPill()
        lsRemoveStopPill()
    }

    private fun lsCancelMarker() {
        if (lsState in 1..3 || lsState == 6) {
            lsSession++
            ls.clearRefs()
            lsCancelUi()
            lsState = 0
            setOverlayVisible(true)
            syncState()
        }
    }

    /**
     * نمایش/لمسپذیری پنجرهی خطگذار؛
     * برای جلوگیری از هرگونه پرش یا تغییر چیدمان (Relayout) توسط WindowManager هنگام مخفی شدن،
     * موقع عکس گرفتن فقط alpha=0f میشود و به layout دست زده نمیشود.
     */
    private fun lsMarkerMode(visible: Boolean, touchable: Boolean) {
        val root = markerRoot ?: return
        val lp = root.layoutParams as? WindowManager.LayoutParams ?: return
        root.alpha = if (visible) 1f else 0f
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val newFlags = if (visible && touchable) base else base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (lp.flags != newFlags) {
            lp.flags = newFlags
            runCatching { wm?.updateViewLayout(root, lp) }
        }
    }

    private fun lsMarkerVisible(v: Boolean) {
        markerRoot?.alpha = if (v) 1f else 0f
    }

    /** پنجرهی کوچک فاز ۲: «＋ خط پایان» و انصراف */
    private fun lsShowEndPill() {
        if (lsEndPill != null) return
        val manager = wm ?: return
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = pill(Color.parseColor("#F2202124"), 28)
            elevation = dp(6).toFloat()
        }
        fun btn(text: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(18), dp(11), dp(18), dp(11))
            background = pill(color, 22)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4); marginEnd = dp(4) }
            setOnClickListener { onClick() }
        }
        bar.addView(btn("＋ خط پایان", BLUE) { lsPhase3Ui() })
        bar.addView(btn("انصراف", Color.parseColor("#5F6368")) { lsCancelMarker() })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(46) }
        lsEndPill = bar
        manager.addView(bar, lp)
    }

    private fun lsHideEndPill() {
        lsEndPill?.let { runCatching { wm?.removeView(it) } }
        lsEndPill = null
    }

    /** ناحیهی کپچر: برش دستی (اگر روشن باشد) وگرنه محدودهی کانتینر اسکرولشونده */
    private fun lsCaptureRect(): Rect {
        val (sw, sh) = lsRealScreenSize()
        val full = Rect(0, 0, sw, sh)
        val f = if (Prefs.cropEnabled(this)) Prefs.crop(this) else null
        if (f != null) {
            val l = (f[0] * sw).toInt().coerceIn(0, sw - 60)
            val t = (f[1] * sh).toInt().coerceIn(0, sh - 260)
            val r = (f[2] * sw).toInt().coerceIn(l + 60, sw)
            val b = (f[3] * sh).toInt().coerceIn(t + 260, sh)
            return Rect(l, t, r, b)
        }
        try {
            val node = lsFindScrollable(sw / 2, (sh * 0.55f).toInt())
            if (node != null) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.intersect(full) && r.width() >= sw * 0.5 && r.height() >= sh * 0.35) return r
            }
        } catch (e: Exception) {
            Log.w(TAG, "scroll container not found", e)
        }
        return full
    }

    /** بزرگترین کانتینر اسکرولشوندهی عمودی که نقطهی دادهشده را میپوشاند */
    private fun lsFindScrollable(cx: Int, cy: Int): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val rect = Rect()
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        var visited = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (visited++ > 3000 || depth > 60) return
            try {
                if (n.isScrollable && n.isVisibleToUser) {
                    n.getBoundsInScreen(rect)
                    if (rect.height() > rect.width() && rect.width() > 200 && rect.contains(cx, cy)) {
                        val area = rect.width() * rect.height()
                        if (area > bestArea) {
                            bestArea = area
                            best = n
                        }
                    }
                }
            } catch (e: Exception) {
                // گره کهنه شده است
            }
            for (i in 0 until n.childCount) {
                try {
                    n.getChild(i)?.let { walk(it, depth + 1) }
                } catch (e: Exception) {
                    // ادامهی پیمایش
                }
            }
        }
        walk(root, 0)
        return best
    }

    private fun lsEnterMarkerMode() {
        if (lsState != 0 || isRunning || processing) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "اسکرین طولی فقط روی اندروید ۱۱ و بالاتر کار میکند", Toast.LENGTH_SHORT).show()
            return
        }
        closePanel()
        lsShowAreaSelector(lsCaptureRect())
    }

    /** مرحلهی ۰: کاربر ناحیهی «فقط محتوا» را تنظیم میکند تا هدر، پیام سنجاقشده و نوار پایین بیرون بمانند */
    private fun lsShowAreaSelector(initial: Rect) {
        val manager = wm ?: (getSystemService(WINDOW_SERVICE) as WindowManager).also { wm = it }
        val (sw, sh) = lsRealScreenSize()
        val bw = sw.toFloat()
        val bh = sh.toFloat()

        val frame = FrameLayout(this)
        val cropView = CropSelectorView(this)
        cropView.initial = floatArrayOf(
            (initial.left / bw).coerceIn(0f, 1f), (initial.top / bh).coerceIn(0f, 1f),
            (initial.right / bw).coerceIn(0f, 1f), (initial.bottom / bh).coerceIn(0f, 1f)
        )
        frame.addView(cropView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        panel.addView(TextView(this).apply {
            text = "ناحیه را فقط روی خودِ محتوا بگذار؛ هدر، پیام سنجاقشده و نوار پایین بیرون بمانند"
            setTextColor(Color.WHITE); textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = pill(Color.parseColor("#CC202124"), 20)
        })
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
        fun action(text: String, color: String, onClick: () -> Unit) = TextView(this).apply {
            this.text = text; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = pill(Color.parseColor(color), 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4); marginEnd = dp(4) }
            setOnClickListener { onClick() }
        }
        bar.addView(action("تأیید و ادامه", "#1E8E3E") {
            val f = cropView.fractions()
            val loc = IntArray(2)
            cropView.getLocationOnScreen(loc)
            val vw = cropView.width
            val vh = cropView.height
            val screenRect = Rect(
                loc[0] + (f[0] * vw).roundToInt(),
                loc[1] + (f[1] * vh).roundToInt(),
                loc[0] + (f[2] * vw).roundToInt(),
                loc[1] + (f[3] * vh).roundToInt()
            )
            lsScreenW = max(sw, loc[0] + vw)
            lsScreenH = max(sh, loc[1] + vh)
            lsAreaConfirm(screenRect)
        })
        bar.addView(action("انصراف", "#5F6368") { lsCancelMarker() })
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
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        lsAreaRoot = frame
        lsState = 6
        lsSession++
        setOverlayVisible(false)
        manager.addView(frame, lp)
    }

    private fun lsAreaConfirm(r: Rect) {
        if (lsState != 6) return
        if (r.height() < OverlapMatcher.REF_BAND_H + 80 || r.width() < 300) {
            Toast.makeText(this, "ناحیهی محتوا خیلی کوچک است؛ بزرگترش کن", Toast.LENGTH_SHORT).show()
            return
        }
        lsAreaRoot?.let { runCatching { wm?.removeView(it) } }
        lsAreaRoot = null
        ls.clearRefs()
        lsSpanHint = 0
        lsSpanReliable = false
        lsLineRow = Int.MIN_VALUE
        lsEvtOk = true
        lsEvtMoved = false
        lsMoving = false
        lsCheckBusy = false
        markerRect = r
        lsLineY1 = lsClampLine(r.top + r.height() * 0.25f)
        lsLineY2 = lsClampLine(r.top + r.height() * 0.75f)
        lsState = 1
        lsSession++
        lsBuildMarkerUi()
        lsPhase1Ui()
    }

    private fun lsBuildMarkerUi() {
        val r = markerRect ?: return
        val manager = wm ?: (getSystemService(WINDOW_SERVICE) as WindowManager).also { wm = it }
        val (sw, sh) = lsRealScreenSize()

        // ریشهی تمامصفحه بدون پسزمینه؛ لمسِ خارج از کنترلها به اپ زیرین میرسد
        val root = FrameLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }

        val borderView = if (r.top > 4 || r.left > 4 || r.bottom < sh - 4 || r.right < sw - 4) {
            View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(2), BLUE, dp(7).toFloat(), dp(5).toFloat())
                }
            }.also { v ->
                root.addView(v, FrameLayout.LayoutParams(r.width(), r.height(), Gravity.TOP or Gravity.LEFT).apply {
                    leftMargin = r.left
                    topMargin = r.top
                })
            }
        } else null

        markerLine1 = MarkerLineView(this).apply {
            label = "ابتدا"
            lineColor = GREEN
            onDrag = { dy ->
                lsLineY1 = lsClampLine(lsLineY1 + dy)
                lsApplyLine(markerLine1, lsLineY1)
            }
        }
        root.addView(markerLine1, FrameLayout.LayoutParams(r.width(), dp(48), Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = r.left - lsWinOffX
            topMargin = (lsLineY1 - lsWinOffY - dp(24)).roundToInt()
        })

        markerLine2 = MarkerLineView(this).apply {
            label = "پایان"
            lineColor = RED
            visibility = View.GONE
            onDrag = { dy ->
                lsLineY2 = lsClampLine(lsLineY2 + dy)
                lsApplyLine(markerLine2, lsLineY2)
            }
        }
        root.addView(markerLine2, FrameLayout.LayoutParams(r.width(), dp(48), Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = r.left - lsWinOffX
            topMargin = (lsLineY2 - lsWinOffY - dp(24)).roundToInt()
        })

        markerInfo = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = pill(Color.parseColor("#E6202124"), 20)
            elevation = dp(6).toFloat()
        }
        root.addView(markerInfo, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(58) })

        markerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = pill(Color.parseColor("#F2202124"), 28)
            elevation = dp(6).toFloat()
        }
        root.addView(markerBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(46) })

        root.addView(TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            background = pill(Color.parseColor("#99000000"), 50)
            setOnClickListener { lsCancelMarker() }
        }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = dp(10)
            topMargin = dp(10)
        })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        markerRoot = root
        manager.addView(root, lp)

        // همتراز کردن دقیق مختصات پنجره با مختصات صفحه نمایش واقعی
        root.post {
            val loc = IntArray(2)
            root.getLocationOnScreen(loc)
            if (loc[0] != lsWinOffX || loc[1] != lsWinOffY) {
                lsWinOffX = loc[0]
                lsWinOffY = loc[1]
                borderView?.let { bv ->
                    (bv.layoutParams as? FrameLayout.LayoutParams)?.apply {
                        leftMargin = r.left - lsWinOffX
                        topMargin = r.top - lsWinOffY
                        bv.layoutParams = this
                    }
                }
                lsApplyLine(markerLine1, lsLineY1)
                lsApplyLine(markerLine2, lsLineY2)
            }
        }
    }

    private fun lsClampLine(y: Float): Float {
        val r = markerRect ?: return y
        val pad = minOf(dp(20).toFloat(), r.height() * 0.08f).coerceAtLeast(8f)
        val lo = r.top + pad
        return y.coerceIn(lo, (r.bottom - pad).coerceAtLeast(lo))
    }

    private fun lsApplyLine(v: MarkerLineView?, y: Float) {
        v ?: return
        val r = markerRect ?: return
        val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.leftMargin = r.left - lsWinOffX
        lp.topMargin = (y - lsWinOffY - dp(24)).roundToInt()
        v.layoutParams = lp
    }

    /** خواندن مختصات واقعی خط روی صفحه نمایش (دقیقاً منطبق با آنچه کاربر میبیند) */
    private fun lsActualLineScreenY(v: MarkerLineView?, fallback: Float): Float {
        if (v != null && v.isAttachedToWindow && v.height > 0) {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            return loc[1] + v.height / 2f
        }
        return fallback
    }

    private fun lsSetBar(vararg actions: Triple<String, String, () -> Unit>) {
        val bar = markerBar ?: return
        bar.removeAllViews()
        for ((text, color, onClick) in actions) {
            bar.addView(TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = pill(Color.parseColor(color), 20)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                }
                setOnClickListener { onClick() }
            })
        }
    }

    private fun lsPhase1Ui() {
        markerLine1?.apply {
            visibility = View.VISIBLE
            alpha = 1f
            onDrag = { dy ->
                lsLineY1 = lsClampLine(lsLineY1 + dy)
                lsApplyLine(markerLine1, lsLineY1)
            }
        }
        markerLine2?.visibility = View.GONE
        endChip?.visibility = View.GONE
        markerBar?.visibility = View.VISIBLE
        markerInfo?.text = "خط ابتدا را روی نقطه شروع بگذار و «ثبت» را بزن"
        lsSetBar(
            Triple("ثبت", "#1E8E3E") { lsConfirmLine1() },
            Triple("انصراف", "#5F6368") { lsCancelMarker() }
        )
    }

    /** فاز ۲ (اسکرول آزاد): خط ابتدا سر جای ثبتشدهاش کاملاً ثابت میماند */
    /** فاز ۲ (اسکرول آزاد): خط ابتدا به «محتوا» چسبیده است و با اسکرول همراه محتوا بالا/پایین می‌رود */
    private fun lsPhase2Ui() {
        lsState = 2
        markerLine1?.apply {
            onDrag = null
        }
        markerLine2?.visibility = View.GONE
        markerBar?.visibility = View.GONE
        markerInfo?.text = "خط ابتدا روی محتوا ثبت شد. اسکرول کن و در انتها «＋ خط پایان» را بزن"
        lsMarkerMode(true, false)   // لمس به اپ زیرین می‌رسد تا بتوانی اسکرول کنی
        lsShowEndPill()
        lsRenderLine()
        lsStartPoll()
    }

    /** فاز ۳: خط ابتدا همانجا ثابت میماند، خط پایان قابل کشیدن است */
    private fun lsPhase3Ui() {
        if (lsState != 2) return
        lsState = 3
        lsStopPoll()
        lsSettleRunnable?.let { uiHandler.removeCallbacks(it) }
        lsSettleRunnable = null
        lsHideEndPill()
        lsMarkerMode(false, false)   // برای یک اسکرین‌شات دقیقِ جای خط ابتدا مخفی می‌شود
        lsStartCheck(false, true)
    }

    /** فاز ۳: جای دقیق خط ابتدا روی محتوا معلوم شد؛ خط پایان قابل کشیدن است */
    private fun lsShowPhase3Ui() {
        if (lsState != 3) return
        lsMarkerMode(true, true)
        markerLine1?.apply { onDrag = null }
        markerLine2?.apply {
            visibility = View.VISIBLE
            onDrag = { dy ->
                lsLineY2 = lsClampLine(lsLineY2 + dy)
                lsApplyLine(markerLine2, lsLineY2)
            }
        }
        markerBar?.visibility = View.VISIBLE
        markerInfo?.text = "خط پایان را روی انتهای محتوا بگذار و «ثبت و شروع» را بزن"
        lsRenderLine()
        lsSetBar(
            Triple("ثبت و شروع", "#1E8E3E") { lsConfirmLine2() },
            Triple("انصراف", "#5F6368") { lsPhase2Ui(); lsRenderLine() }
        )
    }

    // ================= ردیابی خط ابتدا روی محتوا =================

    /** خط را سر جای فعلی‌اش روی محتوا می‌کشد؛ اگر از ناحیه خارج است یا جایش نامطمئن است پنهان می‌ماند */
    private fun lsRenderLine() {
        val r = markerRect ?: return
        val v = markerLine1 ?: return
        val row = lsLineRow
        val show = (lsState == 2 || lsState == 3) && row != Int.MIN_VALUE &&
            row >= 0 && row < r.height() && !lsMoving
        if (show) {
            v.alpha = if (lsState == 3) 0.65f else 1f
            lsApplyLine(v, r.top + row.toFloat())
            v.visibility = View.VISIBLE
        } else {
            v.visibility = View.GONE
        }
    }

    private fun lsStopTracking() {
        lsSettleRunnable?.let { uiHandler.removeCallbacks(it) }
        lsSettleRunnable = null
        lsStopPoll()
        lsCheckBusy = false
        lsSilentPx = null
    }

    private fun lsStartPoll() {
        lsStopPoll()
        val sid = lsSession
        val rn = object : Runnable {
            override fun run() {
                if (sid != lsSession || lsState != 2) return
                if (!lsCheckBusy && lsSettleRunnable == null) lsStartCheck(true, false)
                uiHandler.postDelayed(this, 1100)
            }
        }
        lsPollRunnable = rn
        uiHandler.postDelayed(rn, 1100)
    }

    private fun lsStopPoll() {
        lsPollRunnable?.let { uiHandler.removeCallbacks(it) }
        lsPollRunnable = null
    }

    private fun lsScheduleSettle(delayMs: Long, silent: Boolean) {
        lsSettleRunnable?.let { uiHandler.removeCallbacks(it) }
        val sid = lsSession
        val rn = Runnable {
            lsSettleRunnable = null
            if (sid == lsSession && lsState == 2) lsStartCheck(silent, false)
        }
        lsSettleRunnable = rn
        uiHandler.postDelayed(rn, delayMs)
    }

    /** رویداد اسکرول: خط همراه محتوا حرکت می‌کند (اگر رویدادهای این برنامه دقیق باشند) و بعد از آرام گرفتن با اسکرین‌شات اصلاح می‌شود */
    private fun lsOnScrollEvent(dy: Int) {
        lsEvtSeq++
        if (lsLineRow != Int.MIN_VALUE) {
            if (dy != 0 && abs(dy) < 20000 && lsEvtOk) {
                lsLineRow -= dy
                lsEvtMoved = true
            } else {
                lsMoving = true   // جابه‌جایی نامعلوم: تا بررسی بعدی پنهان
            }
        }
        lsRenderLine()
        lsScheduleSettle(450, false)
    }

    /** برنامه‌هایی که رویداد اسکرول نمی‌دهند: تغییر محتوا (کم‌بسامد) فقط یک بررسی بی‌صدا را راه می‌اندازد */
    private fun lsOnContentChanged() {
        if (lsSettleRunnable == null && !lsCheckBusy) lsScheduleSettle(900, true)
    }

    private fun lsHideForShot(hide: Boolean) {
        markerRoot?.alpha = if (hide) 0f else 1f
        lsEndPill?.visibility = if (hide) View.INVISIBLE else View.VISIBLE
    }

    /**
     * بررسی جای واقعی خط ابتدا.
     * silent=true: اسکرین‌شات بدون پنهان کردن چیزی؛ فقط می‌بیند صفحه عوض شده یا نه (بدون پلک‌زدن).
     * silent=false: میانبرها ~۱۲۰ms پنهان می‌شوند و اثر انگشت روی صفحه جست‌وجو می‌شود.
     */
    private fun lsStartCheck(silentReq: Boolean, toPhase3Req: Boolean) {
        if (lsCheckBusy) return
        if (!(lsState == 2 || lsState == 3) || markerRect == null) return
        // در فاز ۳ همیشه بررسی دقیق (بدون سایه) و بعدش نمایش UI
        val toPhase3 = toPhase3Req || lsState == 3
        val silent = silentReq && !toPhase3
        lsCheckBusy = true
        val sid = lsSession
        val seq = lsEvtSeq
        if (!silent && !toPhase3) lsHideForShot(true)
        handler.postDelayed({
            if (sid != lsSession || !(lsState == 2 || lsState == 3)) {
                lsCheckBusy = false
                return@postDelayed
            }
            lsShotRetry { bmp ->
                if (bmp == null) {
                    lsCheckDone(sid, seq, false, Int.MIN_VALUE, Int.MIN_VALUE, false, false, silent, toPhase3)
                } else {
                    val est = lsLineRow
                    val moved = lsEvtMoved
                    io.execute { lsCheckOnIo(bmp, sid, seq, est, moved, silent, toPhase3) }
                }
            }
        }, if (silent) 0L else 120L)
    }

    /** روی io */
    private fun lsCheckOnIo(bmp: Bitmap, sid: Int, seq: Int, est: Int, moved: Boolean, silent: Boolean, toPhase3: Boolean) {
        val r = markerRect
        val rs = ls.refStart
        var row = Int.MIN_VALUE
        var same = false
        var ok = false
        if (r == null || rs == null) {
            runCatching { bmp.recycle() }
        } else {
            try {
                val px = lsCropPx(bmp, r)
                val w = r.width()
                val h = r.height()
                ok = true
                if (silent) {
                    val last = lsSilentPx
                    lsSilentPx = px
                    same = last != null && last.size == px.size && OverlapMatcher.nearIdentical(last, px, w, h)
                } else {
                    // خط با «کل بافت اطرافش» پیدا می‌شود، نه با یک نوار؛ تا روی عنصر تکراری (مثلاً کارت مشابه در پیام دیگر)
                    // نپرد. اگر مطمئن نبود، خط پنهان می‌ماند.
                    val refPx = ls.refStartFramePx
                    if (refPx != null && refPx.size == px.size) {
                        val hintShift = if (est != Int.MIN_VALUE) est - rs.lineRow else Int.MIN_VALUE
                        val shift = OverlapMatcher.findFrameShift(refPx, px, w, h, rs.lineRow, hintShift)
                        if (shift != Int.MIN_VALUE) row = rs.lineRow + shift
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "line check failed", e)
            }
        }
        uiHandler.post { lsCheckDone(sid, seq, ok, row, est, moved, same, silent, toPhase3) }
    }

    private fun lsCheckDone(
        sid: Int, seq: Int, ok: Boolean, row: Int, est: Int,
        moved: Boolean, same: Boolean, silent: Boolean, toPhase3: Boolean
    ) {
        lsCheckBusy = false
        if (sid != lsSession) return

        if (silent) {
            // صفحه عوض نشده: کاری لازم نیست. عوض شده: بررسی دقیقِ بدون سایه
            if (ok && !same) lsStartCheck(false, false)
            return
        }

        if (!toPhase3 && lsState == 2) lsHideForShot(false)
        val hh = markerRect?.height() ?: 0
        if (ok) {
            if (seq != lsEvtSeq) {
                // در همین بین دوباره اسکرول شده؛ نتیجه کهنه است
                lsScheduleSettle(250, false)
            } else {
                if (row != Int.MIN_VALUE) {
                    if (est != Int.MIN_VALUE && moved && abs(row - est) > 6) lsEvtOk = false   // رویدادها نادقیق بودند
                    lsLineRow = row
                } else {
                    lsLineRow = Int.MIN_VALUE
                }
                lsEvtMoved = false
                lsMoving = false
            }
        } else {
            lsMoving = false
        }
        lsRenderLine()

        if (lsState == 3) {
            if (toPhase3 && (!ok || seq == lsEvtSeq)) lsShowPhase3Ui()
            else lsStartCheck(false, true)
        }
    }

    // ---- ثبت خطوط و عکس مرجع (اثر انگشت) ----

    private fun lsConfirmLine1() {
        val r = markerRect ?: return
        lsLineY1 = lsActualLineScreenY(markerLine1, lsLineY1)
        val lineRow = (lsLineY1 - r.top).roundToInt().coerceIn(0, r.height() - 1)
        val bandRow = (lineRow - OverlapMatcher.REF_BAND_H / 2).coerceIn(0, r.height() - OverlapMatcher.REF_BAND_H)
        lsMarkerVisible(false)
        handler.postDelayed({
            if (lsState != 1 || markerRoot == null) return@postDelayed
            lsShotRetry { bmp ->
                if (bmp == null) {
                    lsMarkerVisible(true)
                    Toast.makeText(this, "اسکرینشات گرفته نشد؛ دوباره تلاش کن", Toast.LENGTH_SHORT).show()
                } else io.execute { lsMakeRef(bmp, bandRow, lineRow, first = true) }
            }
        }, 120)
    }

    private fun lsConfirmLine2() {
        val r = markerRect ?: return
        lsLineY2 = lsActualLineScreenY(markerLine2, lsLineY2)
        val lineRow = (lsLineY2 - r.top).roundToInt().coerceIn(0, r.height() - 1)
        lsSpanHint = 0
        lsSpanReliable = false
        val bandRow = (lineRow - OverlapMatcher.REF_BAND_H / 2).coerceIn(0, r.height() - OverlapMatcher.REF_BAND_H)
        lsMarkerVisible(false)
        handler.postDelayed({
            if (lsState != 3 || markerRoot == null) return@postDelayed
            lsShotRetry { bmp ->
                if (bmp == null) {
                    lsMarkerVisible(true)
                    Toast.makeText(this, "اسکرینشات گرفته نشد؛ دوباره تلاش کن", Toast.LENGTH_SHORT).show()
                } else io.execute { lsMakeRef(bmp, bandRow, lineRow, first = false) }
            }
        }, 120)
    }

    /** برش ناحیهی کپچر از اسکرینشات کامل با تطبیق دقیق ابعاد بیتمپ؛ بیتمپ ورودی بازیافت میشود */
    private fun lsCropPx(bmp: Bitmap, r: Rect): IntArray {
        val w = r.width()
        val h = r.height()
        val sw = if (lsScreenW > 0) lsScreenW else bmp.width
        val sh = if (lsScreenH > 0) lsScreenH else bmp.height
        var cropped: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            if (bmp.width == sw && bmp.height == sh) {
                val left = r.left.coerceIn(0, bmp.width - 1)
                val top = r.top.coerceIn(0, bmp.height - 1)
                val cw = w.coerceIn(1, bmp.width - left)
                val ch = h.coerceIn(1, bmp.height - top)
                cropped = Bitmap.createBitmap(bmp, left, top, cw, ch)
                if (cw != w || ch != h) {
                    scaled = Bitmap.createScaledBitmap(cropped, w, h, true)
                }
            } else {
                val sx = bmp.width.toFloat() / sw
                val sy = bmp.height.toFloat() / sh
                val left = (r.left * sx).roundToInt().coerceIn(0, bmp.width - 1)
                val top = (r.top * sy).roundToInt().coerceIn(0, bmp.height - 1)
                val cw = (w * sx).roundToInt().coerceIn(1, bmp.width - left)
                val ch = (h * sy).roundToInt().coerceIn(1, bmp.height - top)
                cropped = Bitmap.createBitmap(bmp, left, top, cw, ch)
                scaled = if (cw == w && ch == h) null else Bitmap.createScaledBitmap(cropped, w, h, true)
            }
            val target = scaled ?: cropped!!
            val px = IntArray(w * h)
            target.getPixels(px, 0, w, 0, 0, w, h)
            return px
        } finally {
            runCatching { scaled?.recycle() }
            runCatching { cropped?.recycle() }
            runCatching { bmp.recycle() }
        }
    }

    /** روی io: عکس مرجع یک خط را میسازد و فاصلهی دقیق دو خط را با تطبیق چندگانه محاسبه میکند */
    private fun lsMakeRef(bmp: Bitmap, bandRow: Int, lineRow: Int, first: Boolean) {
        val r = markerRect
        val sid = lsSession
        if (r == null) {
            runCatching { bmp.recycle() }
            return
        }
        try {
            val px = lsCropPx(bmp, r)
            val detailBand = OverlapMatcher.extractDetailBand(px, r.width(), bandRow, OverlapMatcher.REF_BAND_H)
            val fullBand = OverlapMatcher.extractBand(px, r.width(), bandRow, OverlapMatcher.REF_BAND_H)
            val band = detailBand ?: fullBand

            val blank = band == null || (band.sigma < 1.2f && (fullBand == null || fullBand.sigma < 1.2f))

            var hint = 0
            var reliable = false
            var badOrder = false
            val rs = ls.refStart
            val startPx = ls.refStartFramePx
            if (!first && !blank && rs != null) {
                if (startPx != null && startPx.size == px.size && OverlapMatcher.nearIdentical(startPx, px, r.width(), r.height())) {
                    // ۱) صفحه اصلاً اسکرول نخورده است: فاصلهی دو خط دقیقاً همان فاصلهی روی صفحه است
                    hint = lineRow - rs.lineRow
                    if (hint <= 0) badOrder = true else reliable = true
                } else if (startPx != null && startPx.size == px.size) {
                    // ۲) بررسی همپوشانی واقعی دو قاب با تطبیق چندنواره (جلوگیری از تشخیص اشتباه روی صفحات دیگر)
                    val shift = OverlapMatcher.findFrameShift(startPx, px, r.width(), r.height(), rs.lineRow)
                    if (shift != Int.MIN_VALUE) {
                        val startRowInCur = rs.lineRow + shift
                        hint = lineRow - startRowInCur
                        if (hint <= 0) badOrder = true else reliable = true
                    }
                }
            }
            val ambiguous = first && !blank && OverlapMatcher.isAmbiguous(px, r.width(), r.height(), lineRow)
            uiHandler.post {
                if (sid != lsSession || markerRoot == null) return@post
                if (ambiguous) {
                    Toast.makeText(this, "این قسمت با اطرافش در همین صفحه تکرار شده؛ اگر نتیجه اشتباه بود خط را جای متفاوت‌تری بگذار", Toast.LENGTH_LONG).show()
                }
                if (blank) {
                    lsMarkerVisible(true)
                    Toast.makeText(this, "این قسمت صفحه کاملاً خالی است؛ خط را روی متن یا تصویر بگذار", Toast.LENGTH_SHORT).show()
                } else if (badOrder) {
                    lsMarkerVisible(true)
                    Toast.makeText(this, "خط پایان باید پایینتر از خط ابتدا باشد", Toast.LENGTH_LONG).show()
                } else if (first) {
                    ls.refStart = LongShotEngine.Ref(band!!, fullBand, lineRow, bandRow)
                    ls.refStartFramePx = px
                    lsLineRow = lineRow
                    lsEvtOk = true
                    lsEvtMoved = false
                    lsMoving = false
                    lsSilentPx = null
                    lsMarkerVisible(true)
                    lsPhase2Ui()
                } else {
                    ls.refEnd = LongShotEngine.Ref(band!!, fullBand, lineRow, bandRow)
                    lsSpanHint = if (hint in 1..LS_MAX_SPAN) hint else 0
                    lsSpanReliable = reliable && lsSpanHint > 0
                    lsBeginRecording(px)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ref shot failed", e)
            uiHandler.post {
                if (sid != lsSession || markerRoot == null) return@post
                lsMarkerVisible(true)
                Toast.makeText(this, "خطا در گرفتن عکس مرجع؛ دوباره تلاش کن", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- شروع ضبط ----

    private fun lsBeginRecording(initialFramePx: IntArray) {
        lsStopTracking()
        val r = markerRect ?: return
        if (cacheDir.usableSpace < LS_MIN_DISK_MB * 1024 * 1024) {
            Toast.makeText(this, "فضای ذخیرهسازی کافی نیست (حداقل ۲۰۰ مگابایت لازم است)", Toast.LENGTH_LONG).show()
            lsCancelMarker()
            return
        }
        ls.reset(r.width(), r.height())
        ls.spanHint = lsSpanHint
        ls.spanReliable = lsSpanReliable
        lsTempDir().deleteRecursively()
        lsTempDir().mkdirs()
        lsScrollAction = true
        lsFailStreak = 0
        lsStopPending = false
        lsBlackStreak = 0
        lsStablePrev = null
        lsScrollDelta = 0
        lsFinishMsg = null
        lsNoMove = 0
        lsScrollAction = false
        lsState = 4
        isRunning = true
        // پنجرهی خطگذار کاملاً برداشته میشود (ناحیهی کپچر نگه داشته میشود)
        markerRoot?.let { runCatching { wm?.removeView(it) } }
        markerRoot = null
        markerLine1 = null
        markerLine2 = null
        markerInfo = null
        markerBar = null
        endChip = null
        lsHideEndPill()
        lsAddStopPill()
        syncState()
        // قاب اول همان قابی است که موقع ثبت خط پایان گرفته شد (بدون تأخیر و بدون جابهجایی)
        val sid = lsSession
        io.execute {
            val res = lsEngineProcess(initialFramePx, first = true)
            uiHandler.post { if (sid == lsSession) lsOnFrameResult(res) }
        }
    }

    /**
     * دکمهی توقف همیشهفعال. اگر بالای ناحیهی کپچر جا داشته باشد، شمارندهی
     * زنده هم نشان میدهد (چون بیرون از کپچرهاست)؛ وگرنه فقط آیکون ثابت ■
     * که بهصورت خودکار جزو نوارهای ثابت از خروجی حذف میشود.
     */
    private fun lsAddStopPill() {
        val r = markerRect
        lsPillLive = r != null && r.top > dp(72)
        stopPill = TextView(this).apply {
            text = "■"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = pill(Color.parseColor("#E6D93025"), 22)
            elevation = dp(6).toFloat()
            setOnClickListener {
                if (lsState == 4 && !lsStopPending) {
                    lsStopPending = true
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    alpha = 0.5f
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(10)
        }
        wm?.addView(stopPill, lp)
    }

    /** اگر دکمهی توقف داخل ناحیهی کپچر باشد، موقع اسکرینشات نامرئی میشود تا در تصویر نیفتد */
    private fun lsPillVisible(v: Boolean) {
        val p = stopPill ?: return
        if (lsPillLive) return
        val lp = p.layoutParams as? WindowManager.LayoutParams ?: return
        p.visibility = if (v) View.VISIBLE else View.INVISIBLE
        lp.flags = if (v) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm?.updateViewLayout(p, lp) }
    }

    private fun lsRemoveStopPill() {
        stopPill?.let { runCatching { wm?.removeView(it) } }
        stopPill = null
    }

    private fun lsUpdatePill() {
        if (!lsPillLive || stopPill == null) return
        val span = ls.capturedSpan() / 1000
        stopPill?.text = "■  ${ls.framesSaved} · ${span}k"
    }

    // ---- گرفتن اسکرینشات خام ----

    private fun lsShotThen(cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            cb(null)
            return
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        var bmp: Bitmap? = null
                        try {
                            val buffer = screenshot.hardwareBuffer
                            bmp = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, true)
                            buffer.close()
                        } catch (e: Exception) {
                            Log.e(TAG, "shot wrap error", e)
                        }
                        cb(bmp)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "takeScreenshot failed code=$errorCode")
                        cb(null)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot error", e)
            cb(null)
        }
    }

    private fun lsShotRetry(attempt: Int = 0, cb: (Bitmap?) -> Unit) {
        lsShotThen { bmp ->
            if (bmp == null && attempt < 2 && lsState != 0) {
                handler.postDelayed({ lsShotRetry(attempt + 1, cb) }, 400)
            } else cb(bmp)
        }
    }

    // ---- حلقهی ضبط ----

    /**
     * صبر میکند تا صفحه ثابت شود (دو پروب پشتسرهم تقریباً یکسان، با تحمل چند
     * درصد برای کرسر چشمکزن و انیمیشنهای ریز) بعد قاب را پردازش میکند.
     */
    private fun lsWaitStable(startMs: Long, first: Boolean) {
        if (lsState != 4 || !isRunning) return
        if (lsStopPending) {
            lsFinishRecording("توقف دستی؛ تا همینجا ذخیره شد")
            return
        }
        if (startMs == 0L) {
            lsStablePrev = null
            lsBlackStreak = 0
            handler.postDelayed({
                lsWaitStable(SystemClock.elapsedRealtime(), first)
            }, if (first) 250 else 300)
            return
        }
        lsPillVisible(false)
        handler.postDelayed({
            if (lsState != 4 || !isRunning) return@postDelayed
            lsShotThen { bmp ->
                lsPillVisible(true)
                if (lsState != 4 || !isRunning) {
                    bmp?.recycle()
                    return@lsShotThen
                }
                if (bmp == null) {
                    if (SystemClock.elapsedRealtime() - startMs > LS_SETTLE_TIMEOUT + 2500) {
                        lsAbortRecording("اسکرینشات گرفته نشد (ممکن است اپ اجازهی اسکرینشات ندهد)")
                    } else {
                        handler.postDelayed({ lsWaitStable(startMs, first) }, 450)
                    }
                    return@lsShotThen
                }
                io.execute { lsProbe(bmp, startMs, first) }
            }
        }, 120)
    }

    /** روی io: بررسی سیاهی و ثبات، سپس پردازش قاب */
    private fun lsProbe(bmp: Bitmap, startMs: Long, first: Boolean) {
        val sid = lsSession
        val r = markerRect
        if (r == null || lsState != 4) {
            runCatching { bmp.recycle() }
            return
        }
        val w = r.width()
        val h = r.height()
        val px = try {
            lsCropPx(bmp, r)
        } catch (e: Exception) {
            Log.e(TAG, "crop failed (rotation?)", e)
            uiHandler.post { if (sid == lsSession) lsAbortRecording("صفحه تغییر کرد؛ عملیات متوقف شد") }
            return
        }
        val prevProbe = lsStablePrev
        when {
            OverlapMatcher.allBlack(px, w, h) -> {
                lsBlackStreak++
                if (lsBlackStreak >= 2) {
                    uiHandler.post { if (sid == lsSession) lsAbortRecording("این صفحه اجازهی اسکرینشات ندارد") }
                } else {
                    uiHandler.post {
                        if (sid == lsSession) handler.postDelayed({ lsWaitStable(startMs, first) }, LS_PROBE_MS)
                    }
                }
            }
            prevProbe != null && OverlapMatcher.nearIdentical(px, prevProbe, w, h) -> {
                lsBlackStreak = 0
                lsStablePrev = null
                val res = lsEngineProcess(px, first)
                uiHandler.post { if (sid == lsSession) lsOnFrameResult(res) }
            }
            else -> {
                lsBlackStreak = 0
                lsStablePrev = px
                if (SystemClock.elapsedRealtime() - startMs > LS_SETTLE_TIMEOUT) {
                    lsStablePrev = null
                    val res = lsEngineProcess(px, first)
                    uiHandler.post { if (sid == lsSession) lsOnFrameResult(res) }
                } else {
                    uiHandler.post {
                        if (sid == lsSession) handler.postDelayed({ lsWaitStable(startMs, first) }, LS_PROBE_MS)
                    }
                }
            }
        }
    }

    private fun lsEngineProcess(px: IntArray, first: Boolean): LongShotEngine.R {
        return try {
            if (first) {
                ls.processFirst(px, lsTempDir())
            } else {
                val fallbackGuess = if (lsScrollDelta > 0) lsScrollDelta
                else ((markerRect?.height() ?: 1200) * 0.42f).toInt()
                ls.processNext(px, lsTempDir(), fallbackGuess)
            }
        } catch (e: OutOfMemoryError) {
            LongShotEngine.R.Error("حافظه کافی نبود؛ محدودهی کوچکتری انتخاب کن")
        } catch (e: Exception) {
            Log.e(TAG, "ls frame process failed", e)
            LongShotEngine.R.Error("خطا در پردازش تصویر")
        }
    }

    private fun lsOnFrameResult(res: LongShotEngine.R) {
        if (lsState != 4 || !isRunning) return
        if (lsStopPending) {
            lsFinishRecording("توقف دستی؛ تا همینجا ذخیره شد")
            return
        }
        when (res) {
            LongShotEngine.R.FoundStart -> lsFinishRecording(null)
            LongShotEngine.R.Accepted -> {
                lsFailStreak = 0
                lsNoMove = 0
                lsUpdatePill()
                stopPill?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                lsMaybeNext()
            }
            LongShotEngine.R.NoMove -> {
                // یا به ابتدای محتوا رسیدهایم یا حرکت سوایپ نگرفته؛ یک بار دوباره امتحان میکنیم
                lsNoMove++
                if (lsNoMove >= 2) {
                    lsFinishRecording("به ابتدای محتوا رسیدیم ولی خط ابتدا پیدا نشد؛ خروجی جزئی ذخیره شد")
                } else {
                    handler.postDelayed({ lsScrollUp() }, 250)
                }
            }
            LongShotEngine.R.Discard -> {
                lsFailStreak++
                if (lsFailStreak >= 3) {
                    lsFinishRecording("تطبیق تصویر ناموفق بود؛ خروجی جزئی ذخیره شد")
                } else {
                    // قاب دورریخته شد؛ سوایپ دوباره فاصله را بزرگتر میکند، پس همان صفحه را دوباره میسنجیم
                    handler.postDelayed({ lsWaitStable(0, false) }, 280)
                }
            }
            is LongShotEngine.R.Error -> lsAbortRecording(res.msg)
        }
    }

    private fun lsMaybeNext() {
        if (ls.framesSaved >= LS_MAX_FRAMES) {
            lsFinishRecording("به سقف $LS_MAX_FRAMES اسکرین رسیدی؛ خروجی جزئی ذخیره شد")
            return
        }
        if (ls.capturedSpan() >= LS_MAX_SPAN) {
            lsFinishRecording("به سقف ارتفاع رسیدی؛ خروجی جزئی ذخیره شد")
            return
        }
        handler.postDelayed({ lsScrollUp() }, 150)
    }

    private fun lsScrollUp() {
        if (lsState != 4 || !isRunning) return
        if (lsStopPending) {
            lsFinishRecording("توقف دستی؛ تا همینجا ذخیره شد")
            return
        }
        lsScrollDelta = 0
        lsSwipeUp()
    }

    /**
     * سوایپ رو به پایین (محتوا به سمت ابتدای سند میرود) با مسافت کنترلشدهی حدود ۴۲٪ ارتفاع،
     * و مکث کوتاه قبل از بلند کردن انگشت تا فلینگ ایجاد نشود. مسافت ثابت یعنی همیشه همپوشانی داریم.
     */
    private fun lsSwipeUp() {
        val r = markerRect ?: return
        val cx = (r.left + r.right) / 2f
        val y1 = r.top + r.height() * 0.30f
        val y2 = y1 + r.height() * 0.42f
        val move = Path().apply {
            moveTo(cx, y1)
            lineTo(cx, y2)
        }
        val s1 = GestureDescription.StrokeDescription(move, 0, LS_SWIPE_MS, true)
        dispatchGesture(
            GestureDescription.Builder().addStroke(s1).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (lsState != 4 || !isRunning) return
                    val hold = Path().apply {
                        moveTo(cx, y2)
                        lineTo(cx, y2 + 1f)
                    }
                    val s2 = s1.continueStroke(hold, 0, 200, false)
                    dispatchGesture(
                        GestureDescription.Builder().addStroke(s2).build(),
                        object : GestureResultCallback() {
                            override fun onCompleted(g: GestureDescription?) {
                                handler.postDelayed({ lsWaitStable(0, false) }, 140)
                            }

                            override fun onCancelled(g: GestureDescription?) {
                                handler.postDelayed({ lsWaitStable(0, false) }, 300)
                            }
                        },
                        null
                    )
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    handler.postDelayed({ lsWaitStable(0, false) }, 300)
                }
            },
            null
        )
    }

    // ---- پایان ضبط و مونتاژ ----

    private fun lsFinishRecording(msg: String?) {
        if (lsState != 4) return
        lsFinishMsg = msg
        lsState = 5
        isRunning = false
        processing = true
        handler.removeCallbacksAndMessages(null)
        lsRemoveStopPill()
        setOverlayVisible(true)
        lsProgressLabel = "0%"
        syncState()
        val partial = ls.startLineCanvas == Int.MIN_VALUE
        io.execute { lsAssemble(partial) }
    }

    private fun lsAbortRecording(msg: String?) {
        if (lsState != 4) return
        lsSession++
        lsState = 0
        isRunning = false
        processing = false
        handler.removeCallbacksAndMessages(null)
        lsRemoveStopPill()
        setOverlayVisible(true)
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        runCatching {
            io.execute {
                runCatching { ls.cleanup() }
                runCatching { lsTempDir().deleteRecursively() }
            }
        }
        syncState()
    }

    /** روی io: ساخت PNG نهایی با استریم ردیفی و پیشرفت روی دکمهی F */
    private fun lsAssemble(partial: Boolean) {
        val sid = lsSession
        try {
            if (ls.framesSaved == 0) {
                uiHandler.post { lsAssembleDone(sid, null, "چیزی برای ذخیره ضبط نشد") }
                return
            }
            val name = "long_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
                (if (partial) "_partial" else "") + ".png"
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FidiboPages")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            if (uri == null) {
                uiHandler.post { lsAssembleDone(sid, null, "ساخت فایل خروجی ناموفق بود") }
                return
            }
            var info: LongShotEngine.AsmResult? = null
            try {
            contentResolver.openOutputStream(uri)?.use { os ->
                val buf = java.io.BufferedOutputStream(os, 128 * 1024)
                info = ls.assemble(buf) { p ->
                    val pct = (p * 100).toInt().coerceIn(0, 100)
                    uiHandler.post {
                        if (sid == lsSession) {
                            lsProgressLabel = "$pct%"
                            updateFButton()
                        }
                    }
                }
                buf.flush()
            }
            } catch (e: Exception) {
                runCatching { contentResolver.delete(uri, null, null) }
                throw e
            }
            if (info == null) {
                runCatching { contentResolver.delete(uri, null, null) }
                uiHandler.post { lsAssembleDone(sid, null, "نوشتن فایل خروجی ناموفق بود") }
                return
            }
            cv.clear()
            cv.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, cv, null, null)
            uiHandler.post { lsAssembleDone(sid, info, null) }
        } catch (e: Exception) {
            Log.e(TAG, "longshot assemble failed", e)
            uiHandler.post { lsAssembleDone(sid, null, "خطا در ساخت تصویر طویل") }
        } finally {
            runCatching { ls.cleanup() }
            runCatching { lsTempDir().deleteRecursively() }
        }
    }

    private fun lsAssembleDone(sid: Int, info: LongShotEngine.AsmResult?, err: String?) {
        if (sid != lsSession) return   // در همین بین کار لغو شده است
        processing = false
        lsState = 0
        lsProgressLabel = null
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show()
        } else {
            flashDone()
            val h = info?.height ?: 0
            val msg = lsFinishMsg ?: "تصویر طویل ($h پیکسل، ${ls.framesSaved} قاب) در Pictures/FidiboPages ذخیره شد"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            if (h > LS_WARN_PX) {
                Toast.makeText(
                    this,
                    "ارتفاع تصویر بالای ۱۶۰۰۰ پیکسل است؛ ممکن است در گالری یا پیامرسانها باز نشود",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        lsFinishMsg = null
        syncState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> when (lsState) {
                // حین ضبط: حدس اولیه‌ی مقدار اسکرول (جمع مقدارهای یک سوایپ)
                4 -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val d = abs(event.scrollDeltaY)
                        if (d in 1..20000) lsScrollDelta += d
                    }
                }
                // فاز اسکرول آزاد: خط ابتدا همراه محتوا
                2 -> if (event.packageName?.toString() != packageName) {
                    val dy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) event.scrollDeltaY else 0
                    lsOnScrollEvent(dy)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                if (lsState == 2 && event.packageName?.toString() != packageName) lsOnContentChanged()
        }
    }

    override fun onInterrupt() {
        stopCapture()
    }

    override fun onDestroy() {
        instance = null
        if (lsState != 0) {
            lsSession++
            lsState = 0
            val dir = lsTempDir()
            runCatching { io.execute { runCatching { dir.deleteRecursively() } } }
                .onFailure { runCatching { dir.deleteRecursively() } }
            lsCancelUi()
        }
        stopCapture()
        closeCropSelector()
        hideOverlay()
        uiHandler.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }
}