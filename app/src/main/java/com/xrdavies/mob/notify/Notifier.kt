package com.xrdavies.mob.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.xrdavies.mob.MainActivity
import com.xrdavies.mob.R
import com.xrdavies.mob.data.Machine
import com.xrdavies.mob.data.Session

/** Phone notifications for agent questions and finished turns. */
class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(QUESTIONS, "Agent questions", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "The agent is waiting for your answer or approval"
            })
            nm.createNotificationChannel(NotificationChannel(TURNS, "Finished tasks", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A task finished or failed"
            })
            nm.createNotificationChannel(NotificationChannel(WATCH, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while mob watches your office Macs in the background"
            })
        }
    }

    val allowed: Boolean
        get() = manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun id(m: Machine, s: Session, kind: String) = "${m.id}/${s.id}/$kind".hashCode()

    /** Opens the chat on its machine when tapped. */
    fun openChat(machineId: String, sessionId: String?, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_MACHINE, machineId).putExtra(EXTRA_SESSION, sessionId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(id: Int, channel: String, title: String, text: String, m: Machine, s: Session, number: Int = 0) {
        if (!allowed) return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(m.name)
            .setAutoCancel(true)
            .setPriority(if (channel == QUESTIONS) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openChat(m.id, s.id, id))
            // Launchers that show badges use this as the app icon's count.
            .apply { if (number > 0) setNumber(number) }
            .build()
        com.xrdavies.mob.viewmodel.Log.add("notification: $title — $text")
        try {
            manager.notify(id, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    fun question(m: Machine, s: Session, approval: Boolean, text: String?, waiting: Int = 0) {
        cancel(m, s, "done")
        post(id(m, s, "ask"), QUESTIONS, if (approval) "Approval needed" else "The agent asks", text ?: s.displayTitle, m, s, waiting)
    }

    fun finished(m: Machine, s: Session, failed: Boolean) {
        cancel(m, s, "ask")
        post(id(m, s, "done"), TURNS, if (failed) "Task failed" else "Task finished", s.displayTitle, m, s)
    }

    /** The question was answered (here or on another device). */
    fun answered(m: Machine, s: Session) = cancel(m, s, "ask")

    private fun cancel(m: Machine, s: Session, kind: String) = manager.cancel(id(m, s, kind))

    companion object {
        const val QUESTIONS = "questions"
        const val TURNS = "turns"
        const val WATCH = "watch"
        const val EXTRA_MACHINE = "machine"
        const val EXTRA_SESSION = "session"
    }
}
