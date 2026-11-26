package net.wigle.xlink

import android.content.Context
import android.util.Base64
import androidx.core.content.edit

/**
 * This phone's X-Link (ported from RF Sentinel's X-Link store): its own id and name, the group key
 * (Keystore-encrypted, never backed up), the other phones of the group, and this phone's role: the main phone
 * (the one the others joined, which records and uploads everything) or a node (sends what it records to the main).
 */
object LinkStore {
    private const val PREFS = "wiglex_xlink"
    private const val KEY_NAME = "wiglex_xlink_key"

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var cachedKey: ByteArray? = null

    /** This phone's id in every group it joins. */
    @JvmStatic fun myId(c: Context): Long {
        val sp = sp(c)
        sp.getLong("node_id", 0L).takeIf { it != 0L }?.let { return it }
        val id = LinkCrypto.randomId()
        sp.edit(commit = true) { putLong("node_id", id) }
        return id
    }

    /** What the other phones call this phone. */
    @JvmStatic fun myName(c: Context): String = sp(c).getString("name", null) ?: android.os.Build.MODEL.take(40)
    @JvmStatic fun setMyName(c: Context, name: String) = sp(c).edit { putString("name", name.trim().take(40).ifEmpty { null }) }

    fun me(c: Context) = LinkCodec.Member(myId(c), myName(c))

    /** Sharing over Bluetooth on (X-Link screen); a group alone doesn't start the radio. */
    @JvmStatic fun enabled(c: Context) = sp(c).getBoolean("enabled", true)
    @JvmStatic fun setEnabled(c: Context, on: Boolean) = sp(c).edit { putBoolean("enabled", on) }

    /** True on the main phone: the group was started here (first "Link up"), so the others' records come here. */
    @JvmStatic fun isMain(c: Context) = hasGroup(c) && sp(c).getBoolean("main", false)
    private fun setMain(c: Context, main: Boolean) = sp(c).edit { putBoolean("main", main) }

    fun groupKey(c: Context): ByteArray? {
        cachedKey?.let { return it }
        val k = SecureStore.get(c, KEY_NAME)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?.takeIf { it.size == LinkCrypto.KEY_BYTES }
        cachedKey = k
        return k
    }

    @JvmStatic fun hasGroup(c: Context) = groupKey(c) != null

    /** A new group with just this phone (the first link-up): this phone is its main phone. */
    fun createGroup(c: Context): ByteArray {
        val key = LinkCrypto.random(LinkCrypto.KEY_BYTES)
        saveGroup(c, key, emptyList())
        setMain(c, true)
        return key
    }

    /** Joined a group: its key and phones replace any group this phone was in. */
    fun saveGroup(c: Context, key: ByteArray, members: List<LinkCodec.Member>) {
        SecureStore.put(c, KEY_NAME, Base64.encodeToString(key, Base64.NO_WRAP))
        cachedKey = key
        sp(c).edit { remove("members") }
        setMain(c, false)
        addMembers(c, members)
    }

    /** Leaves the group: forgets the key, the other phones and the role. */
    fun leave(c: Context) {
        SecureStore.put(c, KEY_NAME, null)
        cachedKey = null
        sp(c).edit { remove("members"); remove("main") }
    }

    /** The other phones of the group (not this phone). */
    fun members(c: Context): List<LinkCodec.Member> {
        val me = myId(c)
        return sp(c).getString("members", null).orEmpty().lines().mapNotNull { line ->
            val cut = line.indexOf('|')
            if (cut <= 0) null else line.substring(0, cut).toLongOrNull()?.let { LinkCodec.Member(it, line.substring(cut + 1)) }
        }.filter { it.id != me }
    }

    /** Adds phones (or updates their names); true when something changed. */
    @Synchronized
    fun addMembers(c: Context, list: List<LinkCodec.Member>): Boolean {
        val me = myId(c)
        val now = members(c).associateBy { it.id }.toMutableMap()
        var changed = false
        for (m in list) {
            if (m.id == me || m.id == 0L) continue
            val name = m.name.replace('\n', ' ').replace('|', ' ').trim().take(40).ifEmpty { "Phone" }
            if (now[m.id]?.name != name) { now[m.id] = LinkCodec.Member(m.id, name); changed = true }
        }
        if (changed) sp(c).edit { putString("members", now.values.joinToString("\n") { "${it.id}|${it.name}" }) }
        return changed
    }
}
