package com.example.fidiboshot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * سرویس foreground ضبط ویدئو (لازمه‌ی MediaProjection در اندروید ۱۰ به بعد).
 * نوتیفیکیشن آن دکمه‌ی «توقف ضبط» دارد؛ چون منوی شناور حین ضبط پنهان است.
 */
class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "com.example.fidiboshot.START_RECORD"
        const val ACTION_STOP = "com.example.fidiboshot.STOP_RECORD"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "fidibo_record"
        private const val NOTIF_ID = 4711
        private const val TAG = "FidiboRecSvc"
        private const val START_DELAY_MS = 700L

        /** true از لحظه‌ای که ضبط واقعاً شروع شده تا ذخیره */
        @Volatile var active = false
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var projCallback: MediaProjection.Callback? = null
    private var recorder: ScreenRecorder? = null
    private var startRunnable: Runnable? = null
    private var stopRequested = false
    private var began = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> beginRecording(intent)
            ACTION_STOP -> stopRecording(null)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun beginRecording(intent: Intent) {
        if (began) return
        began = true

        // ۱) اول باید foreground شویم، بعد MediaProjection بگیریم
        val hasAudioPerm = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val audio = if (hasAudioPerm) Prefs.vAudio(this) else 0
        try {
            createChannel()
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                if (audio == 1 && Build.VERSION.SDK_INT >= 30) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTIF_ID, n, type)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            finishService(false, "شروع سرویس ضبط ناموفق بود")
            return
        }

        // ۲) MediaProjection
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data = readData(intent)
        if (data == null) {
            finishService(false, "مجوز ضبط صفحه دریافت نشد")
            return
        }
        val mp = try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            finishService(false, "مجوز ضبط صفحه دریافت نشد")
            return
        }
        projection = mp
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopRecording(null) }
            }
        }
        projCallback = cb
        mp.registerCallback(cb, main)

        // ۳) کمی صبر تا پنجره‌ی مجوز کامل بسته شود و داخل ویدئو نیفتد
        val r = Runnable {
            startRunnable = null
            if (stopRequested) return@Runnable
            var rec: ScreenRecorder? = null
            try {
                rec = ScreenRecorder(this, mp, Prefs.vQuality(this), Prefs.vFps(this), audio)
                rec.onFailure = { msg -> main.post { stopRecording(msg) } }
                rec.start()
                recorder = rec
                active = true
            } catch (e: Exception) {
                Log.e(TAG, "recorder start failed", e)
                runCatching { rec?.abort() }
                stopRequested = true
                finishService(false, "شروع ضبط ناموفق بود")
            }
        }
        startRunnable = r
        main.postDelayed(r, START_DELAY_MS)
    }

    @Suppress("DEPRECATION")
    private fun readData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_DATA)

    private fun stopRecording(reason: String?) {
        if (stopRequested) return
        stopRequested = true
        startRunnable?.let { main.removeCallbacks(it) }
        startRunnable = null
        val rec = recorder
        if (rec == null) {
            finishService(false, reason ?: "ضبط لغو شد")
            return
        }
        rec.stop { saved, note ->
            main.post {
                val msg = if (saved) {
                    "ویدئو در Movies/FidiboPages ذخیره شد" + (reason ?: note)?.let { "\n$it" }.orEmpty()
                } else {
                    reason ?: note ?: "ضبط ناموفق بود"
                }
                finishService(saved, msg)
            }
        }
    }

    private fun finishService(saved: Boolean, msg: String?) {
        active = false
        projCallback?.let { cb -> runCatching { projection?.unregisterCallback(cb) } }
        runCatching { projection?.stop() }
        projection = null
        recorder = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        FidiboScreenshotService.instance?.onVideoFinished(saved, msg)
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "ضبط ویدئو", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stop), "توقف ضبط", stop
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stop)
            .setContentTitle("در حال ضبط صفحه")
            .setContentText("برای توقف و ذخیره، «توقف ضبط» را بزن")
            .setOngoing(true)
            .setContentIntent(stop)
            .addAction(action)
            .build()
    }

    override fun onDestroy() {
        active = false
        startRunnable?.let { main.removeCallbacks(it) }
        super.onDestroy()
    }
}
