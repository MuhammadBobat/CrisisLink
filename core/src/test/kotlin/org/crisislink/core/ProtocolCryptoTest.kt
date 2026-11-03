package org.crisislink.core

import org.crisislink.core.crypto.Crypto
import org.crisislink.core.crypto.Identity
import org.crisislink.core.protocol.Fragmenter
import org.crisislink.core.protocol.Packets
import org.crisislink.core.protocol.Reassembler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ProtocolCryptoTest {
    private val alice = Identity.generate()
    private val bob = Identity.generate()

    @Test
    fun sealOpenRoundTripAndBinding() {
        val ct = Crypto.seal(bob.keys, "hello".toByteArray(), "ctx".toByteArray())
        assertEquals("hello", String(Crypto.open(bob, ct, "ctx".toByteArray())!!))
        assertNull("wrong recipient", Crypto.open(alice, ct, "ctx".toByteArray()))
        assertNull("wrong context", Crypto.open(bob, ct, "other".toByteArray()))
        val flipped = ct.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull("tampered", Crypto.open(bob, flipped, "ctx".toByteArray()))
    }

    @Test
    fun paddingHidesLengthWithinBuckets() {
        assertEquals(256, Crypto.pad(ByteArray(10)).size)
        assertEquals(256, Crypto.pad(ByteArray(200)).size)
        assertEquals(512, Crypto.pad(ByteArray(300)).size)
        val data = Random.nextBytes(777)
        assertArrayEquals(data, Crypto.unpad(Crypto.pad(data)))
    }

    @Test
    fun signatures() {
        val sig = alice.sign("x".toByteArray())
        assertTrue(Crypto.verify(alice.keys, sig, "x".toByteArray()))
        assertFalse(Crypto.verify(bob.keys, sig, "x".toByteArray()))
        assertFalse(Crypto.verify(alice.keys, sig, "y".toByteArray()))
    }

    @Test
    fun identitySerializationAndNodeId() {
        val copy = Identity.deserialize(alice.serialize())
        assertEquals(alice.nodeId, copy.nodeId)
        assertEquals(16, alice.nodeId.length)
        assertTrue(alice.nodeId != bob.nodeId)
    }

    @Test
    fun malformedPacketsThrow() {
        assertThrows(Packets.MalformedPacket::class.java) { Packets.decode(byteArrayOf(9, 1)) }
        assertThrows(Packets.MalformedPacket::class.java) { Packets.decode(byteArrayOf(1, 2, 0, 0)) }
        assertThrows(Packets.MalformedPacket::class.java) { Packets.decode(byteArrayOf(1, 77)) }
    }

    @Test
    fun fragmentsReassembleOutOfOrderAndExpire() {
        val packet = Random.nextBytes(5000)
        val frames = Fragmenter.split(packet, maxFrame = 182).shuffled(Random(1))
        assertTrue(frames.all { it.size <= 182 })
        val r = Reassembler()
        val out = frames.mapNotNull { r.add(it, 0) }
        assertArrayEquals(packet, out.single())

        val r2 = Reassembler(timeoutMs = 1000)
        val f2 = Fragmenter.split(packet, 182)
        f2.dropLast(1).forEach { r2.add(it, 0) }
        assertNull("late last frame after timeout", r2.add(f2.last(), 5000))
    }

    @Test
    fun reassemblerCapsMemory() {
        val r = Reassembler(maxBytes = 1000)
        val frames = Fragmenter.split(Random.nextBytes(5000), 200)
        assertTrue(frames.mapNotNull { r.add(it, 0) }.isEmpty())
    }
}
