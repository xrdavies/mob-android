package com.xrdavies.mob.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnSettingsTest {
    @Test
    fun openChatShowsItsConversationSettingsAndNewChatItsDraft() {
        val session = ApiClient.gson.fromJson("""{"id":"s","model":"gpt-5.4","effort":"high"}""", Session::class.java)
        assertEquals(TurnChoice("gpt-5.4", "high"), TurnSettings.shown(true, session, "x", null))
        val plain = ApiClient.gson.fromJson("""{"id":"s"}""", Session::class.java)
        assertEquals(TurnChoice(), TurnSettings.shown(true, plain, "x", null))
        assertEquals(TurnChoice("", "low"), TurnSettings.shown(true, null, null, "low"))
        assertEquals(TurnChoice("o4", ""), TurnSettings.shown(false, session, "o4", null))
        val shown = TurnChoice("opus", "high")
        assertEquals(null to null, TurnSettings.sent(true, false, shown, listOf("high")))
        assertEquals("opus" to "high", TurnSettings.sent(true, true, shown, listOf("high")))
        assertNull(TurnSettings.sent(false, false, TurnChoice("", "max"), listOf("high")).second)
        assertEquals(listOf("a", "b"), TurnSettings.menuModels(listOf("a"), "b"))
    }

    @Test
    fun settingsEditsDropEmptyValuesAndBypassNeedsConfirmation() {
        var s = MachineSettings(rev = 2)
        s = TurnSettings.with(s, "codex") { it.copy(models = listOf("gpt-6-astra"), defaultEffort = "high") }
        assertEquals(AgentSettings(models = listOf("gpt-6-astra"), defaultEffort = "high"), s.agents?.get("codex"))
        s = TurnSettings.with(s, "codex") { it.copy(models = emptyList(), defaultEffort = "") }
        assertNull(s.agents?.get("codex"))
        assertEquals(listOf("a", "b"), TurnSettings.addModel(listOf("a"), " b "))
        assertNull(TurnSettings.addModel(emptyList(), "has space"))
        val info = ApiClient.gson.fromJson(
            """{"settings":{"rev":2},"defaultAgent":"codex","agents":{"codex":{"permissions":"approve","permissionsFrom":"app",
               "fallback":{"permissions":"bypass","permissionsFrom":"config"}}}}""", SettingsInfo::class.java,
        )
        assertEquals(listOf("codex"), TurnSettings.newlyUnguarded(info, MachineSettings(rev = 2)))
        assertEquals(emptyList<String>(), TurnSettings.newlyUnguarded(info, TurnSettings.with(MachineSettings(rev = 2), "codex") { it.copy(permissions = "approve") }))
    }
}
