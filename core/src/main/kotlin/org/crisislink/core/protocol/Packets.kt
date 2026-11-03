package org.crisislink.core.protocol

import org.crisislink.core.crypto.Crypto
import org.crisislink.core.crypto.Identity
import org.crisislink.core.crypto.hexToBytes
import org.crisislink.core.crypto.toHex
import org.crisislink.core.model.Ack
import org.crisislink.core.model.Message
import org.crisislink.core.model.Priority
import org.crisislink.core.model.PublicKeys
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Binary wire format. Every packet: version (1 B), type (1 B), body.
 *
 *  HELLO   signed handshake: keys, battery, free buffer, encounter row, held ids, recent acks
 *  MESSAGE a routable message; the source signs every field except hops and spray tokens
 *  ACK     signed by the destination; relays drop their copies on seeing it
 *
 * Signatures are domain-separated by a 4-byte tag so one kind can never be replayed as another.
 */
object Packets {
    const val VERSION: Int = 1
    const val MAX_PAYLOAD = 16 * 1024
    private const val MAX_LIST = 256

    enum class Type(val code: Int) { HELLO(1), MESSAGE(2), ACK(3) }

    sealed class Packet
    data class Hello(
        val keys: PublicKeys,
        val timestampMs: Long,
        val battery: Float,
        val bufferFree: Float,
        val predictability: Map<String, Float>,
        val messageIds: List<String>,
        val acks: List<Ack>,
        val signature: ByteArray,
    ) : Packet() {
        val nodeId: String get() = Identity.nodeIdOf(keys)
        fun verify(): Boolean = Crypto.verify(keys, signature, helloSignedBytes(this))
    }
    data class MessagePacket(val message: Message) : Packet()
    data class AckPacket(val ack: Ack) : Packet()

    class MalformedPacket(msg: String) : Exception(msg)

    // ------------------------------------------------------------------ encode

    fun encodeHello(h: Hello): ByteArray = frame(Type.HELLO) { writeHelloBody(h); write(h.signature) }

    /** [ageMs] is the message's age at the moment of sending; relays pass their own computed age. */
    fun encodeMessage(m: Message, ageMs: Long = m.ageAtReceiptMs): ByteArray = frame(Type.MESSAGE) {
        writeMessageHeader(m)
        writeInt(m.payload.size); write(m.payload)
        write(m.signature)
        writeByte(m.hopCount.coerceIn(0, 255))
        writeByte(m.sprayTokens.coerceIn(0, 255))
        writeInt((ageMs / 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
    }

    fun encodeAck(a: Ack): ByteArray = frame(Type.ACK) { writeAck(a) }

    // ------------------------------------------------------------------ decode

    /** Parses a packet. Signatures are NOT checked here; callers must verify before trusting it. */
    fun decode(bytes: ByteArray): Packet {
        val input = DataInputStream(bytes.inputStream())
        try {
            if (input.readUnsignedByte() != VERSION) throw MalformedPacket("Unsupported version")
            return when (input.readUnsignedByte()) {
                Type.HELLO.code -> input.readHello()
                Type.MESSAGE.code -> MessagePacket(input.readMessage())
                Type.ACK.code -> AckPacket(input.readAck())
                else -> throw MalformedPacket("Unknown packet type")
            }
        } catch (e: java.io.EOFException) {
            throw MalformedPacket("Truncated packet")
        }
    }

    // ------------------------------------------------------------- signed views

    /** Header bytes a message's source signs and binds into the HPKE context. */
    fun messageHeaderBytes(m: Message): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).run { write(TAG_MESSAGE); writeMessageHeader(m) } }.toByteArray()

    fun messageSignedBytes(header: ByteArray, payload: ByteArray): ByteArray = header + payload

    fun ackSignedBytes(messageId: String, createdAtMs: Long): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).run { write(TAG_ACK); write(messageId.hexToBytes()); writeLong(createdAtMs) } }.toByteArray()

    fun helloSignedBytes(h: Hello): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).run { write(TAG_HELLO); writeHelloBody(h) } }.toByteArray()

    // ------------------------------------------------------------- internals

    private val TAG_MESSAGE = "CLM1".toByteArray()
    private val TAG_ACK = "CLA1".toByteArray()
    private val TAG_HELLO = "CLH1".toByteArray()

    private inline fun frame(type: Type, body: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).run { writeByte(VERSION); writeByte(type.code); body() }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.writeKeys(k: PublicKeys) { write(k.encryption); write(k.signing) }

    private fun DataOutputStream.writeMessageHeader(m: Message) {
        write(m.id.hexToBytes())
        writeKeys(m.sourceKeys)
        if (m.destinationId == null) writeByte(0) else { writeByte(1); write(m.destinationId.hexToBytes()) }
        writeByte(m.priority.ordinal)
        writeLong(m.createdAtMs)
        writeInt(m.ttlSeconds)
    }

    private fun DataOutputStream.writeAck(a: Ack) {
        write(a.messageId.hexToBytes())
        writeLong(a.createdAtMs)
        writeKeys(a.recipientKeys)
        write(a.signature)
    }

    private fun DataOutputStream.writeHelloBody(h: Hello) {
        writeKeys(h.keys)
        writeLong(h.timestampMs)
        writeFloat(h.battery)
        writeFloat(h.bufferFree)
        writeShort(h.predictability.size)
        for ((node, p) in h.predictability) { write(node.hexToBytes()); writeFloat(p) }
        writeShort(h.messageIds.size)
        for (id in h.messageIds) write(id.hexToBytes())
        writeShort(h.acks.size)
        for (a in h.acks) writeAck(a)
    }

    private fun DataInputStream.bytes(n: Int) = ByteArray(n).also { readFully(it) }
    private fun DataInputStream.readKeys() = PublicKeys(bytes(32), bytes(32))
    private fun DataInputStream.count(): Int = readUnsignedShort().also { if (it > MAX_LIST) throw MalformedPacket("List too long") }

    private fun DataInputStream.readAck(): Ack {
        val id = bytes(16).toHex()
        val created = readLong()
        val keys = readKeys()
        return Ack(id, Identity.nodeIdOf(keys), keys, created, bytes(64))
    }

    private fun DataInputStream.readHello(): Hello {
        val keys = readKeys()
        val ts = readLong()
        val battery = readFloat()
        val free = readFloat()
        val p = (0 until count()).associate { bytes(8).toHex() to readFloat() }
        val ids = (0 until count()).map { bytes(16).toHex() }
        val acks = (0 until count()).map { readAck() }
        return Hello(keys, ts, battery, free, p, ids, acks, bytes(64))
    }

    private fun DataInputStream.readMessage(): Message {
        val id = bytes(16).toHex()
        val keys = readKeys()
        val dst = if (readUnsignedByte() == 1) bytes(8).toHex() else null
        val priority = Priority.entries.getOrNull(readUnsignedByte()) ?: throw MalformedPacket("Bad priority")
        val created = readLong()
        val ttl = readInt()
        val len = readInt()
        if (len < 0 || len > MAX_PAYLOAD) throw MalformedPacket("Payload too large")
        val payload = bytes(len)
        val sig = bytes(64)
        val hops = readUnsignedByte()
        val tokens = readUnsignedByte()
        val ageSeconds = readInt().coerceAtLeast(0)
        return Message(
            id = id, sourceId = Identity.nodeIdOf(keys), destinationId = dst, priority = priority,
            createdAtMs = created, ttlSeconds = ttl, sourceKeys = keys, payload = payload, signature = sig,
            hopCount = hops, sprayTokens = tokens, ageAtReceiptMs = ageSeconds * 1000L,
        )
    }
}
