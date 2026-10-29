package sefirah.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PairedDeviceAddressTest {
    @Test fun reconnectTriesWorkingLanBeforeObsoleteAdapters() {
        val addresses = (1..20).map { AddressEntry("172.20.0.$it") } + AddressEntry("192.168.8.109")
        val device = PairedDevice("pc", "PC", byteArrayOf(), addresses, address = "192.168.8.109")
        assertEquals("192.168.8.109", device.getAddressesToTry().first())
        assertEquals(21, device.getAddressesToTry().size)
    }

    @Test fun lastWorkingAddressDoesNotOverrideDisabledAddress() {
        val device = PairedDevice("pc", "PC", byteArrayOf(), listOf(
            AddressEntry("old", isEnabled = false), AddressEntry("allowed", isEnabled = true),
        ), address = "old")
        assertEquals(listOf("allowed"), device.getAddressesToTry())
    }

    @Test fun fallbackRetainsPriorityAndDialsEachAddressOnlyOnce() {
        val device = PairedDevice("pc", "PC", byteArrayOf(), listOf(
            AddressEntry("second", priority = 2), AddressEntry("first", priority = 1),
            AddressEntry("second", priority = 3),
        ))
        assertEquals(listOf("first", "second"), device.getAddressesToTry())
    }
}
