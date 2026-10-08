package dev.ide.android.Terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/** Keeps the interactive shell process alive while the terminal Activity is off-screen. */
class TerminalKeepAliveService : Service() {

    private var inForeground = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification()
        // Android 12+ throws ForegroundServiceStartNotAllowedException when the service starts with the
        // app in the background (a system START_STICKY restart, or the singleTask activity recreated from
        // recents). Degrade instead of crashing: no foreground slot, no keep-alive.
        if (!enterForeground(notification)) {
            Log.w(TAG, "startForeground not allowed (app in background); stopping keep-alive")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_NOT_STICKY unless we own a foreground slot: a sticky retry from the background would only
        // hit the same startForeground denial and crash again.
        if (intent?.action == ACTION_STOP || !inForeground) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /** Promote to a foreground service. Returns false if the system denies the promotion. */
    private fun enterForeground(notification: Notification): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            inForeground = true
        }.onFailure {
            inForeground = false
            Log.e(TAG, "startForeground failed", it)
        }.isSuccess
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Terminal", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Mantiene activa la terminal y los procesos de la IA"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, TerminalActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TerminalKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("CodeStudio: terminal activa")
            .setContentText("La terminal y los procesos siguen ejecutándose")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Detener", stopIntent).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "dev.ide.android.Terminal.STOP_KEEP_ALIVE"
        private const val TAG = "TerminalKeepAlive"
        private const val CHANNEL_ID = "codestudio-terminal"
        private const val NOTIFICATION_ID = 4201
    }
}
