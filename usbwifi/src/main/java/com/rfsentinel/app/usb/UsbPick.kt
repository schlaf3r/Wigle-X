package com.rfsentinel.app.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import com.rfsentinel.app.util.Prefs

/**
 * Wigle-X: WiFi-only version of RF Sentinel's UsbPick. Which plugged-in WiFi adapter to use when several are
 * plugged in, saved by USB ID ("0e8d:7961"); Auto (nothing saved) runs all of them.
 */
object UsbPick {

    enum class Kind(val key: String, val what: String) { WIFI("wifi", "WiFi adapters") }

    fun id(d: UsbDevice) = "%04x:%04x".format(d.vendorId, d.productId)

    /** What [kind]'s driver can use. */
    fun candidates(kind: Kind, all: Collection<UsbDevice>): List<UsbDevice> = all.filter { UsbWifi.chipOf(it) != null }

    /** The saved device's name when it's unplugged but other adapters are in: the driver waits for it. */
    fun waitingFor(context: Context, kind: Kind, all: Collection<UsbDevice>): String? {
        val saved = Prefs.usbPick(context, kind.key) ?: return null
        val list = candidates(kind, all)
        return if (list.isNotEmpty() && list.none { id(it) == saved }) Prefs.usbPickName(context, kind.key) ?: saved else null
    }

    /** "ALFA AWUS036AXML · MT7921AU"; the maker's name is only readable once USB access is allowed. */
    fun label(d: UsbDevice): String {
        val name = runCatching { listOfNotNull(d.manufacturerName, d.productName).joinToString(" ") }.getOrDefault("")
        return listOfNotNull(name.ifBlank { null }, UsbWifi.chipOf(d)?.name).joinToString(" · ").ifBlank { "USB device ${id(d)}" }
    }
}
