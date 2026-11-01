package com.xrdavies.mob.stream

import org.junit.Assert.assertEquals
import org.junit.Test

/** A Mob relay's STUN/TURN servers from GET /stream/ice. */
class IceTest {
    @Test
    fun parsesServersAndIgnoresMalformedOnes() {
        val body = """{"iceServers":[{"urls":["stun:relay.example.com:3478"]},{"urls":["turn:relay.example.com:3478"],"username":"1800043200:office","credential":"c2VjcmV0"},{"urls":[]},{"nope":1}],"ttlSeconds":43200}"""
        assertEquals(
            listOf(
                IceServerSpec(listOf("stun:relay.example.com:3478")),
                IceServerSpec(listOf("turn:relay.example.com:3478"), "1800043200:office", "c2VjcmV0"),
            ),
            parseIce(body),
        )
        assertEquals(emptyList<IceServerSpec>(), parseIce("""{"iceServers":[]}"""))
        assertEquals(emptyList<IceServerSpec>(), parseIce("not json"))
    }
}
