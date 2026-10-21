package com.example.fidiboshot

import android.content.ContentValues
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ضبط ویدئوی صفحه به‌صورت MP4:
 *   MediaProjection → VirtualDisplay → MediaCodec (H.264)
 *   میکروفون یا صدای داخلی → AudioRecord → MediaCodec (AAC)
 *   هر دو با یک MediaMuxer در Movies/FidiboPages ذخیره می‌شوند.
 *
 * quality: 0 = 480p ، 1 = 720p ، 2 = 1080p ، 3 = اصلی (اندازه‌ی کامل صفحه)
 * audioMode: 0 = خاموش ، 1 = میکروفون ، 2 = صدای داخلی (اندروید ۱۰+)
 */
class ScreenRecorder(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val quality: Int,
    private val fps: Int,
    audioModeIn: Int
) {
    companion object {
        private const val TAG = "FidiboRec"
        private const val AUDIO_RATE = 44100
        private const val START_TIMEOUT_MS = 8000L
        private const val AAC_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val AVC_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    /** وقتی خطای مهلک رخ دهد (روی یک ترد پس‌زمینه) صدا زده می‌شود؛ بعدش باید stop() زده شود */
    @Volatile var onFailure: ((String) -> Unit)? = null

    @Volatile private var failure: String? = null
    @Volatile private var warning: String? = null
    @Volatile private var stopping = false
    @Volatile private var muxerStarted = false

    private var audioMode = audioModeIn
    private var channels = 1

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var display: VirtualDisplay? = null
    private var inputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null

    private val lock = Any()
    private val startLatch = CountDownLatch(1)
    private var videoTrack = -1
    private var audioTrack = -1
    private var baseUs = 0L
    private var startedAt = 0L
    private var lastVideoPts = -1L
    private var lastAudioPts = -1L

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    private val audioOn: Boolean get() = audioCodec != null

    // ================= شروع =================

    fun start() {
        val vc = MediaCodec.createEncoderByType(AVC_MIME)
        videoCodec = vc
        val vcaps = vc.codecInfo.getCapabilitiesForType(AVC_MIME).videoCapabilities

        val (sw, sh) = realSize()
        val shortSide = min(sw, sh)
        val target = when (quality) {
            0 -> 480
            1 -> 720
            2 -> 1080
            else -> shortSide
        }
        val scale = min(1f, target.toFloat() / shortSide)
        val (w, h) = fitSize(vcaps, (sw * scale).roundToInt(), (sh * scale).roundToInt())

        val fpsMax = runCatching { vcaps.getSupportedFrameRatesFor(w, h).upper.toInt() }.getOrDefault(fps)
        val f = min(fps, max(fpsMax, 1))
        val bitrate = (w.toLong() * h * f / 10L)
            .coerceIn(1_000_000L, 40_000_000L)
            .coerceAtMost(vcaps.bitrateRange.upper.toLong())
            .toInt()

        val vf = MediaFormat.createVideoFormat(AVC_MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, f)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // صفحه‌ی ثابت فریم جدید نمی‌دهد؛ فریم قبلی را تکرار کن تا ویدئو یکنواخت بماند
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / f)
        }
        vc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = vc.createInputSurface()
        vc.start()

        muxer = openOutput()
        setupAudio()

        baseUs = System.nanoTime() / 1000L
        startedAt = SystemClock.elapsedRealtime()

        display = projection.createVirtualDisplay(
            "fidibo_rec", w, h, ctx.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )

        videoThread = Thread({ videoLoop() }, "rec-video").also { it.start() }
        if (audioCodec != null) {
            audioThread = Thread({ audioLoop() }, "rec-audio").also { it.start() }
        }
        Log.d(TAG, "started ${w}x$h @$f fps, ${bitrate / 1000} kbps, audio=$audioMode")
    }

    @Suppress("DEPRECATION")
    private fun realSize(): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val m = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(m)
            Pair(m.widthPixels, m.heightPixels)
        }
    }

    /** اندازه‌ای که انکودر قبول کند؛ اگر لازم شد کمی کوچک‌تر می‌شود */
    private fun fitSize(vcaps: MediaCodecInfo.VideoCapabilities, w0: Int, h0: Int): Pair<Int, Int> {
        var w = w0
        var h = h0
        repeat(12) {
            val ew = w and 1.inv()
            val eh = h and 1.inv()
            if (ew > 0 && eh > 0 && vcaps.isSizeSupported(ew, eh)) return Pair(ew, eh)
            val aw = w and 15.inv()
            val ah = h and 15.inv()
            if (aw > 0 && ah > 0 && vcaps.isSizeSupported(aw, ah)) return Pair(aw, ah)
            w = (w * 0.9f).toInt()
            h = (h * 0.9f).toInt()
        }
        throw IllegalStateException("اندازه‌ی ویدئو توسط دستگاه پشتیبانی نمی‌شود")
    }

    private fun openOutput(): MediaMuxer {
        val name = "fidibo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FidiboPages")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = ctx.contentResolver
        val u = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
            ?: throw IOException("ساخت فایل ویدئو در گالری ناموفق بود")
        uri = u
        val p = resolver.openFileDescriptor(u, "rw")
            ?: throw IOException("باز کردن فایل ویدئو ناموفق بود")
        pfd = p
        return MediaMuxer(p.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun setupAudio() {
        if (audioMode == 0) return
        var rec: AudioRecord? = null
        var ac: MediaCodec? = null
        try {
            val stereo = audioMode == 2
            val chMask = if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            channels = if (stereo) 2 else 1
            val af = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AUDIO_RATE)
                .setChannelMask(chMask)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(AUDIO_RATE, chMask, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = max(minBuf * 2, 16384)

            rec = if (audioMode == 2) {
                if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("internal audio needs API 29")
                val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                AudioRecord.Builder()
                    .setAudioFormat(af)
                    .setBufferSizeInBytes(bufSize)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build()
            } else {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(af)
                    .setBufferSizeInBytes(bufSize)
                    .build()
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord init failed")

            val afmt = MediaFormat.createAudioFormat(AAC_MIME, AUDIO_RATE, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)
            }
            ac = MediaCodec.createEncoderByType(AAC_MIME)
            ac.configure(afmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            ac.start()

            audioRecord = rec
            audioCodec = ac
        } catch (e: Exception) {
            Log.w(TAG, "audio setup failed", e)
            runCatching { rec?.release() }
            runCatching { ac?.release() }
            audioRecord = null
            audioCodec = null
            audioMode = 0
            warning = "صدا فعال نشد؛ ویدئو بدون صدا ضبط شد"
        }
    }

    // ================= مولتی‌پلکسر =================

    private fun addTrack(fmt: MediaFormat, video: Boolean) {
        synchronized(lock) {
            val m = muxer ?: return
            val idx = m.addTrack(fmt)
            if (video) videoTrack = idx else audioTrack = idx
            if (videoTrack >= 0 && (!audioOn || audioTrack >= 0)) {
                m.start()
                muxerStarted = true
                startLatch.countDown()
            }
        }
    }

    /** صبر تا وقتی هر دو مسیر آماده شوند؛ false = ضبط در حال توقف یا شروع نشد */
    private fun awaitStart(): Boolean {
        while (!muxerStarted) {
            if (stopping) return false
            val left = startedAt + START_TIMEOUT_MS - SystemClock.elapsedRealtime()
            if (left <= 0) {
                fail("شروع ضبط ناموفق بود (تصویر آماده نشد)")
                return false
            }
            startLatch.await(min(left, 100L), TimeUnit.MILLISECONDS)
        }
        return true
    }

    private fun fail(msg: String) {
        if (failure != null) return
        failure = msg
        stopping = true
        onFailure?.invoke(msg)
    }

    private fun videoPts(t: Long): Long {
        var v = max(0L, t - baseUs)
        if (v <= lastVideoPts) v = lastVideoPts + 1
        lastVideoPts = v
        return v
    }

    private fun audioPts(t: Long): Long {
        var v = max(0L, t)
        if (v <= lastAudioPts) v = lastAudioPts + 1
        lastAudioPts = v
        return v
    }

    // ================= حلقه‌ی ویدئو =================

    private fun videoLoop() {
        val vc = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = vc.dequeueOutputBuffer(info, 10_000L)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    addTrack(vc.outputFormat, true)
                } else if (idx >= 0) {
                    val buf = vc.getOutputBuffer(idx)
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (info.size > 0 && buf != null && awaitStart()) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        info.presentationTimeUs = videoPts(info.presentationTimeUs)
                        synchronized(lock) { muxer?.writeSampleData(videoTrack, buf, info) }
                    }
                    vc.releaseOutputBuffer(idx, false)
                    if (eos) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "video loop", e)
            fail("خطا در رمزگذاری ویدئو")
        }
    }

    // ================= حلقه‌ی صدا =================

    private fun audioLoop() {
        val rec = audioRecord ?: return
        val ac = audioCodec ?: return
        val info = MediaCodec.BufferInfo()
        val bytesPerFrame = 2 * channels
        var eosQueued = false
        var eosAt = 0L
        var totalFrames = 0L
        try {
            rec.startRecording()
            val startUs = System.nanoTime() / 1000L - baseUs
            var finished = false
            while (!finished) {
                // ورودی
                if (!eosQueued) {
                    val inIdx = ac.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        val inBuf = ac.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            inBuf.clear()
                            val pts = startUs + totalFrames * 1_000_000L / AUDIO_RATE
                            if (stopping) {
                                ac.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                eosQueued = true
                                eosAt = SystemClock.elapsedRealtime()
                            } else {
                                val n = rec.read(inBuf, inBuf.capacity())
                                if (n > 0) {
                                    ac.queueInputBuffer(inIdx, 0, n, pts, 0)
                                    totalFrames += (n / bytesPerFrame).toLong()
                                } else if (n < 0) {
                                    ac.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    eosQueued = true
                                    eosAt = SystemClock.elapsedRealtime()
                                } else {
                                    ac.queueInputBuffer(inIdx, 0, 0, pts, 0)
                                }
                            }
                        }
                    }
                }
                // خروجی
                while (true) {
                    val oi = ac.dequeueOutputBuffer(info, 0L)
                    if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        addTrack(ac.outputFormat, false)
                    } else if (oi >= 0) {
                        val buf = ac.getOutputBuffer(oi)
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                        if (info.size > 0 && buf != null && awaitStart()) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            info.presentationTimeUs = audioPts(info.presentationTimeUs)
                            synchronized(lock) { muxer?.writeSampleData(audioTrack, buf, info) }
                        }
                        ac.releaseOutputBuffer(oi, false)
                        if (eos) { finished = true; break }
                    }
                }
                // اگر بعد از ارسال EOS خروجی نیامد، بیش از ۲ ثانیه منتظر نمی‌مانیم
                if (eosQueued && SystemClock.elapsedRealtime() - eosAt > 2000L) finished = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio loop", e)
            // خطای صدا ضبط ویدئو را خراب نمی‌کند، فقط بدون ادامه‌ی صدا می‌ماند
            if (warning == null) warning = "ضبط صدا وسط کار قطع شد"
        }
    }

    // ================= توقف =================

    /** توقف و ذخیره‌ی فایل؛ روی ترد جدا انجام می‌شود. onDone(ذخیره‌شد؟، پیام) */
    fun stop(onDone: (Boolean, String?) -> Unit) {
        Thread({
            stopping = true
            runCatching { display?.release() }
            runCatching { videoCodec?.signalEndOfInputStream() }
            runCatching { videoThread?.join(6000) }
            runCatching { audioThread?.join(6000) }

            var stopOk = false
            try {
                if (muxerStarted) {
                    muxer?.stop()
                    stopOk = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "muxer stop failed", e)
            }
            runCatching { muxer?.release() }
            releaseCodecs()
            runCatching { pfd?.close() }
            val saved = publish(stopOk)
            onDone(saved, failure ?: warning)
        }, "rec-stop").start()
    }

    /** لغو بدون ذخیره (وقتی شروع ضبط ناموفق بوده) */
    fun abort() {
        stopping = true
        runCatching { display?.release() }
        runCatching { muxer?.release() }
        releaseCodecs()
        runCatching { pfd?.close() }
        publish(false)
    }

    private fun releaseCodecs() {
        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        runCatching { inputSurface?.release() }
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
    }

    /** keep=true: فایل را در گالری نهایی کن؛ وگرنه حذفش کن */
    private fun publish(keep: Boolean): Boolean {
        val u = uri ?: return false
        return try {
            if (keep) {
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    ctx.contentResolver.update(u, cv, null, null)
                }
                true
            } else {
                ctx.contentResolver.delete(u, null, null)
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "publish failed", e)
            false
        }
    }
}
