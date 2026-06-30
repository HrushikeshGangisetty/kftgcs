package com.example.kftgcs.ui.replay.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.kftgcs.utils.LogUtils
import java.io.File

/**
 * Foreground service that screen-records the flight replay to an `.mp4` via [MediaProjection].
 *
 * On Android 14+ a `mediaProjection`-typed foreground service must be running before the projection
 * is acquired, so the flow is: UI gets the user's screen-capture consent (Activity result) → starts
 * this service with the result → the service goes foreground, builds the [MediaProjection], wires a
 * [MediaRecorder] to a mirrored [VirtualDisplay], and records. Stopping finalises the file and hands
 * it to [ScreenRecordController.pendingShareFile] for the UI to share.
 */
class ScreenRecordService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // System or user revoked the projection — finalise without auto-sharing.
            stopRecording(share = false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording(share = true)
            ACTION_START -> {
                startForegroundNotification()
                if (!startRecording(intent)) {
                    LogUtils.e(TAG, "Recording failed to start; stopping service")
                    ScreenRecordController.isRecording = false
                    stopSelfCleanup()
                }
            }
            else -> stopSelfCleanup()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent): Boolean {
        return try {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                ?: return false
            val width = intent.getIntExtra(EXTRA_WIDTH, 0)
            val height = intent.getIntExtra(EXTRA_HEIGHT, 0)
            val dpi = intent.getIntExtra(EXTRA_DPI, 320)
            val path = intent.getStringExtra(EXTRA_OUTPUT_PATH) ?: return false
            if (width <= 0 || height <= 0) return false

            val file = File(path)
            outputFile = file

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, data) ?: return false
            mediaProjection = projection
            projection.registerCallback(projectionCallback, mainHandler)

            val rec = createRecorder(file, width, height)
            recorder = rec

            virtualDisplay = projection.createVirtualDisplay(
                "ReplayScreenRecord",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, null
            )
            rec.start()
            ScreenRecordController.isRecording = true
            LogUtils.i(TAG, "Recording started → ${file.absolutePath} (${width}x$height)")
            true
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to start screen recording", e)
            releaseAll()
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(file: File, width: Int, height: Int): MediaRecorder {
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        rec.setVideoEncodingBitRate(8_000_000)
        rec.setVideoFrameRate(30)
        rec.setVideoSize(width, height)
        rec.setOutputFile(file.absolutePath)
        rec.prepare()
        return rec
    }

    private fun stopRecording(share: Boolean) {
        val file = outputFile
        var ok = false
        try {
            recorder?.stop()
            ok = true
        } catch (e: Exception) {
            // stop() throws if no frames were captured (recording too short) — discard the file.
            LogUtils.w(TAG, "MediaRecorder.stop failed: ${e.message}")
            file?.takeIf { it.exists() }?.delete()
        }
        releaseAll()
        ScreenRecordController.isRecording = false
        if (share && ok && file != null && file.exists() && file.length() > 0L) {
            ScreenRecordController.pendingShareFile = file
        }
        outputFile = null
        stopSelfCleanup()
    }

    private fun releaseAll() {
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null
    }

    private fun stopSelfCleanup() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Screen recording", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording flight replay")
            .setContentText("Screen recording in progress")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        releaseAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScreenRecordService"
        private const val NOTIF_ID = 8731
        private const val CHANNEL_ID = "screen_record"
        const val ACTION_START = "com.example.kftgcs.replay.REC_START"
        const val ACTION_STOP = "com.example.kftgcs.replay.REC_STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_OUTPUT_PATH = "output_path"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_DPI = "dpi"

        fun start(
            context: Context,
            resultCode: Int,
            data: Intent,
            outputFile: File,
            width: Int,
            height: Int,
            dpi: Int
        ) {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_OUTPUT_PATH, outputFile.absolutePath)
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_DPI, dpi)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenRecordService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
