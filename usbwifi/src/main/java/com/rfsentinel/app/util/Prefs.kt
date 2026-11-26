package com.rfsentinel.app.util

import android.content.Context

/**
 * Wigle-X shim for the few RF Sentinel settings the USB WiFi drivers read. Stored in their own preferences file
 * so they never collide with WiGLE's.
 */
object Prefs {
    private const val FILE = "wiglex_usbwifi"

    /** Which bands the adapter hops. */
    enum class WifiBand { ALL, LOW, HIGH, MOSTLY_LOW }

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @JvmStatic fun wifiBand(c: Context): WifiBand =
        runCatching { WifiBand.valueOf(p(c).getString("wifi_band", null) ?: "ALL") }.getOrDefault(WifiBand.ALL)
    @JvmStatic fun setWifiBand(c: Context, band: WifiBand) = p(c).edit().putString("wifi_band", band.name).apply()

    /** RTL8811AU / 8821AU: hop 5 GHz too (on by default). */
    fun rtl8821au5g(c: Context): Boolean = p(c).getBoolean("rtl8821au_5g", true)

    /** Leave the adapter's LED on while it runs. */
    fun usbAdapterLight(c: Context): Boolean = p(c).getBoolean("usb_adapter_light", true)

    /** Log every device the adapter hears (large log). */
    fun usbWifiLogAll(c: Context): Boolean = p(c).getBoolean("usb_wifi_log_all", false)

    /** The USB ID ("0e8d:7961") picked when several adapters are plugged in; null is Auto. */
    fun usbPick(c: Context, kind: String): String? = p(c).getString("usb_pick_$kind", null)
    fun usbPickName(c: Context, kind: String): String? = p(c).getString("usb_pick_name_$kind", null)

    /** Use the Bluetooth side of a USB adapter (or a USB Bluetooth dongle) as a second LE scanner. */
    @JvmStatic fun usbBluetooth(c: Context): Boolean = p(c).getBoolean("usb_bluetooth", true)
    @JvmStatic fun setUsbBluetooth(c: Context, value: Boolean) = p(c).edit().putBoolean("usb_bluetooth", value).apply()

    /** Adapters whose "Bluetooth turned off by its maker" notice was dismissed. */
    fun usbBtNoticeHidden(c: Context): Set<String> = p(c).getStringSet("usb_bt_notice_hidden", emptySet()).orEmpty()
    fun hideUsbBtNotice(c: Context, id: String) = p(c).edit().putStringSet("usb_bt_notice_hidden", usbBtNoticeHidden(c) + id).apply()
}
