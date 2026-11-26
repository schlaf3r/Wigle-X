package net.wigle.xlink

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * X-Link's wire format (big-endian, plain JVM so it's unit-tested). The pairing, hello and sealed frames are RF
 * Sentinel's Sentinel Link frames unchanged; a batch carries an opaque, compressed payload (Wigle-X's observations,
 * see the app's XLinkCodec) instead of RF Sentinel's detections.
 */
object LinkCodec {
    // ---- Message types: the first byte of every frame ----
    /** Joiner -> host: ECDH public key + nonce. */
    const val PAIR_HELLO = 1
    /** Host -> joiner: ECDH public key + nonce. */
    const val PAIR_REPLY = 2
    /** Joiner -> host, sealed: the joiner's id and name once its user confirmed the code. */
    const val PAIR_CONFIRM = 3
    /** Host -> joiner, sealed: the group key and the phones in it. */
    const val PAIR_GROUP = 4
    const val PAIR_CANCEL = 5
    /** Connecting phone -> other: nonce + proof of the group key. */
    const val HELLO = 10
    /** Answer to [HELLO]: nonce + proof. */
    const val HELLO_REPLY = 11
    /** Counter + AES-GCM data; inside, one of the inner types below. */
    const val SEALED = 12

    // ---- Inner (sealed) types ----
    const val INFO = 20
    const val BATCH = 21

    data class Member(val id: Long, val name: String)

    /** What one phone recorded lately, passed on by others up to [MAX_HOPS] times so it reaches the main phone. */
    class Batch(val origin: Long, val seq: Int, val hops: Int, val payload: ByteArray) {
        fun relayed() = Batch(origin, seq, hops + 1, payload)
    }

    const val MAX_HOPS = 2

    // ---- Small messages ----

    fun pairHello(type: Int, pub: ByteArray, nonce: ByteArray): ByteArray = write {
        writeByte(type); bytes(pub); bytes(nonce)
    }

    /** (public key, nonce) of [PAIR_HELLO] / [PAIR_REPLY]. */
    fun readPairHello(b: ByteArray): Pair<ByteArray, ByteArray> = read(b) { readByte(); bytes() to bytes() }

    fun member(m: Member): ByteArray = write { writeLong(m.id); writeUTF(m.name.take(40)) }
    fun readMember(b: ByteArray): Member = read(b) { Member(readLong(), readUTF()) }

    fun group(key: ByteArray, members: List<Member>): ByteArray = write {
        bytes(key); writeShort(members.size); members.forEach { writeLong(it.id); writeUTF(it.name.take(40)) }
    }

    fun readGroup(b: ByteArray): Pair<ByteArray, List<Member>> = read(b) {
        val key = bytes()
        key to List(readUnsignedShort()) { Member(readLong(), readUTF()) }
    }

    fun hello(type: Int, nonce: ByteArray, proof: ByteArray): ByteArray = write { writeByte(type); bytes(nonce); bytes(proof) }
    fun readHello(b: ByteArray): Pair<ByteArray, ByteArray> = read(b) { readByte(); bytes() to bytes() }

    fun sealed(counter: Long, data: ByteArray): ByteArray =
        ByteBuffer.allocate(9 + data.size).put(SEALED.toByte()).putLong(counter).put(data).array()

    /** (counter, data) of a [SEALED] frame. */
    fun readSealed(b: ByteArray): Pair<Long, ByteArray> = ByteBuffer.wrap(b).let { it.get(); it.long to b.copyOfRange(9, b.size) }

    /** [INFO]: who this phone is and every phone it knows (so a new one spreads through the group). */
    fun info(me: Member, members: List<Member>): ByteArray = write {
        writeByte(INFO); writeLong(me.id); writeUTF(me.name.take(40))
        writeShort(members.size); members.forEach { writeLong(it.id); writeUTF(it.name.take(40)) }
    }

    fun readInfo(b: ByteArray): Pair<Member, List<Member>> = read(b) {
        readByte()
        Member(readLong(), readUTF()) to List(readUnsignedShort()) { Member(readLong(), readUTF()) }
    }

    // ---- Batches ----

    fun batch(b: Batch): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(DeflaterOutputStream(body, Deflater(Deflater.BEST_SPEED))).use { o ->
            o.writeLong(b.origin); o.writeInt(b.seq); o.writeByte(b.hops)
            o.writeInt(b.payload.size); o.write(b.payload)
        }
        return byteArrayOf(BATCH.toByte()) + body.toByteArray()
    }

    /** Null for a batch that doesn't parse (a newer version, or damaged). */
    fun readBatch(bytes: ByteArray): Batch? = runCatching {
        DataInputStream(InflaterInputStream(ByteArrayInputStream(bytes, 1, bytes.size - 1))).use { i ->
            val origin = i.readLong(); val seq = i.readInt(); val hops = i.readUnsignedByte()
            val n = i.readInt()
            require(n in 0..MAX_PAYLOAD)
            Batch(origin, seq, hops, ByteArray(n).also { i.readFully(it) })
        }
    }.getOrNull()

    /** Largest payload accepted (uncompressed). */
    const val MAX_PAYLOAD = 4 * 1024 * 1024

    // ---- Helpers ----

    private fun DataOutputStream.bytes(b: ByteArray) { writeShort(b.size); write(b) }
    private fun DataInputStream.bytes(): ByteArray = ByteArray(readUnsignedShort()).also { readFully(it) }

    private fun write(block: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).use(block) }.toByteArray()

    private fun <T> read(b: ByteArray, block: DataInputStream.() -> T): T = DataInputStream(ByteArrayInputStream(b)).use(block)
}
