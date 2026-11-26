package net.wigle.usbwifi

import com.rfsentinel.app.usb.MonitorSighting
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalWifiTest {

    private fun ap(
        mac: String = "AA:BB:CC:DD:EE:01", ssid: String? = "Home", rssi: Int = -60, freq: Int = 2437,
        caps: String = "[RSN-PSK][ESS]", isAp: Boolean = true, hidden: Boolean = false,
        ies: List<Pair<Int, ByteArray>> = emptyList()
    ) = MonitorSighting(mac = mac, ssid = ssid, rssi = rssi, frequencyMhz = freq, isAccessPoint = isAp,
        hiddenNetwork = hidden, capabilities = caps, infoElements = ies)

    @Test fun passesAccessPointLikeAPhoneScanResult() {
        val out = ExternalWifi.toAccessPoints(listOf(ap()))
        assertEquals(1, out.size)
        with(out[0]) {
            assertEquals("aa:bb:cc:dd:ee:01", bssid)
            assertEquals("Home", ssid)
            assertEquals(2437, frequencyMhz)
            assertEquals("[RSN-PSK][ESS]", capabilities)
            assertEquals(-60, rssi)
            assertNull(roamingConsortium)
        }
    }

    @Test fun dropsClientDevices() {
        assertTrue(ExternalWifi.toAccessPoints(listOf(ap(isAp = false))).isEmpty())
    }

    @Test fun dropsAccessPointsWithUnknownSecurity() {
        assertTrue(ExternalWifi.toAccessPoints(listOf(ap(caps = ""))).isEmpty())
    }

    @Test fun hiddenNetworkKeepsEmptySsidEvenWhenRevealed() {
        assertEquals("", ExternalWifi.toAccessPoints(listOf(ap(ssid = "Revealed", hidden = true)))[0].ssid)
    }

    @Test fun dropsBadAddressesAndSignal() {
        val bad = listOf(ap(mac = "00:00:00:00:00:00"), ap(mac = "ff:ff:ff:ff:ff:ff"), ap(mac = "nonsense"),
            ap(rssi = 0), ap(rssi = -200), ap(freq = 0))
        assertTrue(ExternalWifi.toAccessPoints(bad).isEmpty())
    }

    @Test fun keepsSixGhz() {
        assertEquals(5975, ExternalWifi.toAccessPoints(listOf(ap(freq = 5975)))[0].frequencyMhz)
    }

    @Test fun passesRoamingConsortiumBody() {
        val body = byteArrayOf(0, 0x03, 0x5a, 0x03, 0xba.toByte())
        val out = ExternalWifi.toAccessPoints(listOf(ap(ies = listOf(0 to byteArrayOf(1), 111 to body))))
        assertArrayEquals(body, out[0].roamingConsortium)
    }
}
