package com.isaque.signalplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolContractTest {
    @Test fun documentsEveryRequiredProtocol() {
        val examples = listOf("srt://host:9000", "https://host/live.m3u8", "rtmp://host/live", "rtp://host:5004", "udp://host:5000")
        assertTrue(examples.all { it.substringBefore(":") in setOf("srt", "https", "rtmp", "rtp", "udp") })
    }

    @Test fun acceptsRequiredAddressForms() {
        val addresses = listOf(
            "srt://0.0.0.0:9000?mode=listener",
            "https://host/live.m3u8",
            "rtmp://host/live/channel",
            "rtp://@:5004",
            "udp://@:5000"
        )
        addresses.forEach { assertTrue("Should accept $it", StreamAddress.validate(it).isSuccess) }
    }

    @Test fun rejectsBlankUnsupportedAndIncompleteAddresses() {
        val addresses = listOf("", "ftp://host/file", "udp:", "srt://")
        addresses.forEach { assertTrue("Should reject $it", StreamAddress.validate(it).isFailure) }
    }

    @Test fun trimsAddressBeforePlayback() {
        assertEquals("udp://@:5000", StreamAddress.validate("  udp://@:5000  ").getOrNull())
    }
}
