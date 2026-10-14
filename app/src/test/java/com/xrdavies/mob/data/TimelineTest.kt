package com.xrdavies.mob.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    private fun task(id: String, status: String) = Task(
        id, "s", "codex", "prompt $id", null, null, status, 0, null, "2026-10-14T00:00:00Z",
        null, null, null, null, null, null, null,
    )
    private fun msg(id: String, task: String, role: String, at: String) = Message(id, task, role, "text $id", at)
    private fun step(id: String, task: String, at: String, tool: String? = null) = Activity(
        id, task, "tool", "completed", "Step $id", null, tool, null, null, null, null, null, null, null, null, null, at, at,
    )

    @Test
    fun ordersRepliesAndStepsAndGroupsLongRuns() {
        val items = buildTimeline(
            listOf(task("t1", Status.SUCCEEDED)),
            listOf(
                msg("t1:user", "t1", "user", "2026-10-14T00:00:00Z"),
                msg("t1:a", "t1", "assistant", "2026-10-14T00:00:05Z"),
            ),
            listOf(
                step("1", "t1", "2026-10-14T00:00:01Z"), step("2", "t1", "2026-10-14T00:00:02Z"),
                step("3", "t1", "2026-10-14T00:00:03Z"), step("ask", "t1", "2026-10-14T00:00:04Z", tool = "ask_user"),
                step("4", "t1", "2026-10-14T00:00:06Z"),
            ),
            emptyList(),
        )
        assertEquals(listOf("User", "Group", "Assistant", "Steps", "Footer"), items.map { it.javaClass.simpleName })
        assertEquals(3, (items[1] as TimelineItem.Group).activities.size)
    }

    @Test
    fun runningTurnWithoutMessagesUsesPromptAndShowsWaiting() {
        val q = Question("q", "t1", "approval", "Allow?", null, null, false, "pending", null, null, "2026-10-14T00:00:01Z")
        val items = buildTimeline(listOf(task("t1", Status.RUNNING)), emptyList(), emptyList(), listOf(q))
        assertEquals("prompt t1", (items[0] as TimelineItem.User).message.content)
        assertTrue(items[1] is TimelineItem.Ask)
        assertTrue((items[2] as TimelineItem.Working).waiting)
    }
}
