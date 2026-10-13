package com.xrdavies.mob.data

import com.google.gson.annotations.SerializedName

data class Health(
    val ok: Boolean,
    val features: List<String>,
    val runners: List<Runner>?,
    val runner: String?,
    val models: List<String>?,
    val efforts: List<String>?
)

data class Runner(
    val name: String,
    val label: String,
    val models: List<String>,
    val efforts: List<String>,
    val images: Boolean,
    val resume: Boolean,
    val cost: Boolean,
    val steer: Boolean?,
    val available: Boolean,
    val detail: String?
)

data class Task(
    val id: String,
    val sessionId: String,
    val runner: String,
    val prompt: String,
    val model: String?,
    val effort: String?,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val usage: Usage?
) {
    val isTerminal: Boolean
        get() = status in listOf("succeeded", "failed", "cancelled")
}

data class Usage(
    val inputTokens: Int?,
    val cachedInputTokens: Int?,
    val outputTokens: Int?,
    val reasoningOutputTokens: Int?,
    val costUsd: Double?
)

data class Session(
    val id: String,
    val runner: String,
    val resumeId: String?,
    val messages: List<Message>,
    val createdAt: String,
    val updatedAt: String
)

data class Message(
    val id: String,
    val taskId: String,
    val role: String,
    val content: String,
    val createdAt: String
)

data class Question(
    val id: String,
    val taskId: String,
    val kind: String,
    val text: String,
    val details: String?,
    val options: List<String>?,
    val allowText: Boolean,
    val status: String,
    val answer: String?,
    val approved: Boolean?,
    val createdAt: String
)

data class Activity(
    val id: String,
    val taskId: String,
    val kind: String,
    val status: String,
    val title: String,
    val tool: String?,
    val command: String?,
    val text: String?,
    val startedAt: String,
    val updatedAt: String
)

data class SessionsResponse(
    val sessions: List<Session>
)

data class SessionDetail(
    val session: Session,
    val tasks: List<Task>,
    val activities: List<Activity>,
    val questions: List<Question>
)

data class CreateTaskRequest(
    val prompt: String,
    val sessionId: String? = null,
    val runner: String? = null,
    val model: String? = null,
    val effort: String? = null
)

data class AnswerQuestionRequest(
    val answer: String? = null,
    val approved: Boolean? = null
)

data class SteerRequest(
    val prompt: String
)
