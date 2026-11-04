package com.xrdavies.mob.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollabTest {
    private fun session(id: String, handle: String? = null, title: String? = null, archived: Boolean? = null) =
        Session(id, title, "codex", null, null, null, null, null, archived, null, null, null, null, null, handle)

    @Test
    fun handleRulesMatchMobd() {
        val valid = listOf("api", "order-service", "x1", "abc" + "x".repeat(29))
        val invalid = listOf("a", "Api", "2api", "abc123", "deadbeef", "all", "mobd", "a".repeat(33))
        valid.forEach { assertTrue("$it should be valid", validHandle(it)) }
        invalid.forEach { assertFalse("$it should be invalid", validHandle(it)) }
    }

    @Test
    fun suggestedHandleFollowsTheRules() {
        assertEquals("order-service-refactor", suggestHandle("Order service refactor"))
        assertEquals("ui", suggestHandle("UI"))
        val hex = suggestHandle("cafe deadbeef")
        assertEquals("cafe-deadbeef", hex)
        assertTrue(validHandle(hex!!))
        assertNull(suggestHandle(null))
        assertNull(suggestHandle("!!"))
        val s = suggestHandle("Fix the login bug on the checkout page when the user is logged out")!!
        assertTrue(validHandle(s))
        assertTrue(s.length <= 32)
    }

    @Test
    fun typedMentionsResolveByHandleAndShortId() {
        val api = session("9f8e7d6c5b4a0011", handle = "api", title = "Order API")
        val ui = session("aaaa1111bbbb2222", handle = "ui", title = "UI")
        val old = session("cccc3333dddd4444", title = "Old thread", archived = true)
        val all = listOf(api, ui, old)

        // @ui is the current conversation, so it is skipped; @aaaa1 is a unique short ID of ui's
        // neighbour only when it differs, so use the handle-only case for api.
        val (ids, problems) = resolveMentions("ask @api and @ui", all, ui.id, emptySet())
        assertEquals(listOf(api.id), ids)
        assertTrue(problems.isEmpty())
        val (byShort, _) = resolveMentions("ask @9f8e7d", all, ui.id, emptySet())
        assertEquals(listOf(api.id), byShort)
    }

    @Test
    fun unknownAndArchivedMentionsBlockTheSend() {
        val api = session("9f8e7d6c5b4a0011", handle = "api")
        val old = session("cccc3333dddd4444", handle = "old", archived = true)
        val (ids, problems) = resolveMentions("@nobody @old @api", listOf(api, old), null, emptySet())
        assertEquals(listOf(api.id), ids)
        assertEquals(2, problems.size)
        assertTrue(problems[0] is MentionProblem.Unknown)
        assertEquals("nobody", problems[0].handle)
        assertTrue(problems[1] is MentionProblem.Archived)
    }

    @Test
    fun pickedAgentsWithoutHandlesAreNotUnknown() {
        val plain = session("9f8e7d6c5b4a0011", title = "Scratch")
        val (ids, problems) = resolveMentions("ask @9f8e7d6c", listOf(plain), null, setOf(plain.id))
        assertEquals(listOf(plain.id), ids)
        assertTrue(problems.isEmpty())
    }

    @Test
    fun mentionOfTheCurrentConversationIsSkipped() {
        val api = session("9f8e7d6c5b4a0011", handle = "api")
        val (ids, problems) = resolveMentions("@api talking to myself", listOf(api), api.id, emptySet())
        assertTrue(ids.isEmpty())
        assertTrue(problems.isEmpty())
    }

    @Test
    fun peerSenderIsReadFromTheHeader() {
        val prompt = "[Mob collaboration · from @api (a1b2c3) · request r-7f3k · exchange x-91qa]\nThis message comes from another agent"
        assertEquals("@api (a1b2c3)", peerSender(prompt))
        assertNull(peerSender("Just a user message"))
    }

    @Test
    fun requestStatusDecodesFromMobdJson() {
        val r = ApiClient.gson.fromJson(
            """{"id":"r-1","from":"s1","to":"s2","status":"interrupted","thread":[{"from":"s2","kind":"clarification","text":"v1 or v2?"}]}""",
            CollabRequest::class.java,
        )
        assertTrue(r.ended)
        assertTrue(r.needsUser)
        assertEquals("v1 or v2?", r.thread!!.single().text)
        assertEquals("Interrupted · needs you", requestStatusText(r.status))
        val open = ApiClient.gson.fromJson("""{"id":"r-2","from":"s1","to":"s2","status":"in_progress"}""", CollabRequest::class.java)
        assertFalse(open.ended)
        assertFalse(open.needsUser)
    }

    @Test
    fun mentionQueryFindsTheWordBeingTyped() {
        assertEquals("ap", mentionQuery("ask @ap"))
        assertEquals("", mentionQuery("ask @"))
        assertNull(mentionQuery("mail me@x.com"))
        assertNull(mentionQuery("done"))
        assertEquals("ask @api ", insertMention("ask @ap", "api"))
    }
}
