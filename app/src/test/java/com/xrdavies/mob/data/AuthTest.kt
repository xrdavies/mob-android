package com.xrdavies.mob.data

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AuthTest {
    private val server = MockWebServer().apply { start() }

    @After
    fun stop() = server.shutdown()

    private val health = """{"ok":true}"""

    @Test
    fun sendsTokenToMobdOnly() {
        server.enqueue(MockResponse().setBody(health))
        val client = ApiClient(server.url("/").toString(), "T0K")
        runBlocking { client.api.getHealth() }
        assertEquals("Bearer T0K", server.takeRequest().getHeader("Authorization"))

        // Same server under another host name is another origin: no token.
        server.enqueue(MockResponse().setBody("x"))
        val other = server.url("/img").newBuilder().host("127.0.0.1").build()
        client.okHttpClient.newCall(Request.Builder().url(other).build()).execute().close()
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun noTokenNoHeader() {
        server.enqueue(MockResponse().setBody(health))
        runBlocking { ApiClient(server.url("/").toString(), null).api.getHealth() }
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun unauthorizedSurfaces() {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer realm=\"mobd\""))
        val client = ApiClient(server.url("/").toString(), "old")
        try {
            runBlocking { client.api.getHealth() }
            fail("no error")
        } catch (e: Exception) {
            assertTrue(e.isUnauthorized())
            assertEquals(NEEDS_PAIRING, e.readable())
        }
    }

    @Test
    fun unauthorizedStream() {
        server.enqueue(MockResponse().setResponseCode(401))
        val client = ApiClient(server.url("/").toString(), "old")
        try {
            runBlocking { client.streamClient.sse(client.url("events")).collect {} }
            fail("no error")
        } catch (e: Exception) {
            assertTrue(e.isUnauthorized())
        }
        assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun streamFailureKeepsStatus() {
        server.enqueue(MockResponse().setResponseCode(500))
        val client = ApiClient(server.url("/").toString(), "t")
        try {
            runBlocking { client.streamClient.sse(client.url("events")).collect {} }
            fail("no error")
        } catch (e: HttpStatusException) {
            assertEquals(500, e.code)
        }
    }
}
