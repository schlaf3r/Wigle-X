package net.wigle.xlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XLinkCodecTest {

    private fun wifi() = XLinkObservations.Obs("W", "aa:bb:cc:dd:ee:01", "Home", 5975, "[RSN-PSK][ESS]", -61, "5a03ba",
        null, null, null, true, 43.1234567, -85.7654321, 251.5, 4.5f, 1_764_000_000_000L)

    private fun ble() = XLinkObservations.Obs("E", "11:22:33:44:55:66", "Tag", 0, "Misc", -77, null,
        listOf("0000180f-0000-1000-8000-00805f9b34fb"), 76, 1, false, 0.0, 0.0, 0.0, 0f, 1_764_000_000_500L)

    @Test fun observationsRoundTrip() {
        val back = XLinkObservations.decode(XLinkObservations.encode(listOf(wifi(), ble())))
        assertEquals(2, back.size)
        with(back[0]) {
            assertEquals("W", type); assertEquals("aa:bb:cc:dd:ee:01", bssid); assertEquals("Home", ssid)
            assertEquals(5975, frequency); assertEquals("[RSN-PSK][ESS]", capabilities); assertEquals(-61, level)
            assertEquals("5a03ba", rcois); assertNull(bleServiceUuids); assertNull(bleMfgrId)
            assertTrue(hasLocation); assertEquals(43.1234567, lat, 0.0); assertEquals(-85.7654321, lon, 0.0)
            assertEquals(251.5, altitude, 0.0); assertEquals(4.5f, accuracy, 0f); assertEquals(1_764_000_000_000L, time)
        }
        with(back[1]) {
            assertEquals("E", type); assertEquals(listOf("0000180f-0000-1000-8000-00805f9b34fb"), bleServiceUuids)
            assertEquals(76, bleMfgrId); assertEquals(1, bleAddressType); assertFalse(hasLocation)
        }
    }

    @Test fun damagedOrNewerPayloadIsDropped() {
        assertTrue(XLinkObservations.decode(byteArrayOf(9, 0, 0, 0, 1)).isEmpty())
        assertTrue(XLinkObservations.decode(XLinkObservations.encode(listOf(wifi())).copyOf(20)).isEmpty())
    }

    @Test fun batchRoundTripsCompressed() {
        val payload = XLinkObservations.encode(List(200) { wifi() })
        val wire = LinkCodec.batch(LinkCodec.Batch(42L, 7, 1, payload))
        assertTrue(wire.size < payload.size)
        val b = LinkCodec.readBatch(wire)!!
        assertEquals(42L, b.origin); assertEquals(7, b.seq); assertEquals(1, b.hops)
        assertArrayEquals(payload, b.payload)
        assertEquals(2, b.relayed().hops)
    }

    @Test fun badBatchIsNull() {
        assertNull(LinkCodec.readBatch(byteArrayOf(LinkCodec.BATCH.toByte(), 1, 2, 3)))
    }

    @Test fun seenBatchesOncePerOriginAndSeq() {
        val seen = SeenBatches(capacity = 2)
        assertTrue(seen.first(1L, 1)); assertFalse(seen.first(1L, 1)); assertTrue(seen.first(2L, 1))
    }
}
