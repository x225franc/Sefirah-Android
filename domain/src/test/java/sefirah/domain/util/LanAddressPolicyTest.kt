package sefirah.domain.util

import org.junit.Assert.*
import org.junit.Test

class LanAddressPolicyTest {
    @Test fun localWifiPeerRemainsReachableWhenVpnBecomesDefault() {
        assertTrue(LanAddressPolicy.isDirectLanPeer("192.168.8.109", "192.168.8.218", 24))
        assertTrue(LanAddressPolicy.isDirectLanPeer("10.2.3.4", "10.2.3.5", 24))
        assertTrue(LanAddressPolicy.isDirectLanPeer("172.16.4.5", "172.16.4.6", 24))
    }

    @Test fun vpnAndInternetDestinationsNeverEscapeOntoAnUnrelatedLan() {
        for (destination in listOf("1.1.1.1", "100.122.27.94", "10.2.0.1", "192.168.9.2", "255.255.255.255")) {
            assertFalse(LanAddressPolicy.isDirectLanPeer(destination, "192.168.8.218", 24))
        }
        assertFalse(LanAddressPolicy.isDirectLanPeer("192.168.9.2", "192.168.8.218", 0))
        assertFalse(LanAddressPolicy.isDirectLanPeer("8.8.8.8", "8.8.8.9", 24))
    }

    @Test fun invalidOrNonNumericAddressesCannotTriggerPhysicalNetworkSelection() {
        for (destination in listOf("pc.local", "192.168.8.256", "192.168.8.-1", "::1", "192.168.8.1:5150")) {
            assertFalse(LanAddressPolicy.isDirectLanPeer(destination, "192.168.8.218", 24))
        }
        assertFalse(LanAddressPolicy.isDirectLanPeer("192.168.8.1", "192.168.8.2", 33))
    }
}
