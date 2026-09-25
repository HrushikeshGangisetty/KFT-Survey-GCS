package com.kft.gcs.core.mavlink

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import okio.IOException

/** The real jSerialComm adapter, on whatever machine runs the tests. No port is needed: this checks the failure path. */
class DesktopSerialPortsTest {
    @Test
    fun aMissingPortFailsWithAReadableIOException() {
        val error = assertFailsWith<IOException> { SerialPorts().open("COM_KFT_DOES_NOT_EXIST", 57600) }
        assertContains(error.message!!, "COM_KFT_DOES_NOT_EXIST")
    }

    @Test
    fun listingPortsDoesNotThrow() {
        SerialPorts().list() // may be empty on a CI runner; it must just not crash
    }
}
