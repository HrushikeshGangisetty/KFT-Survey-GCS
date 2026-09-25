package com.kft.gcs.feature.connections

import com.kft.gcs.core.mavlink.LinkConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileFileTest {

    @Test
    fun everyLinkKindSurvivesARoundTrip() {
        val profiles = listOf(
            "SITL" to LinkConfig.UdpListen(14550),
            "Bridge" to LinkConfig.UdpClient("192.168.4.1", 14555),
            "SITL TCP" to LinkConfig.TcpClient("127.0.0.1", 5762),
            "Radio \"A\" \\ COM7" to LinkConfig.Serial("COM7", 115200), // quotes and backslashes in the name
        )
        assertEquals(profiles, decodeProfiles(encodeProfiles(profiles)))
    }

    /** The on-disk names are a file format. If this fails, existing users' profile files stop loading. */
    @Test
    fun fileFormatIsStable() {
        val saved = """[{"name":"R","link":{"type":"serial","port":"COM7","baud":57600}}]"""
        assertEquals(listOf("R" to LinkConfig.Serial("COM7", 57600)), decodeProfiles(saved))
    }

    /** Found in the first desktop run: a port equal to the class default was left out of the file. */
    @Test
    fun defaultValuesAreWrittenOut() {
        val text = encodeProfiles(listOf("SITL" to LinkConfig.UdpListen(14550), "TCP" to LinkConfig.TcpClient("h")))
        assertTrue("\"port\": 14550" in text, text)
        assertTrue("\"port\": 5760" in text, text)
    }

    @Test
    fun anEmptyListIsKeptSoDeletedDefaultsStayDeleted() {
        assertEquals(emptyList(), decodeProfiles(encodeProfiles(emptyList())))
    }

    @Test
    fun brokenFilesAreRejectedNotHalfLoaded() {
        assertNull(decodeProfiles(""), "empty file")
        assertNull(decodeProfiles("""[{"name":"R","link":{"type":"serial","port":"CO"""), "truncated by a crash")
        assertNull(decodeProfiles("""[{"name":"R","link":{"type":"carrier-pigeon"}}]"""), "unknown link kind")
        assertNull(decodeProfiles("""[{"name":"R","link":{"type":"udp-listen","port":0}}]"""), "LinkConfig's own port check")
    }
}
