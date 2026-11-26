package net.wigle.usbwifi

import android.content.Context
import com.rfsentinel.app.usb.UsbBluetooth
import com.rfsentinel.app.util.HwLog
import java.util.Locale
import java.util.UUID

/**
 * Wigle-X's Java-friendly front for the USB Bluetooth LE scanner ported from RF Sentinel ([UsbBluetooth]): a
 * Bluetooth dongle, or the Bluetooth half of a combo adapter such as the MT7921AU (ALFA AWUS036AXML), scanning
 * passively (it never sends scan requests, connects or advertises).
 *
 * Advertising reports are passed on in the shape WiGLE reads from Android's own LE scan results: address
 * ("AA:BB:CC:DD:EE:FF"), address type, RSSI, device name, service UUIDs and manufacturer ID.
 */
object ExternalBluetooth {

    /** Android's BluetoothDevice.ADDRESS_TYPE_PUBLIC / ADDRESS_TYPE_RANDOM. */
    const val ADDRESS_TYPE_PUBLIC = 0
    const val ADDRESS_TYPE_RANDOM = 1

    /** One LE advertisement heard by the USB controller. */
    class Advert(
        @JvmField val address: String,
        @JvmField val addressType: Int,
        @JvmField val rssi: Int,
        /** Complete or shortened local name, or null. */
        @JvmField val name: String?,
        /** Advertised service UUIDs as Android formats them ("0000180f-0000-1000-8000-00805f9b34fb"), or null. */
        @JvmField val serviceUuids: List<String>?,
        /** Bluetooth SIG company ID from the manufacturer-specific data, or null. */
        @JvmField val manufacturerId: Int?
    )

    /** Called on the controller's thread with each batch of adverts heard. */
    fun interface Listener { fun onAdverts(adverts: List<Advert>) }

    /** Starts the USB Bluetooth controller plugged in now or later (USB access is shared with the WiFi side). */
    @JvmStatic
    fun start(context: Context, listener: Listener) {
        HwLog.init(context)
        UsbBluetooth.start(context) { reports -> toAdverts(reports).takeIf { it.isNotEmpty() }?.let(listener::onAdverts) }
    }

    @JvmStatic
    fun stop(context: Context) = UsbBluetooth.stop(context)

    /** Status line for the UI ("" when no controller is plugged in). */
    @JvmStatic
    fun status(): String = UsbBluetooth.status

    /** The scanning controller's name ("MT7921AU"), or null while none scans. */
    @JvmStatic
    fun runningChip(): String? = UsbBluetooth.runningChip

    internal fun toAdverts(reports: List<UsbBluetooth.Report>): List<Advert> = reports.mapNotNull { r ->
        // 127: RSSI not available
        if (r.rssi >= 0 || r.rssi < -127) return@mapNotNull null
        if (r.addr.size != 6 || r.addr.all { it == 0.toByte() }) return@mapNotNull null
        val ad = parseAd(r.data)
        Advert(
            address = r.mac,
            // HCI: 0 public, 1 random, 2 public identity, 3 random identity
            addressType = if (r.addrType == 0 || r.addrType == 2) ADDRESS_TYPE_PUBLIC else ADDRESS_TYPE_RANDOM,
            rssi = r.rssi,
            name = ad.name,
            serviceUuids = ad.uuids.takeIf { it.isNotEmpty() },
            manufacturerId = ad.manufacturerId
        )
    }

    internal class Ad(val name: String?, val uuids: List<String>, val manufacturerId: Int?)

    /** The AD structures WiGLE uses, read the way Android's ScanRecord reads them. */
    internal fun parseAd(data: ByteArray): Ad {
        var name: String? = null
        var shortName: String? = null
        val uuids = ArrayList<String>()
        var mfgr: Int? = null
        var i = 0
        while (i < data.size) {
            val len = data[i].toInt() and 0xff
            if (len == 0 || i + 1 + len > data.size) break
            val type = data[i + 1].toInt() and 0xff
            val start = i + 2
            val end = i + 1 + len
            when (type) {
                0x02, 0x03 -> { var p = start; while (p + 2 <= end) { uuids += uuid16(u16(data, p)); p += 2 } }
                0x04, 0x05 -> { var p = start; while (p + 4 <= end) { uuids += uuid32(u16(data, p).toLong() or (u16(data, p + 2).toLong() shl 16)); p += 4 } }
                0x06, 0x07 -> { var p = start; while (p + 16 <= end) { uuids += uuid128(data, p); p += 16 } }
                0x08 -> shortName = String(data, start, end - start, Charsets.UTF_8)
                0x09 -> name = String(data, start, end - start, Charsets.UTF_8)
                0xff -> if (end - start >= 2) mfgr = u16(data, start)
            }
            i = end
        }
        return Ad((name ?: shortName)?.trimEnd('\u0000')?.takeIf { it.isNotBlank() }, uuids.distinct(), mfgr)
    }

    private fun u16(b: ByteArray, p: Int) = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8)

    private fun uuid16(v: Int) = String.format(Locale.US, "%08x-0000-1000-8000-00805f9b34fb", v)
    private fun uuid32(v: Long) = String.format(Locale.US, "%08x-0000-1000-8000-00805f9b34fb", v)

    /** 128-bit UUIDs are sent least significant byte first. */
    private fun uuid128(b: ByteArray, p: Int): String {
        var msb = 0L; var lsb = 0L
        for (k in 0 until 8) lsb = lsb or ((b[p + k].toLong() and 0xff) shl (8 * k))
        for (k in 0 until 8) msb = msb or ((b[p + 8 + k].toLong() and 0xff) shl (8 * k))
        return UUID(msb, lsb).toString()
    }
}
