package com.xrdavies.mob.data

import java.time.Instant

// Ported from the desktop app (apps/mob/src/lib/timeline.ts).

// The question card replaces the tool step that asked it.
private val ASK_TOOLS = setOf("ask_user", "request_approval")

/** Consecutive steps fold into one expandable group from this many on. */
const val GROUP_MIN = 3

sealed interface TimelineItem {
    val id: String

    data class User(override val id: String, val message: Message, val task: Task?) : TimelineItem
    data class Assistant(override val id: String, val message: Message) : TimelineItem
    data class Steps(override val id: String, val activities: List<Activity>) : TimelineItem
    data class Group(override val id: String, val activities: List<Activity>) : TimelineItem
    data class Ask(override val id: String, val question: Question) : TimelineItem
    data class Queued(override val id: String, val task: Task) : TimelineItem
    data class Working(override val id: String, val task: Task, val latest: Activity?, val waiting: Boolean) : TimelineItem
    data class Footer(override val id: String, val task: Task) : TimelineItem
}

private fun time(iso: String?): Long =
    iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: Long.MAX_VALUE

private class Bucket {
    val messages = mutableListOf<Message>()
    val activities = mutableListOf<Activity>()
    val questions = mutableListOf<Question>()
}

/** Builds the conversation: per turn, the prompt, then replies, steps and questions by time. */
fun buildTimeline(tasks: List<Task>, messages: List<Message>, activities: List<Activity>, questions: List<Question>): List<TimelineItem> {
    val items = mutableListOf<TimelineItem>()
    val known = tasks.map { it.id }.toSet()
    val byTask = linkedMapOf<String, Bucket>()
    fun bucket(id: String) = byTask.getOrPut(id) { Bucket() }
    messages.forEach { bucket(it.taskId).messages += it }
    activities.filter { it.tool !in ASK_TOOLS }.forEach { bucket(it.taskId).activities += it }
    questions.forEach { bucket(it.taskId).questions += it }
    val orphans = byTask.keys.filter { it !in known }

    for (turnId in tasks.map { it.id } + orphans) {
        val task = tasks.firstOrNull { it.id == turnId }
        val b = byTask[turnId] ?: Bucket()
        val user = b.messages.firstOrNull { it.role == "user" }
            ?: task?.prompt?.takeIf { it.isNotEmpty() }?.let { Message("$turnId:user", turnId, "user", it, task.createdAt) }
        if (user != null) items += TimelineItem.User(user.id, user, task)

        data class Entry(val at: Long, val order: Int, val message: Message? = null, val activity: Activity? = null, val question: Question? = null)
        val entries = mutableListOf<Entry>()
        var order = 0
        b.messages.filter { it.id != user?.id }.forEach { entries += Entry(time(it.createdAt), order++, message = it) }
        val seenNotices = mutableSetOf<String>()
        for (a in b.activities) {
            if (a.kind == "notice" && !seenNotices.add(a.text ?: a.title)) continue
            entries += Entry(time(a.startedAt), order++, activity = a)
        }
        b.questions.forEach { entries += Entry(time(it.createdAt), order++, question = it) }
        entries.sortWith(compareBy<Entry> { it.at }.thenBy { it.order })

        val run = mutableListOf<Activity>()
        fun flush() {
            if (run.size >= GROUP_MIN) items += TimelineItem.Group("${run[0].id}:group", run.toList())
            else if (run.isNotEmpty()) items += TimelineItem.Steps("${run[0].id}:steps", run.toList())
            run.clear()
        }
        for (e in entries) {
            when {
                e.activity != null -> run += e.activity
                e.question != null -> {
                    flush()
                    items += TimelineItem.Ask("q:${e.question.id}", e.question)
                }
                else -> {
                    flush()
                    val m = e.message!!
                    items += if (m.role == "user") TimelineItem.User(m.id, m, null) else TimelineItem.Assistant(m.id, m)
                }
            }
        }
        flush()

        if (task == null) continue
        when (task.status) {
            Status.QUEUED -> items += TimelineItem.Queued("${task.id}:queued", task)
            Status.RUNNING -> items += TimelineItem.Working(
                "${task.id}:working", task,
                b.activities.lastOrNull { it.kind != "notice" },
                b.questions.any { it.isPending },
            )
            else -> items += TimelineItem.Footer("${task.id}:footer", task)
        }
    }
    return items
}
