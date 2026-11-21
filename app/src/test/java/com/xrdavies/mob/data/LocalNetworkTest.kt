package com.xrdavies.mob.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalNetworkTest {
    @After
    fun reset() { LocalNetwork.state.value = false }

    @Test
    fun aDeniedLocalNetworkIsNamedInsteadOfATimeout() {
        assertEquals("mobd did not answer in time", java.net.SocketTimeoutException().readable())
        LocalNetwork.state.value = true
        assertEquals(LocalNetwork.MESSAGE, java.net.SocketTimeoutException().readable())
        assertEquals(LocalNetwork.MESSAGE, java.net.ConnectException().readable())
        // A name that does not resolve is not the permission.
        assertEquals("Cannot reach mobd", java.net.UnknownHostException().readable())
    }
}
