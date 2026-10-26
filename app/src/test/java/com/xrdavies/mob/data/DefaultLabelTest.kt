package com.xrdavies.mob.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultLabelTest {
    @Test
    fun defaultChoiceNamesTheAgentsDefault() {
        val r = ApiClient.gson.fromJson("""{"name":"codex","defaultModel":"gpt-6-astra","defaultEffort":"xhigh"}""", Runner::class.java)
        assertEquals("Default (xhigh)", defaultLabel("effort", r.defaultEffort))
        assertEquals("Default (gpt-6-astra)", defaultLabel("model", r.defaultModel))
        val old = ApiClient.gson.fromJson("""{"name":"claude"}""", Runner::class.java)
        assertEquals("Default effort", defaultLabel("effort", old.defaultEffort))
    }
}
