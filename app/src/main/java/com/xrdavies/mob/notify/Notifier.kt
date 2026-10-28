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
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.xrdavies.mob.MainActivity
import com.xrdavies.mob.R
import com.xrdavies.mob.data.Machine
import com.xrdavies.mob.data.Question
import com.xrdavies.mob.data.Session

/** Phone notifications for agent questions and finished turns; questions can be answered from the notification. */
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
                description = "Shown while Mob watches your office computers in the background"
            })
        }
    }

    val allowed: Boolean
        get() = manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun id(m: Machine, s: Session, kind: String) = id(m, s.id, kind)
    private fun id(m: Machine, session: String, kind: String) = "${m.id}/$session/$kind".hashCode()

    /** Opens the chat on its machine when tapped. */
    fun openChat(machineId: String, sessionId: String?, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_MACHINE, machineId).putExtra(EXTRA_SESSION, sessionId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(
        id: Int, channel: String, title: String, text: String, m: Machine, session: String, number: Int = 0,
        actions: List<NotificationCompat.Action> = emptyList(),
    ) {
        if (!allowed) return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(m.name)
            .setAutoCancel(true)
            .setPriority(if (channel == QUESTIONS) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openChat(m.id, session, id))
            // Launchers that show badges use this as the app icon's count.
            .apply { if (number > 0) setNumber(number) }
            .apply { actions.forEach(::addAction) }
            .build()
        com.xrdavies.mob.viewmodel.Log.add("notification: $title — $text")
        try {
            manager.notify(id, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    fun question(m: Machine, s: Session, q: Question?, waiting: Int = 0) {
        cancel(m, s, "done")
        val title = if (q?.isApproval == true) "Approval needed" else "The agent asks"
        post(id(m, s, "ask"), QUESTIONS, title, q?.text ?: s.displayTitle, m, s.id, waiting, q?.let { actions(m, s, it) }.orEmpty())
    }

    /** Approve/deny, a few options and a typed reply; Android shows at most three buttons. */
    private fun actions(m: Machine, s: Session, q: Question): List<NotificationCompat.Action> {
        var code = id(m, s, "ask")
        fun intent(mutable: Boolean, extras: Intent.() -> Unit) = PendingIntent.getBroadcast(
            context, code++,
            Intent(context, AnswerReceiver::class.java)
                .putExtra(EXTRA_MACHINE, m.id).putExtra(EXTRA_SESSION, s.id)
                .putExtra(AnswerReceiver.EXTRA_TASK, q.taskId).putExtra(AnswerReceiver.EXTRA_QUESTION, q.id)
                .apply(extras),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE),
        )
        fun button(label: String, extras: Intent.() -> Unit) =
            NotificationCompat.Action.Builder(0, label, intent(false, extras))
                // Answers act on the office computer, so they need an unlocked phone.
                .setAuthenticationRequired(true).build()
        if (q.isApproval) {
            return listOf(
                button("Approve") { putExtra(AnswerReceiver.EXTRA_APPROVED, true) },
                button("Deny") { putExtra(AnswerReceiver.EXTRA_APPROVED, false) },
            )
        }
        val options = q.options.orEmpty()
        val typed = options.isEmpty() || q.allowText != false
        val buttons = options.take(if (typed) 2 else 3).map { o -> button(o) { putExtra(AnswerReceiver.EXTRA_ANSWER, o) } }
        if (!typed) return buttons
        // RemoteInput fills in the reply, so this intent has to be mutable.
        val reply = NotificationCompat.Action.Builder(0, if (options.isEmpty()) "Reply" else "Other answer", intent(true) {})
            .addRemoteInput(RemoteInput.Builder(AnswerReceiver.KEY_REPLY).setLabel("Your answer").build())
            .setAllowGeneratedReplies(false)
            .setAuthenticationRequired(true)
            .build()
        return buttons + reply
    }

    /** An answer from a notification did not reach the office computer; replaces the question notification. */
    fun answerFailed(m: Machine, session: String, message: String) =
        post(id(m, session, "ask"), QUESTIONS, "Your answer was not sent", "$message. Open the chat to answer.", m, session)

    fun finished(m: Machine, s: Session, failed: Boolean) {
        cancel(m, s, "ask")
        post(id(m, s, "done"), TURNS, if (failed) "Task failed" else "Task finished", s.displayTitle, m, s.id)
    }

    /** The question was answered (here or on another device). */
    fun answered(m: Machine, s: Session) = answered(m, s.id)
    fun answered(m: Machine, session: String) = manager.cancel(id(m, session, "ask"))

    private fun cancel(m: Machine, s: Session, kind: String) = manager.cancel(id(m, s, kind))

    companion object {
        const val QUESTIONS = "questions"
        const val TURNS = "turns"
        const val WATCH = "watch"
        const val EXTRA_MACHINE = "machine"
        const val EXTRA_SESSION = "session"
    }
}
