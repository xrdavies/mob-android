package com.xrdavies.mob.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.xrdavies.mob.MobApplication
import com.xrdavies.mob.R

/**
 * Keeps the app process (and with it the event streams in AppState) alive in the background,
 * so questions and finished tasks reach the phone while mob is not on screen. There is no
 * push service on an office intranet; this foreground service is how Android allows it.
 */
class WatchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = (application as MobApplication).appState
        val count = state.machines.value.size
        val n = NotificationCompat.Builder(this, Notifier.WATCH)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Watching ${if (count == 1) "your office computer" else "$count office computers"}")
            .setContentText("You get a notification when the agent asks something or a task ends")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(state.notifier.openChat("", null, 0))
            .build()
        ServiceCompat.startForeground(
            this, ID, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        return START_STICKY
    }

    companion object {
        private const val ID = 1

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, WatchService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }
    }
}
