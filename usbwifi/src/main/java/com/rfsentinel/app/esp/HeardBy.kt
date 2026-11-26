package com.rfsentinel.app.esp

/** Wigle-X shim: RF Sentinel tracks which radio heard each device; Wigle-X doesn't, so this is a no-op set. */
object HeardBy {
    val usb: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
}
