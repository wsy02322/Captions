package app.captions.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.captions.MainActivity
import app.captions.R
import app.captions.audio.CaptureSource
import app.captions.pipeline.CaptionSessionController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.inject.Inject

@AndroidEntryPoint
class CaptionForegroundService : Service() {

    @Inject lateinit var controller: CaptionSessionController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                controller.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val source = intent?.getStringExtra(EXTRA_CAPTURE_SOURCE)
                    ?.let { runCatching { CaptureSource.valueOf(it) }.getOrNull() }
                    ?: CaptureSource.MICROPHONE
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                    ?: Activity.RESULT_CANCELED
                val projectionData = if (Build.VERSION.SDK_INT >= 33) {
                    intent?.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)
                }

                // Android 14+: start FGS before obtaining MediaProjection.
                startAsForeground(source)

                val projection = if (source == CaptureSource.PLAYBACK && projectionData != null) {
                    val mpm = getSystemService(MediaProjectionManager::class.java)
                    mpm.getMediaProjection(resultCode, projectionData)
                } else {
                    null
                }
                controller.start(scope, source = source, mediaProjection = projection)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        controller.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(source: CaptureSource) {
        createChannel()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptionForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (source) {
            CaptureSource.MICROPHONE -> getString(R.string.notification_text_mic)
            CaptureSource.PLAYBACK -> getString(R.string.notification_text_playback)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .build()

        val types = when {
            Build.VERSION.SDK_INT < 34 -> 0
            source == CaptureSource.PLAYBACK ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }

        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "app.captions.action.STOP_CAPTION"
        const val EXTRA_CAPTURE_SOURCE = "capture_source"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val CHANNEL_ID = "caption_capture"
        private const val NOTIFICATION_ID = 42

        fun startMicrophone(context: Context) {
            val intent = Intent(context, CaptionForegroundService::class.java)
                .putExtra(EXTRA_CAPTURE_SOURCE, CaptureSource.MICROPHONE.name)
            context.startForegroundService(intent)
        }

        fun startPlayback(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptionForegroundService::class.java)
                .putExtra(EXTRA_CAPTURE_SOURCE, CaptureSource.PLAYBACK.name)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_PROJECTION_DATA, data)
            context.startForegroundService(intent)
            bringCaptionsToFront(context)
        }

        /** After MediaProjection UI (single-app / entire screen), return to Captions live screen. */
        fun bringCaptionsToFront(context: Context) {
            val launch = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(MainActivity.EXTRA_NAVIGATE_TO, MainActivity.ROUTE_LIVE)
            }
            context.startActivity(launch)
        }

        /** @deprecated Prefer [startMicrophone] or [startPlayback]. */
        fun start(context: Context) = startMicrophone(context)

        fun stop(context: Context) {
            val intent = Intent(context, CaptionForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
