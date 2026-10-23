package com.xrdavies.mob.viewmodel

import com.xrdavies.mob.data.ApiClient
import com.xrdavies.mob.data.SessionDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatDetailTest {
    /** GET /sessions/{id} as mobd answers it: one message and one step per turn. */
    private fun detail(turns: List<Triple<String, String, String>>, rev: Long?, full: Boolean?): SessionDetail {
        val tasks = turns.joinToString(",") { (id, status) -> """{"id":"$id","sessionId":"s","status":"$status"}""" }
        val messages = turns.joinToString(",") { (id, _, text) -> """{"id":"$id:a","taskId":"$id","role":"assistant","content":"$text"}""" }
        val steps = turns.joinToString(",") { (id, _, text) -> """{"id":"$id:1","taskId":"$id","kind":"tool","status":"completed","title":"$text"}""" }
        var json = """{"session":{"id":"s","messages":[$messages]},"tasks":[$tasks],"activities":[$steps],"questions":[]"""
        if (rev != null) json += ""","rev":$rev"""
        if (full != null) json += ""","full":$full"""
        return ApiClient.gson.fromJson("$json}", SessionDetail::class.java)
    }

    @Test
    fun aPartialAnswerReplacesOnlyTheTurnsItCarries() {
        val base = ChatDetail.merged(detail(listOf(Triple("t1", "succeeded", "one"), Triple("t2", "running", "two…")), 5, true), null)
        assertEquals(5L, base.rev)
        val d = ChatDetail.merged(detail(listOf(Triple("t2", "succeeded", "two"), Triple("t3", "running", "three")), 9, false), base)
        assertEquals(9L, d.rev)
        assertEquals(listOf("t1", "t2", "t3"), d.tasks.map { it.id })
        assertEquals(listOf("succeeded", "succeeded", "running"), d.tasks.map { it.status })
        assertEquals(listOf("one", "two", "three"), d.session.messages?.map { it.content })
        assertEquals(listOf("one", "two", "three"), d.activities.map { it.title })

        // An older turn that changed keeps its place.
        val older = ChatDetail.merged(detail(listOf(Triple("t1", "succeeded", "one, edited")), 10, false), d)
        assertEquals(listOf("one, edited", "two", "three"), older.session.messages?.map { it.content })
    }

    @Test
    fun aFullAnswerOrAnOlderMobdReplacesEverything() {
        val base = ChatDetail.merged(detail(listOf(Triple("t1", "succeeded", "one"), Triple("t2", "succeeded", "two")), 5, true), null)
        assertEquals(listOf("t2"), ChatDetail.merged(detail(listOf(Triple("t2", "succeeded", "two")), 3, true), base).tasks.map { it.id })
        val legacy = ChatDetail.merged(detail(listOf(Triple("t1", "succeeded", "one")), null, null), base)
        assertEquals(listOf("t1"), legacy.tasks.map { it.id })
        assertNull(legacy.rev)
    }
}
