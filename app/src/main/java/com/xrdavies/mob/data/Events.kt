package com.xrdavies.mob.data

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException

data class SseEvent(val id: String?, val type: String, val data: String)

/** Pseudo event sent once the stream is connected. */
const val OPEN = "open"

/**
 * One server-sent event connection. The flow ends when the server closes the stream and
 * fails on network errors; callers reconnect with the last event ID.
 */
fun OkHttpClient.sse(url: String, lastEventId: String? = null): Flow<SseEvent> = callbackFlow {
    val request = Request.Builder().url(url).header("Accept", "text/event-stream")
        .apply { lastEventId?.let { header("Last-Event-ID", it) } }.build()
    val source = EventSources.createFactory(this@sse).newEventSource(request, object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            trySend(SseEvent(null, OPEN, ""))
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            trySend(SseEvent(id, type ?: "message", data))
        }

        override fun onClosed(eventSource: EventSource) {
            close()
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            close(t ?: IOException("HTTP ${response?.code}"))
        }
    })
    awaitClose { source.cancel() }
}.buffer(Channel.UNLIMITED)

/** An event on GET /tasks/{id}/events. */
data class TaskEvent(
    val id: Long,
    val type: String?,
    val message: String?,
    val messageId: String?,
    val role: String?,
    val status: String?,
    val activity: Activity?,
    val question: Question?,
    val usage: Usage?,
    val timestamp: String?,
)
