package com.personaltech.gravador

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class RecorderService : Service() {

    enum class State { IDLE, COUNTDOWN, RECORDING }

    companion object {
        const val ACTION_START = "com.personaltech.gravador.START"
        const val ACTION_STOP = "com.personaltech.gravador.STOP"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_FILL = "fill"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_MIC = "mic"
        const val EXTRA_COUNTDOWN = "countdown"

        const val OUT_W = 1080
        const val OUT_H = 1920

        private const val CH_REC = "rec"
        private const val CH_SAVED = "saved"
        private const val NOTIF_REC = 1
        private const val NOTIF_SAVED = 2

        @Volatile var state: State = State.IDLE
            private set
        var listener: (() -> Unit)? = null
    }

    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var cropper: GlCropper? = null
    private var pfd: ParcelFileDescriptor? = null
    private var videoUri: Uri? = null
    private var stopping = false

    private var fill = true
    private var fps = 30
    private var bitrate = 12_000_000
    private var mic = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Usuário parou pelo sistema (chip vermelho / tela bloqueada etc.)
            main.post { stopRecording() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopRecording(); if (state == State.IDLE) stopSelf() }
            ACTION_START -> if (state == State.IDLE) start(intent) else Unit
            else -> if (state == State.IDLE) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        createChannels()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = dataExtra(intent)

        fill = intent.getBooleanExtra(EXTRA_FILL, true)
        fps = intent.getIntExtra(EXTRA_FPS, 30)
        bitrate = intent.getIntExtra(EXTRA_BITRATE, 12_000_000)
        mic = intent.getBooleanExtra(EXTRA_MIC, false) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val countdown = intent.getBooleanExtra(EXTRA_COUNTDOWN, true)

        // Android 14: o serviço precisa estar em primeiro plano ANTES de pegar o MediaProjection
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (mic && Build.VERSION.SDK_INT >= 30) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(NOTIF_REC, buildRecNotification("Preparando…", false), type)
        } catch (t: Throwable) {
            fail("Não foi possível iniciar o serviço: ${t.message}")
            return
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            fail("Permissão de captura não concedida.")
            return
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = try { mpm.getMediaProjection(resultCode, data) } catch (t: Throwable) { null }
        if (proj == null) {
            fail("Não foi possível iniciar a captura de tela.")
            return
        }
        proj.registerCallback(projectionCallback, main)
        projection = proj

        if (countdown) {
            setState(State.COUNTDOWN)
            tick(3)
        } else {
            begin()
        }
    }

    private fun tick(n: Int) {
        if (state != State.COUNTDOWN) return
        if (n == 0) { begin(); return }
        notifyRec(buildRecNotification("Começando em $n…", false))
        main.postDelayed({ tick(n - 1) }, 1000)
    }

    private fun begin() {
        val proj = projection ?: return
        try {
            // 1) Arquivo em Filmes/PT Gravador
            val name = "Tela_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PT Gravador")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
            ) ?: throw IllegalStateException("não consegui criar o arquivo")
            videoUri = uri
            val fd = contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("não consegui abrir o arquivo")
            pfd = fd

            // 2) Encoder H.264 1080x1920
            val rec = newRecorder()
            recorder = rec
            if (mic) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(OUT_W, OUT_H)
            rec.setVideoFrameRate(fps)
            rec.setVideoEncodingBitRate(bitrate)
            if (mic) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(128_000)
                rec.setAudioSamplingRate(44_100)
                rec.setAudioChannels(1)
            }
            rec.setOutputFile(fd.fileDescriptor)
            rec.prepare()
            val encoderSurface = rec.surface
            rec.start()

            // 3) Tela -> encoder
            val (sw, sh) = screenSizePortrait()
            val dpi = resources.displayMetrics.densityDpi
            val target: Surface
            val vdW: Int
            val vdH: Int
            if (fill) {
                val scale = max(OUT_W.toFloat() / sw, OUT_H.toFloat() / sh)
                vdW = even((sw * scale).roundToInt())
                vdH = even((sh * scale).roundToInt())
                val c = GlCropper(encoderSurface, OUT_W, OUT_H, vdW, vdH)
                cropper = c
                target = c.inputSurface
            } else {
                // O sistema encaixa a tela inteira em 1080x1920 (faixas pretas)
                vdW = OUT_W; vdH = OUT_H
                target = encoderSurface
            }

            virtualDisplay = proj.createVirtualDisplay(
                "PTGravador", vdW, vdH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                target, null, null
            )

            setState(State.RECORDING)
            notifyRec(buildRecNotification("Gravando 1080×1920 • ${fps}fps" + if (mic) " • mic" else "", true))
        } catch (t: Throwable) {
            fail("Erro ao iniciar gravação: ${t.message}")
        }
    }

    private fun stopRecording(quiet: Boolean = false) {
        if (stopping) return
        if (state == State.IDLE && projection == null && recorder == null) return
        stopping = true
        main.removeCallbacksAndMessages(null)

        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { cropper?.release() } catch (_: Throwable) {}
        cropper = null

        var ok = recorder != null
        recorder?.let {
            try { it.stop() } catch (_: Throwable) { ok = false } // sem frames = arquivo inválido
            try { it.reset(); it.release() } catch (_: Throwable) {}
        }
        recorder = null
        try { pfd?.close() } catch (_: Throwable) {}
        pfd = null

        val uri = videoUri
        videoUri = null
        if (uri != null) {
            try {
                if (ok) {
                    contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }, null, null)
                } else {
                    contentResolver.delete(uri, null, null)
                }
            } catch (_: Throwable) {}
        }

        try { projection?.unregisterCallback(projectionCallback) } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null

        setState(State.IDLE)
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (uri != null && ok) {
            Toast.makeText(this, "Vídeo salvo em Filmes/PT Gravador", Toast.LENGTH_LONG).show()
            showSavedNotification(uri)
        } else if (uri != null && !quiet) {
            Toast.makeText(this, "Gravação muito curta — nada foi salvo.", Toast.LENGTH_LONG).show()
        }
        stopping = false
        stopSelf()
    }

    private fun fail(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        if (state == State.IDLE && projection == null && recorder == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            stopRecording(quiet = true)
        }
    }

    override fun onDestroy() {
        if (state != State.IDLE) stopRecording()
        super.onDestroy()
    }

    // ---------- utilidades ----------

    @Suppress("DEPRECATION")
    private fun screenSizePortrait(): Pair<Int, Int> {
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        display.getRealMetrics(m)
        val w = min(m.widthPixels, m.heightPixels)
        val h = max(m.widthPixels, m.heightPixels)
        return w to h
    }

    @Suppress("DEPRECATION")
    private fun dataExtra(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_DATA)

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun even(v: Int) = if (v % 2 == 0) v else v + 1

    private fun setState(s: State) {
        state = s
        main.post { listener?.invoke() }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_REC, "Gravação em andamento", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SAVED, "Vídeos salvos", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun buildRecNotification(text: String, chrono: Boolean): Notification {
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH_REC)
            .setSmallIcon(R.drawable.ic_rec)
            .setContentTitle("PT Gravador")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openPi)
            .setUsesChronometer(chrono)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(chrono)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_rec), "■ PARAR", stopPi
                ).build()
            )
            .build()
    }

    private fun notifyRec(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_REC, n)
    }

    private fun showSavedNotification(uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            this, 3, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CH_SAVED)
            .setSmallIcon(R.drawable.ic_rec)
            .setContentTitle("Vídeo salvo")
            .setContentText("Toque para assistir • Filmes/PT Gravador")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_SAVED, n)
        } catch (_: SecurityException) {}
    }
}
