package com.xrdavies.mob.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.xrdavies.mob.MobApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Sends an answer picked or typed in a question notification to the office computer. */
class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val machine = intent.getStringExtra(Notifier.EXTRA_MACHINE) ?: return
        val session = intent.getStringExtra(Notifier.EXTRA_SESSION) ?: return
        val task = intent.getStringExtra(EXTRA_TASK) ?: return
        val question = intent.getStringExtra(EXTRA_QUESTION) ?: return
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim()
        val answer = typed ?: intent.getStringExtra(EXTRA_ANSWER)
        val approved = if (intent.hasExtra(EXTRA_APPROVED)) intent.getBooleanExtra(EXTRA_APPROVED, false) else null
        if (answer.isNullOrEmpty() && approved == null) return
        val state = (context.applicationContext as MobApplication).appState
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                state.answerFromNotification(machine, session, task, question, answer, approved)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_TASK = "task"
        const val EXTRA_QUESTION = "question"
        const val EXTRA_ANSWER = "answer"
        const val EXTRA_APPROVED = "approved"
        const val KEY_REPLY = "reply"
    }
}
