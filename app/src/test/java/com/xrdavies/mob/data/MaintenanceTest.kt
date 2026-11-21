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
    fun agentOptionsAndSectionsDecode() {
        // An ACP agent without efforts, options of each kind, and config sections holding args and env.
        val v = ApiClient.gson.fromJson(
            """{"settings":{"rev":1,"defaultAgent":"codex","agents":{}},"agents":{"opencode":{"label":"OpenCode","efforts":null,"agentModels":["opencode/big-pickle"]}},"installed":[{"name":"opencode","label":"OpenCode","state":"ready","enabled":true,"protocol":"acp","options":[{"key":"bin","label":"Program","type":"path","value":"opencode","restart":true},{"key":"mode","label":"Mode","type":"choice","choices":[{"value":"exec","label":"exec"}],"value":"exec","overridden":true},{"key":"fast","label":"Fast","type":"bool","value":true},{"key":"n","label":"N","type":"number","value":3}]}]}""",
            SettingsInfo::class.java,
        )
        assertNull(v.agents?.get("opencode")?.efforts)
        assertEquals(listOf("opencode/big-pickle"), v.agents?.get("opencode")?.agentModels)
        val o = v.installed!!.first().options!!
        assertEquals("acp", v.installed!!.first().protocol)
        assertEquals(listOf("opencode", "exec", "true", "3"), o.map { AgentOption.text(it.value) })
        assertEquals(true, o[1].overridden)
        assertEquals("exec", o[1].choices!!.first().value)
        val c = ApiClient.gson.fromJson(
            """{"rev":"a","values":{"agents":{"opencode":{"bin":"/x/opencode"},"mine":{"protocol":"acp","args":["--acp"],"env":{"A":"1"}}}},"effective":{},"editable":["agents.opencode.bin"],"overridden":[],"pendingRestart":true}""",
            ConfigView::class.java,
        )
        assertEquals("/x/opencode", c.values?.agents?.get("opencode")?.get("bin"))
        assertEquals("", AgentOption.text(c.values?.agents?.get("mine")?.get("args")))
        assertTrue(c.canEdit("agents.opencode.bin"))
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
