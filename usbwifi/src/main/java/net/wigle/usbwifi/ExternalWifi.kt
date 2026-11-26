package net.wigle.usbwifi

import android.content.Context
import com.rfsentinel.app.usb.MonitorSighting
import com.rfsentinel.app.usb.UsbWifi
import com.rfsentinel.app.util.HwLog

/**
 * Wigle-X's Java-friendly front for the USB WiFi monitor-mode drivers ported from RF Sentinel ([UsbWifi]).
 *
 * The drivers report everything they hear: access points and client devices. WiGLE's WiFi records are access
 * points, so only access points whose beacon / probe response was decoded (security known) are passed on, in the
 * same shape as Android's own scan results: BSSID, SSID, frequency in MHz, "[RSN-PSK][ESS]"-style capabilities,
 * and RSSI in dBm. Client devices and names revealed for hidden networks are never passed on.
 */
object ExternalWifi {

    /** One access point heard by a USB adapter. */
    class AccessPoint(
        @JvmField val bssid: String,
        /** "" for a hidden network, as Android's scan reports it. */
        @JvmField val ssid: String,
        @JvmField val frequencyMhz: Int,
        @JvmField val capabilities: String,
        @JvmField val rssi: Int,
        /** The beacon's Roaming Consortium element body (element 111), or null. */
        @JvmField val roamingConsortium: ByteArray?
    )

    /** Called on a driver thread with each batch of access points heard. */
    fun interface Listener { fun onAccessPoints(aps: List<AccessPoint>) }

    private const val EID_ROAMING_CONSORTIUM = 111
    private val MAC = Regex("^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$")

    /** Starts every supported adapter plugged in now or later (Android asks once per adapter for USB access). */
    @JvmStatic
    fun start(context: Context, listener: Listener) {
        HwLog.init(context)
        UsbWifi.start(context) { sightings -> toAccessPoints(sightings).takeIf { it.isNotEmpty() }?.let(listener::onAccessPoints) }
    }

    /** Stops all adapters and stops listening for plug-ins. */
    @JvmStatic
    fun stop(context: Context) = UsbWifi.stop(context)

    /** Status line for the UI ("" when no adapter is plugged in). */
    @JvmStatic
    fun status(): String = UsbWifi.status

    /** How many adapters are running. */
    @JvmStatic
    fun runningCount(): Int = UsbWifi.running

    /** The adapter log, this run (for bug reports). */
    @JvmStatic
    fun logText(): String = com.rfsentinel.app.util.HwLogs.usbWifi.text()

    internal fun toAccessPoints(sightings: List<MonitorSighting>): List<AccessPoint> = sightings.mapNotNull { s ->
        if (!s.isAccessPoint) return@mapNotNull null
        // Security unknown: heard only from data / management frames without its beacon body. Android's scan always
        // has at least "[ESS]", and WiGLE's auth-mode column must not record a guess.
        if (s.capabilities.isBlank()) return@mapNotNull null
        if (s.frequencyMhz <= 0 || s.rssi >= 0 || s.rssi < -120) return@mapNotNull null
        val mac = s.mac.trim()
        if (!MAC.matches(mac) || mac == "00:00:00:00:00:00" || mac.equals("ff:ff:ff:ff:ff:ff", ignoreCase = true)) return@mapNotNull null
        AccessPoint(
            bssid = mac.lowercase(),
            ssid = if (s.hiddenNetwork) "" else s.ssid.orEmpty(),
            frequencyMhz = s.frequencyMhz,
            capabilities = s.capabilities,
            rssi = s.rssi,
            roamingConsortium = s.infoElements.firstOrNull { it.first == EID_ROAMING_CONSORTIUM }?.second
        )
    }
}
