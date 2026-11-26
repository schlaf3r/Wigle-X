package net.wigle.xlink

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * What a node sends the main phone: WiGLE observations (one network heard once, at one place), in plain JVM types
 * so the format is unit-tested. The app maps them to and from WiGLE's Network / Location.
 */
object XLinkObservations {

    /** Format version: the first byte of a payload. A newer payload is dropped rather than misread. */
    private const val VERSION = 1

    /** One observation. [type] is WiGLE's NetworkType code ("W", "E", "B", "L", "G", "N", ...). */
    class Obs(
        @JvmField val type: String,
        @JvmField val bssid: String,
        @JvmField val ssid: String,
        @JvmField val frequency: Int,
        @JvmField val capabilities: String,
        @JvmField val level: Int,
        /** Passpoint Roaming Consortium OIs, or null. */
        @JvmField val rcois: String?,
        /** BLE service UUIDs, or null. */
        @JvmField val bleServiceUuids: List<String>?,
        @JvmField val bleMfgrId: Int?,
        @JvmField val bleAddressType: Int?,
        /** False when the node had no fix: the network is recorded without a location. */
        @JvmField val hasLocation: Boolean,
        @JvmField val lat: Double,
        @JvmField val lon: Double,
        @JvmField val altitude: Double,
        @JvmField val accuracy: Float,
        /** When it was heard (the node's clock, ms). */
        @JvmField val time: Long,
    )

    @JvmStatic
    fun encode(list: List<Obs>): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            o.writeByte(VERSION)
            o.writeInt(list.size)
            for (x in list) {
                o.writeUTF(x.type.take(4))
                o.writeUTF(x.bssid.take(64))
                o.writeUTF(x.ssid.take(256))
                o.writeInt(x.frequency)
                o.writeUTF(x.capabilities.take(512))
                o.writeShort(x.level)
                o.optUtf(x.rcois?.take(512))
                val uuids = x.bleServiceUuids
                o.writeShort(uuids?.size ?: -1)
                uuids?.forEach { o.writeUTF(it.take(64)) }
                o.optInt(x.bleMfgrId)
                o.optInt(x.bleAddressType)
                o.writeBoolean(x.hasLocation)
                if (x.hasLocation) {
                    o.writeDouble(x.lat); o.writeDouble(x.lon); o.writeDouble(x.altitude); o.writeFloat(x.accuracy)
                }
                o.writeLong(x.time)
            }
        }
        return out.toByteArray()
    }

    /** Empty for a payload that doesn't parse (a newer version, or damaged). */
    @JvmStatic
    fun decode(bytes: ByteArray): List<Obs> = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            if (i.readUnsignedByte() != VERSION) return emptyList()
            val n = i.readInt()
            require(n in 0..100_000)
            List(n) {
                val type = i.readUTF(); val bssid = i.readUTF(); val ssid = i.readUTF()
                val freq = i.readInt(); val caps = i.readUTF(); val level = i.readShort().toInt()
                val rcois = i.optUtf()
                val nu = i.readShort().toInt()
                val uuids = if (nu < 0) null else List(nu) { i.readUTF() }
                val mfgr = i.optInt(); val addrType = i.optInt()
                val hasLoc = i.readBoolean()
                var lat = 0.0; var lon = 0.0; var alt = 0.0; var acc = 0f
                if (hasLoc) { lat = i.readDouble(); lon = i.readDouble(); alt = i.readDouble(); acc = i.readFloat() }
                val time = i.readLong()
                Obs(type, bssid, ssid, freq, caps, level, rcois, uuids, mfgr, addrType, hasLoc, lat, lon, alt, acc, time)
            }
        }
    }.getOrDefault(emptyList())

    private fun DataOutputStream.optUtf(s: String?) { writeBoolean(s != null); s?.let { writeUTF(it) } }
    private fun DataInputStream.optUtf(): String? = if (readBoolean()) readUTF() else null
    private fun DataOutputStream.optInt(v: Int?) { writeBoolean(v != null); v?.let { writeInt(it) } }
    private fun DataInputStream.optInt(): Int? = if (readBoolean()) readInt() else null
}
