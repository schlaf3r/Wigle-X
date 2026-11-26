package com.rfsentinel.app.sdr

/** Wigle-X shims: RF Sentinel's SDR support isn't part of Wigle-X; the USB WiFi code only asks these. */
object SdrRadio {
    /** No SDR runs in Wigle-X. */
    val status: String get() = ""
    fun isSdr(d: android.hardware.usb.UsbDevice): Boolean = false
    fun labelOf(d: android.hardware.usb.UsbDevice): String = "SDR"
}

object RtlSdr {
    fun isRtlSdr(vendorId: Int, productId: Int): Boolean = false
}

object HackRf {
    fun isHackRf(vendorId: Int, productId: Int): Boolean = false
}
