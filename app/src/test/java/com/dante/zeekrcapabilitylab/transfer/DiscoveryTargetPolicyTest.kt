package com.dante.zeekrcapabilitylab.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryTargetPolicyTest {
    @Test
    fun prioritizesHotspotGatewaysThenDirectedAndLimitedBroadcasts() {
        assertEquals(
            listOf("192.168.5.1", "192.168.5.255", "255.255.255.255"),
            DiscoveryTargetPolicy.targets(
                gateways = listOf("192.168.5.1", "192.168.5.1", "::1"),
                directedBroadcasts = listOf("192.168.5.255", "255.255.255.255"),
            ),
        )
    }

    @Test
    fun calculatesDirectedIpv4BroadcastFromPrefix() {
        assertEquals("192.0.0.255", DiscoveryTargetPolicy.directedBroadcast("192.0.0.42", 24))
        assertEquals("10.0.5.255", DiscoveryTargetPolicy.directedBroadcast("10.0.4.2", 23))
        assertNull(DiscoveryTargetPolicy.directedBroadcast("10.0.4.2", 31))
        assertNull(DiscoveryTargetPolicy.directedBroadcast("not-an-ip", 24))
    }
}
