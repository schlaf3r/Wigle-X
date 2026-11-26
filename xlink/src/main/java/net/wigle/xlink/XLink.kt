package net.wigle.xlink

import android.bluetooth.le.ScanResult
import android.content.Context
import java.util.concurrent.atomic.AtomicInteger

/** Batches already handled (heard straight from their phone or relayed by another), so each is used and passed on once. */
class SeenBatches(private val capacity: Int = 4_000) {
    private val seen = object : LinkedHashMap<Pair<Long, Int>, Unit>(capacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Long, Int>, Unit>?) = size > capacity
    }

    /** True the first time this batch shows up. */
    @Synchronized
    fun first(origin: Long, seq: Int): Boolean = seen.put(origin to seq, Unit) == null

    @Synchronized
    fun clear() = seen.clear()
}

/**
 * Wigle-X's Java-friendly front for X-Link ([PhoneLink]). The main phone (the one the others joined) receives
 * every node's observations; nodes send theirs.
 */
object XLink {

    /** Called on the link thread with another phone's payload, that phone's name and id. */
    fun interface Receiver { fun onPayload(fromName: String, origin: Long, payload: ByteArray) }

    /** Set by the app: the group, role or sharing switch changed on the X-Link screen (start or stop the link). */
    @JvmStatic @Volatile var onSettingsChanged: Runnable? = null

    /** Starts at the time in seconds, so a restarted app never reuses a number the others have seen. */
    private val seq = AtomicInteger((System.currentTimeMillis() / 1000).toInt())

    @JvmStatic fun hasGroup(c: Context) = LinkStore.hasGroup(c)
    @JvmStatic fun isMain(c: Context) = LinkStore.isMain(c)
    @JvmStatic fun sharingOn(c: Context) = LinkStore.enabled(c)
    @JvmStatic fun canUse(c: Context) = PhoneLink.canUse(c)

    @JvmStatic
    fun start(context: Context, receiver: Receiver) =
        PhoneLink.start(context) { b, name -> receiver.onPayload(name, b.origin, b.payload) }

    @JvmStatic fun stop() { PhoneLink.stop() }

    /** Sends [payload] to every phone of the group (relayed on up to [LinkCodec.MAX_HOPS] times). */
    @JvmStatic
    fun send(context: Context, payload: ByteArray) {
        PhoneLink.send(LinkCodec.Batch(LinkStore.myId(context), seq.incrementAndGet(), 0, payload))
    }

    /** Phones linked to this one right now (directly over Bluetooth). */
    @JvmStatic fun linkedCount(): Int = PhoneLink.linked.size

    /** Phones heard from (directly or relayed) within [windowMs], plus those linked now. */
    @JvmStatic
    fun activeCount(windowMs: Long): Int {
        val now = System.currentTimeMillis()
        val ids = PhoneLink.lastHeard.filterValues { now - it <= windowMs }.keys + PhoneLink.linked.map { it.id }
        return ids.size
    }

    /** The status line ("" while X-Link isn't running). */
    @JvmStatic fun status(): String = PhoneLink.status

    /**
     * For the app's own BLE scan: true for another Wigle-X phone's X-Link advert (handled here, not a device to
     * record).
     */
    @JvmStatic fun isLinkAdvert(r: ScanResult): Boolean = PhoneLink.onScanResult(r)
}
