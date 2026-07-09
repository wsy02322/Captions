package app.captions.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.captions.MainActivity
import app.captions.R
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
                startAsForeground()
                controller.start(scope)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        controller.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
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
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
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
        private const val CHANNEL_ID = "caption_capture"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, CaptionForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CaptionForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
