package com.rfsentinel.app.detect

/**
 * Wigle-X shim: RF Sentinel names device makers from its own OUI database. Wigle-X leaves that to WiGLE's own OUI
 * lookup, so the drivers' WPS / vendor-element parsing simply gets no name here.
 */
object VendorDb {
    @Suppress("UNUSED_PARAMETER")
    fun macVendor(mac: String): String? = null
}
