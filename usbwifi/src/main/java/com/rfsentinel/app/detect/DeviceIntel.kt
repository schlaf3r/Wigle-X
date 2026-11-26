package com.rfsentinel.app.detect

/** Wigle-X shim: the one helper of RF Sentinel's DeviceIntel the USB WiFi code uses. */
object DeviceIntel {
    /** A maker's name without "Inc.", "Co., Ltd." and the like. */
    fun shortVendor(v: String): String =
        v.replace(Regex("(?i)(?:[,.]\\s*|\\s+)(inc\\.?|corp(oration)?\\.?|co\\.?,?\\s*ltd\\.?|ltd\\.?|llc|gmbh|s\\.?a\\.?|limited|technologies|technology|systems|networks|electronics|communications?|international)\\b.*$"), "")
            .trim().trimEnd(',', '.').ifEmpty { v }
}
