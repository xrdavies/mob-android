package com.xrdavies.mob.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceTest {
    @Test
    fun configDecodesTheKeysThePhoneUses() {
        val c = ApiClient.gson.fromJson(
            """{"rev":"a1","values":{"runners":["codex"]},"effective":{"runners":["codex","claude"],"workdir":"/w"},"editable":["runners","workdir"],"overridden":["workdir"],"pendingRestart":true,"lastRejected":{"reason":"did not answer"}}""",
            ConfigView::class.java,
        )
        assertEquals(listOf("codex", "claude"), c.effective?.runners)
        assertTrue(c.canEdit("runners"))
        assertFalse("overridden keys are read-only", c.canEdit("workdir"))
        assertFalse(c.canEdit("listen"))
        assertEquals("did not answer", c.lastRejected?.reason)
    }

    @Test
    fun operationText() {
        assertEquals("Restarting…", Maintenance.text(MaintenanceOp("1", "restart", "running", step = "restarting")))
        assertEquals("Installing…", Maintenance.text(MaintenanceOp("1", "upgrade", "running", step = "swapping")))
        assertEquals("Restarted", Maintenance.text(MaintenanceOp("1", "restart", "done")))
        assertEquals("Updated to 0.5.3", Maintenance.text(MaintenanceOp("1", "upgrade", "done", version = "0.5.3")))
        assertEquals("Went back to the previous version: did not start", Maintenance.text(MaintenanceOp("1", "upgrade", "rolled-back", error = "did not start")))
        val op = ApiClient.gson.fromJson("""{"id":"x","action":"restart","state":"failed","error":"needs attention","startedAt":"2026"}""", MaintenanceOp::class.java)
        assertTrue(op.failed)
        assertEquals("needs attention", Maintenance.text(op))
    }

    @Test
    fun runnersToggleKeepsOneAgentOn() {
        assertEquals(listOf("codex", "claude"), Maintenance.runners(listOf("codex"), "claude", on = true))
        assertEquals(listOf("claude"), Maintenance.runners(listOf("codex", "claude"), "codex", on = false))
        assertEquals(listOf("codex"), Maintenance.runners(listOf("codex"), "codex", on = true))
        assertNull("the last agent stays on", Maintenance.runners(listOf("codex"), "codex", on = false))
    }

    @Test
    fun busyCountsRunningAndQueued() {
        assertEquals(0, Maintenance.busy(null))
        assertEquals("1 task running", Maintenance.tasksText(1))
        assertEquals("2 tasks running", Maintenance.tasksText(2))
    }
}
