package com.xrdavies.mob.data

// Mirrors the mobd v3 API (mob/docs/mobd-v3.md). Gson ignores Kotlin defaults and
// nullability, so every field mobd may omit is nullable and read through a helper.

data class Check(val ok: Boolean, val unknown: Boolean?, val detail: String?)

data class Health(
    val ok: Boolean,
    val features: List<String>?,
    val runners: List<Runner>?,
    val runner: String?,
    val models: List<String>?,
    val efforts: List<String>?,
    val checks: Map<String, Check>?,
) {
    fun supports(feature: String) = features.orEmpty().contains(feature)
    fun runner(name: String?): Runner? = runners.orEmpty().let { list -> list.firstOrNull { it.name == (name ?: runner) } ?: list.firstOrNull { it.default == true } }
}

data class Runner(
    val name: String,
    val label: String?,
    val models: List<String>?,
    val efforts: List<String>?,
    val images: Boolean?,
    val resume: Boolean?,
    val cost: Boolean?,
    val steer: Boolean?,
    val available: Boolean?,
    val detail: String?,
    val default: Boolean?,
) {
    val title get() = label ?: name
}

object Status {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    fun terminal(s: String?) = s == SUCCEEDED || s == FAILED || s == CANCELLED
    fun busy(s: String?) = s == QUEUED || s == RUNNING
}

data class Task(
    val id: String,
    val sessionId: String,
    val runner: String?,
    val prompt: String?,
    val model: String?,
    val effort: String?,
    val status: String,
    val eventId: Long?,
    val attachments: List<String>?,
    val createdAt: String?,
    val updatedAt: String?,
    val startedAt: String?,
    val finishedAt: String?,
    val usage: Usage?,
    val error: String?,
    val queuePosition: Int?,
    /** True while the turn waits for the user to answer a question. */
    val waiting: Boolean?,
) {
    val isTerminal get() = Status.terminal(status)
}

data class Usage(
    val inputTokens: Long?,
    val cachedInputTokens: Long?,
    val outputTokens: Long?,
    val reasoningOutputTokens: Long?,
    val costUsd: Double?,
)

data class Session(
    val id: String,
    val title: String?,
    val runner: String?,
    val resumeId: String?,
    val activeTaskId: String?,
    val status: String?,
    val pinned: Boolean?,
    val waiting: Boolean?,
    val archived: Boolean?,
    val createdAt: String?,
    val updatedAt: String?,
    // Only GET /sessions/{id} includes messages; lists and events omit them.
    val messages: List<Message>?,
) {
    val displayTitle get() = title?.takeIf { it.isNotBlank() } ?: "New chat"
}

data class Message(
    val id: String,
    val taskId: String,
    val role: String,
    val content: String,
    val createdAt: String?,
)

data class Question(
    val id: String,
    val taskId: String,
    val kind: String,
    val text: String,
    val details: String?,
    val options: List<String>?,
    val allowText: Boolean?,
    val status: String,
    val answer: String?,
    val approved: Boolean?,
    val createdAt: String?,
) {
    val isApproval get() = kind == "approval"
    val isPending get() = status == "pending"
}

data class PlanItem(val text: String, val completed: Boolean?)
data class FileChange(val path: String, val kind: String?)

data class Activity(
    val id: String,
    val taskId: String,
    val kind: String,
    val status: String,
    val title: String,
    val server: String?,
    val tool: String?,
    val arguments: Map<String, Any?>?,
    val command: String?,
    val exitCode: Int?,
    val text: String?,
    val truncated: Boolean?,
    val error: String?,
    val images: List<String>?,
    val items: List<PlanItem>?,
    val changes: List<FileChange>?,
    val startedAt: String?,
    val updatedAt: String?,
)

data class SessionsResponse(val sessions: List<Session>?)
data class TasksResponse(val tasks: List<Task>?)
data class Queue(val running: List<Task>?, val queued: List<Task>?)

data class SessionDetail(
    val session: Session,
    val tasks: List<Task>?,
    val activities: List<Activity>?,
    val questions: List<Question>?,
)

data class Upload(val id: String, val name: String?, val mime: String?, val size: Long?, val url: String?)

data class CreateTaskRequest(
    val prompt: String,
    val sessionId: String? = null,
    val runner: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val attachments: List<String>? = null,
)

data class PatchSessionRequest(val title: String? = null, val pinned: Boolean? = null, val archived: Boolean? = null)

data class AnswerQuestionRequest(val answer: String? = null, val approved: Boolean? = null)

data class SteerRequest(val prompt: String)

/** An office Mac running mobd. Stored on the phone only. */
data class Machine(
    val id: String,
    val name: String,
    val mobdUrl: String,
    /** macOS user for Screen Sharing sign-in (ARD), so the live view lands in that user's session. */
    val vncUsername: String = "",
    val vncPassword: String = "",
)
