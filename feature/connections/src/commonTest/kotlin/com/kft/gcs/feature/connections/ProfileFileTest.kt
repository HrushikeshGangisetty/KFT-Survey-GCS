package com.kft.gcs.feature.connections

import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.SerialPortInfo
import com.kft.gcs.core.mavlink.bluetoothPortName
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
            "HC-05" to LinkConfig.Serial(bluetoothPortName("00:21:13:0A:BC:DE")), // Android Bluetooth: a serial port too
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

    /** The Links form's note replaces the baud chips on Bluetooth ports: different advice for Android and Windows. */
    @Test
    fun bluetoothNotes() {
        assertNull(bluetoothNote(SerialPortInfo("COM7", "USB Serial Port (COM7)")))
        val android = SerialPortInfo(bluetoothPortName("00:21:13:0A:BC:DE"), "Bluetooth · HC-05", bluetooth = true)
        assertTrue(bluetoothNote(android)!!.startsWith("Bluetooth: no baud rate"))
        assertTrue("Outgoing" in bluetoothNote(SerialPortInfo("COM26", "Standard Serial over Bluetooth link (COM26)", bluetooth = true))!!)
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
