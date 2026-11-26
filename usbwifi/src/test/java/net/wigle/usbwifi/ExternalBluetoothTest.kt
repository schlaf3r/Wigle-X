package net.wigle.usbwifi

import com.rfsentinel.app.usb.UsbBluetooth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalBluetoothTest {

    // flags; 16-bit UUIDs 0x180F, 0xFEAA; name "Tag"; manufacturer 0x004C
    private val ad = byteArrayOf(2, 0x01, 0x06, 5, 0x03, 0x0F, 0x18, 0xAA.toByte(), 0xFE.toByte(),
        4, 0x09, 'T'.code.toByte(), 'a'.code.toByte(), 'g'.code.toByte(), 5, 0xFF.toByte(), 0x4C, 0x00, 0x02, 0x15)
    private val addr = byteArrayOf(0x66, 0x55, 0x44, 0x33, 0x22, 0x11) // HCI order

    @Test fun parsesNameUuidsAndManufacturer() {
        val p = ExternalBluetooth.parseAd(ad)
        assertEquals("Tag", p.name)
        assertEquals(listOf("0000180f-0000-1000-8000-00805f9b34fb", "0000feaa-0000-1000-8000-00805f9b34fb"), p.uuids)
        assertEquals(0x004C, p.manufacturerId)
    }

    @Test fun parses128BitUuidLeastSignificantByteFirst() {
        val u = "6e400001b5a3f393e0a9e50e24dcca9e".chunked(2).map { it.toInt(16).toByte() }.reversed().toByteArray()
        assertEquals(listOf("6e400001-b5a3-f393-e0a9-e50e24dcca9e"), ExternalBluetooth.parseAd(byteArrayOf(17, 0x07) + u).uuids)
    }

    @Test fun truncatedStructureIsIgnored() {
        assertNull(ExternalBluetooth.parseAd(byteArrayOf(9, 0x09, 'x'.code.toByte())).name)
    }

    @Test fun reportBecomesAdvertLikeAndroidScanResult() {
        val a = ExternalBluetooth.toAdverts(listOf(UsbBluetooth.Report(addr, 1, -70, ad, true, "LE 1M")))
        assertEquals(1, a.size)
        assertEquals("11:22:33:44:55:66", a[0].address)
        assertEquals(ExternalBluetooth.ADDRESS_TYPE_RANDOM, a[0].addressType)
        assertEquals(-70, a[0].rssi)
    }

    @Test fun publicIdentityAddressIsPublic() {
        assertEquals(ExternalBluetooth.ADDRESS_TYPE_PUBLIC,
            ExternalBluetooth.toAdverts(listOf(UsbBluetooth.Report(addr, 2, -70, ad, true, null)))[0].addressType)
    }

    @Test fun dropsUnavailableRssiAndEmptyAddress() {
        assertTrue(ExternalBluetooth.toAdverts(listOf(UsbBluetooth.Report(addr, 0, 127, ad, true, null))).isEmpty())
        assertTrue(ExternalBluetooth.toAdverts(listOf(UsbBluetooth.Report(ByteArray(6), 0, -50, ad, true, null))).isEmpty())
    }
}
