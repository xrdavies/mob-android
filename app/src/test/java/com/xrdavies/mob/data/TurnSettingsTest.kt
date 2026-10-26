package com.xrdavies.mob.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnSettingsTest {
    @Test
    fun openChatShowsItsConversationSettingsAndNewChatItsDraft() {
        val session = ApiClient.gson.fromJson("""{"id":"s","model":"gpt-5.4","effort":"high"}""", Session::class.java)
        assertEquals(TurnChoice("gpt-5.4", "high"), TurnSettings.shown(session, "x", null))
        val plain = ApiClient.gson.fromJson("""{"id":"s"}""", Session::class.java)
        assertEquals(TurnChoice(), TurnSettings.shown(plain, "x", null))
        assertEquals(TurnChoice("", "low"), TurnSettings.shown(null, null, "low"))
        assertEquals(listOf("a", "b"), TurnSettings.menuModels(listOf("a"), "b"))
    }

    @Test
    fun settingsFileEditsAndTurningApprovalOffNeedsConfirmation() {
        val info = ApiClient.gson.fromJson(
            """{"settings":{"rev":2,"defaultAgent":"codex","agents":{
                 "codex":{"models":[],"defaultModel":"","defaultEffort":"","permissions":"approve"},
                 "claude":{"models":["opus"],"defaultModel":"","defaultEffort":"","permissions":"bypass"}}},
               "agents":{"codex":{"label":"Codex","efforts":["low","high"],"agentDefaultEffort":"xhigh"}}}""", SettingsInfo::class.java,
        )
        assertEquals("xhigh", info.agents?.get("codex")?.agentDefaultEffort)
        val next = TurnSettings.with(info.settings, "codex") { it.copy(models = listOf("gpt-6-astra"), defaultEffort = "high") }
        assertEquals(AgentSettings(models = listOf("gpt-6-astra"), defaultEffort = "high", permissions = "approve"), next.agents["codex"])
        assertEquals(listOf("a", "b"), TurnSettings.addModel(listOf("a"), " b "))
        assertNull(TurnSettings.addModel(emptyList(), "has space"))
        assertEquals(listOf("codex"), TurnSettings.newlyUnguarded(info.settings, TurnSettings.with(info.settings, "codex") { it.copy(permissions = "bypass") }))
        assertEquals(emptyList<String>(), TurnSettings.newlyUnguarded(info.settings, next))
    }
}
