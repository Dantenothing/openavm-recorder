package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanEndpointPolicyTest {
    @Test
    fun hotspotCandidatesComeBeforeOtherPrivateInterfaces() {
        val ordered = LanEndpointPolicy.ordered(
            listOf(
                LanEndpointCandidate("10.42.0.7", "tun0"),
                LanEndpointCandidate("192.168.43.1", "wlan0"),
                LanEndpointCandidate("192.0.0.2", "ap0"),
            ),
        )

        assertEquals(
            listOf("192.0.0.2", "192.168.43.1", "10.42.0.7"),
            ordered.map { it.ipv4 },
        )
    }

    @Test
    fun duplicatesAndNonLanAddressesAreRemoved() {
        val ordered = LanEndpointPolicy.ordered(
            listOf(
                LanEndpointCandidate("192.168.1.20", "wlan0"),
                LanEndpointCandidate("192.168.1.20", "ap0"),
                LanEndpointCandidate("127.0.0.1", "lo"),
                LanEndpointCandidate("8.8.8.8", "rmnet0"),
            ),
        )

        assertEquals(listOf("192.168.1.20"), ordered.map { it.ipv4 })
        assertTrue(LanEndpointPolicy.isLocalLanIpv4("172.20.10.1"))
        assertFalse(LanEndpointPolicy.isLocalLanIpv4("169.254.1.2"))
    }
}
