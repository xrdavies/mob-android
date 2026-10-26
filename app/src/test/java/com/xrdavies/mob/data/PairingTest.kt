package com.xrdavies.mob.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PairingTest {
    private fun parseFails(text: String): String = try {
        PairLink.parse(text)
        fail("parsed $text")
        ""
    } catch (e: PairException) {
        e.message.orEmpty()
    }

    @Test
    fun parsesLinksInAnyParamOrder() {
        val l = PairLink.parse("  https://office.example.com/pair#invite=INV&serverId=SRV&v=1 \n")
        assertEquals(PairLink("https://office.example.com", "INV", "SRV"), l)
        assertFalse(l.insecure)
        assertEquals(l, PairLink.parse("https://office.example.com/pair#v=1&serverId=SRV&invite=INV"))
    }

    @Test
    fun keepsPortHttpAndIpv6() {
        val l = PairLink.parse("http://192.168.1.20:8080/pair#v=1&serverId=S&invite=I")
        assertEquals("http://192.168.1.20:8080", l.origin)
        assertTrue(l.insecure)
        assertEquals("http://[fd7a:115c::1]:8080", PairLink.parse("http://[fd7a:115c::1]:8080/pair#v=1&serverId=S&invite=I").origin)
        assertEquals("https://office.example.com", PairLink.parse("HTTPS://Office.Example.com/pair#v=1&serverId=S&invite=I").origin)
    }

    @Test
    fun decodesFragmentValues() {
        assertEquals("a+b/c", PairLink.parse("http://h/pair#v=1&serverId=S&invite=a%2Bb%2Fc").invite)
    }

    @Test
    fun rejectsBadLinks() {
        assertTrue(parseFails("http://h/pair#serverId=S&invite=I").contains("version"))
        assertTrue(parseFails("http://h/pair#v=2&serverId=S&invite=I").contains("newer"))
        assertTrue(parseFails("http://h/pair#v=1&serverId=S").contains("invite"))
        assertTrue(parseFails("http://h/pair#v=1&invite=I").contains("server ID"))
        assertTrue(parseFails("http://h/pair").contains("no invite"))
        assertTrue(parseFails("http://h/pairing#v=1&serverId=S&invite=I").contains("/pair"))
        assertTrue(parseFails("http://h/api/pair#v=1&serverId=S&invite=I").contains("/pair"))
        assertTrue(parseFails("http://h/pair?x=1#v=1&serverId=S&invite=I").contains("query"))
        assertTrue(parseFails("http://u:p@h/pair#v=1&serverId=S&invite=I").contains("user name"))
        assertTrue(parseFails("ftp://h/pair#v=1&serverId=S&invite=I").contains("http"))
        assertTrue(parseFails("mob://pair#v=1&serverId=S&invite=I").contains("http"))
        assertTrue(parseFails("h/pair#v=1&serverId=S&invite=I").contains("http"))
        assertTrue(parseFails("http://h/pair#v=1&v=1&serverId=S&invite=I").contains("repeats"))
        assertTrue(parseFails("").isNotEmpty())
    }

    @Test
    fun comparesOrigins() {
        val l = PairLink.parse("http://192.168.1.20:8080/pair#v=1&serverId=S&invite=I")
        assertTrue(l.sameOrigin("192.168.1.20:8080"))
        assertTrue(l.sameOrigin("http://192.168.1.20:8080/"))
        assertFalse(l.sameOrigin("https://192.168.1.20:8080"))
        assertFalse(l.sameOrigin("http://192.168.1.20:9090"))
        assertTrue(PairLink.parse("https://h:443/pair#v=1&serverId=S&invite=I").sameOrigin("https://h"))
    }

    @Test
    fun checksDeviceNames() {
        assertNull(deviceNameError(" Pixel 9 "))
        assertTrue(deviceNameError("  ") != null)
        assertTrue(deviceNameError("a\nb") != null)
        assertTrue(deviceNameError("é".repeat(65)) != null)
        assertNull(deviceNameError("é".repeat(64)))
    }

    private val server = MockWebServer().apply { start() }

    @After
    fun stop() = server.shutdown()

    private fun link(serverId: String = "SRV") = PairLink(server.url("/").toString().trimEnd('/'), "INV", serverId)

    private fun pairFails(code: Int, body: String = ""): String {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
        return try {
            runBlocking { pair(link(), "Pixel", OkHttpClient()) }
            fail("paired with $code")
            ""
        } catch (e: PairException) {
            e.message.orEmpty()
        }
    }

    @Test
    fun redeemsInvite() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"serverId":"SRV","clientId":"C","token":"T"}"""))
        val r = runBlocking { pair(link(), "  Pixel 9 ", OkHttpClient()) }
        assertEquals(PairResult("SRV", "C", "T"), r)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/pair", req.path)
        assertNull(req.getHeader("Authorization"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("""{"invite":"INV","name":"Pixel 9"}""", req.body.readUtf8())
    }

    @Test
    fun mapsStatusCodes() {
        assertTrue(pairFails(401).contains("mobd link"))
        assertTrue(pairFails(400, """{"error":"invalid name"}""").contains("invalid name"))
        assertTrue(pairFails(503).contains("could not store"))
        assertTrue(pairFails(500, """{"error":"boom"}""").contains("boom"))
        assertTrue(pairFails(404, """{"error":"pairing is off","code":"pairing_closed"}""").contains("mobd link"))
        assertTrue(pairFails(404).contains("Update mobd"))
    }

    @Test
    fun doesNotFollowRedirects() {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/elsewhere"))
        assertTrue(pairFails(307).contains("redirect"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun rejectsOtherServerId() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"serverId":"OTHER","clientId":"C","token":"T"}"""))
        try {
            runBlocking { pair(link(), "Pixel", OkHttpClient()) }
            fail("accepted another server")
        } catch (e: PairException) {
            assertTrue(e.message!!.contains("different server ID"))
        }
    }
}
