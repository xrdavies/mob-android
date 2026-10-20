package com.xrdavies.mob.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopsTest {
    @Test
    fun healthSessionDesktops() {
        val h = Health(true, listOf("sessionDesktops"), null, null, null, null, null)
        assertTrue(h.sessionDesktops)
        assertFalse(Health(true, listOf("stream"), null, null, null, null, null).sessionDesktops)
        assertFalse(Health(true, null, null, null, null, null, null).sessionDesktops)
    }

    @Test
    fun desktopInfoOpen() {
        val running = DesktopInfo("s1", "Chat", ":101", 1280, 800, 1.0, DesktopState.RUNNING, null, 512L shl 20, 1L shl 30, null, null, null, 0)
        assertTrue(running.open)
        assertFalse(running.asleep)
        val asleep = running.copy(state = DesktopState.ASLEEP)
        assertTrue(asleep.open)
        assertTrue(asleep.asleep)
        assertFalse(running.copy(state = DesktopState.CLOSED).open)
        assertFalse(running.copy(state = DesktopState.FAILED).open)
    }

    @Test
    fun appliesDesktopEvents() {
        val d1 = DesktopInfo("s1", "A", null, null, null, null, DesktopState.RUNNING, null, null, null, null, null, null, null)
        val d2 = d1.copy(sessionId = "s2", title = "B")
        var map = emptyMap<String, DesktopInfo>()
        map = map.apply(d1)
        assertEquals(mapOf("s1" to d1), map)
        map = map.apply(d2)
        assertEquals(mapOf("s1" to d1, "s2" to d2), map)
        map = map.apply(d1.copy(state = DesktopState.ASLEEP))
        assertEquals(DesktopState.ASLEEP, map["s1"]?.state)
        map = map.apply(d2.copy(state = DesktopState.CLOSED))
        assertFalse(map.containsKey("s2"))
        assertTrue(map.containsKey("s1"))
    }

    @Test
    fun sortsDesktopsByMemory() {
        val d1 = DesktopInfo("s1", "A", null, null, null, null, null, null, 100L shl 20, null, null, null, null, null)
        val d2 = d1.copy(sessionId = "s2", title = "B", memoryBytes = 200L shl 20)
        val d3 = d1.copy(sessionId = "s3", title = "C", memoryBytes = 50L shl 20)
        val map = mapOf("s1" to d1, "s2" to d2, "s3" to d3)
        val sorted = map.byMemory()
        assertEquals(listOf("s2", "s1", "s3"), sorted.map { it.sessionId })
    }

    @Test
    fun queueReasonText() {
        assertEquals("Waiting for a free slot", queueReasonText("slots"))
        assertEquals("Waiting: the desktop limit is reached", queueReasonText("desktops"))
        assertEquals("Waiting: the office computer is low on memory", queueReasonText("memory"))
        assertEquals("Waiting: the office computer is low on disk space", queueReasonText("disk"))
        assertEquals("Starting the desktop", queueReasonText("desktopStarting"))
        assertNull(queueReasonText("unknown"))
        assertNull(queueReasonText(null))
    }

    @Test
    fun resourceWarningMemory() {
        val low = MemoryInfo(Level.LOW, 1L shl 30, 8L shl 30, 0.0, null, null)
        val critical = MemoryInfo(Level.CRITICAL, 512L shl 20, 8L shl 30, 0.0, null, null)
        val ok = MemoryInfo(Level.OK, 4L shl 30, 8L shl 30, 0.0, null, null)
        assertNull(resourceWarning(ok, null))
        val w = resourceWarning(low, null)!!
        assertEquals("low/${Level.OK}", w.key)
        assertEquals(1, w.lines.size)
        assertTrue(w.lines[0].contains("low on memory"))
        assertFalse(w.lines[0].contains("New conversations wait"))
        val c = resourceWarning(critical, null)!!
        assertEquals("critical/${Level.OK}", c.key)
        assertTrue(c.lines[0].contains("New conversations wait"))
    }

    @Test
    fun resourceWarningDisk() {
        val low = DiskInfo(Level.LOW, 5L shl 30, 100L shl 30, null)
        val ok = DiskInfo(Level.OK, 50L shl 30, 100L shl 30, null)
        val w = resourceWarning(null, low)!!
        assertEquals("${Level.OK}/low", w.key)
        assertTrue(w.lines[0].contains("low on disk space"))
        assertNull(resourceWarning(null, ok))
    }

    @Test
    fun resourceWarningBoth() {
        val mem = MemoryInfo(Level.LOW, 1L shl 30, 8L shl 30, 0.0, null, null)
        val disk = DiskInfo(Level.CRITICAL, 2L shl 30, 100L shl 30, null)
        val w = resourceWarning(mem, disk)!!
        assertEquals("low/critical", w.key)
        assertEquals(2, w.lines.size)
        assertTrue(w.lines[0].contains("memory"))
        assertTrue(w.lines[1].contains("disk"))
    }

    @Test
    fun formatsBytes() {
        assertEquals("0 KB", bytes(0))
        assertEquals("1 KB", bytes(1024))
        assertEquals("100 KB", bytes(100L shl 10))
        assertEquals("1 MB", bytes(1L shl 20))
        assertEquals("512 MB", bytes(512L shl 20))
        assertEquals("1.0 GB", bytes(1L shl 30))
        assertEquals("2.5 GB", bytes((2.5 * (1L shl 30)).toLong()))
    }

    @Test
    fun parsesApiError() {
        val e1 = apiError("""{"error":"denied","code":"forbidden"}""", 403)
        assertEquals("forbidden", e1.code)
        assertEquals("denied", e1.message)
        val e2 = apiError("""{"error":"not found"}""", 404)
        assertNull(e2.code)
        assertEquals("not found", e2.message)
        val e3 = apiError("plain text", 500)
        assertNull(e3.code)
        assertEquals("HTTP 500", e3.message)
        val e4 = apiError(null, 503)
        assertEquals("HTTP 503", e4.message)
    }

    @Test
    fun seedErrorMessages() {
        assertEquals("Close the browser in this conversation first", seedErrorText(ApiError("browser_running", "denied")))
        assertEquals("denied", seedErrorText(ApiError(null, "denied")))
        assertEquals("denied", seedErrorText(ApiError("other", "denied")))
    }

    @Test
    fun parsesDesktopEvents() {
        val desktop = parseDesktopEvent("desktop", """{"sessionId":"s1","state":"running","memoryBytes":1000000}""")
        assertTrue(desktop is DesktopEvent.Desktop)
        assertEquals("s1", (desktop as DesktopEvent.Desktop).info.sessionId)
        assertEquals(DesktopState.RUNNING, desktop.info.state)
        val memory = parseDesktopEvent("memory", """{"level":"low","availableBytes":1000000000,"totalBytes":8000000000}""")
        assertTrue(memory is DesktopEvent.Memory)
        assertEquals(Level.LOW, (memory as DesktopEvent.Memory).info.level)
        val disk = parseDesktopEvent("disk", """{"level":"ok","freeBytes":50000000000,"totalBytes":100000000000}""")
        assertTrue(disk is DesktopEvent.Disk)
        assertEquals(Level.OK, (disk as DesktopEvent.Disk).info.level)
        assertNull(parseDesktopEvent("session", "{}"))
        assertNull(parseDesktopEvent("desktop", "invalid json"))
        assertNull(parseDesktopEvent("desktop", """{"state":"running"}""")) // missing sessionId
    }
}
