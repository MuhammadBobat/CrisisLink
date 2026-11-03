package org.crisislink.core

import org.crisislink.core.model.DeliveryStatus
import org.crisislink.core.model.Priority
import org.crisislink.core.routing.SprayAndWaitRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshEngineTest {

    @Test
    fun directMessageReachesNeighbourEncryptedAndIsAcked() {
        val net = FakeNetwork()
        val a = net.Node("A"); val b = net.Node("B")
        val sent = a.engine.sendDirect(b.contact, "need insulin at north shelter", Priority.URGENT)
        net.connect(a, b)

        assertEquals(listOf("need insulin at north shelter"), b.received.map { it.text })
        assertEquals(DeliveryStatus.DELIVERED, a.store.message(sent.id)!!.status)
        assertEquals(listOf(sent.id), a.delivered)
        // the stored ciphertext does not contain the plaintext
        assertFalse(String(a.store.message(sent.id)!!.payload).contains("insulin"))
    }

    @Test
    fun relayCarriesMessageItCannotReadThenPurgesOnAck() {
        val net = FakeNetwork()
        val a = net.Node("A"); val b = net.Node("B", SprayAndWaitRouter()); val c = net.Node("C")
        a.engine.router = SprayAndWaitRouter()
        val sent = a.engine.sendDirect(c.contact, "meet at the bakery", Priority.NORMAL)

        net.connect(a, b)            // A sprays half its tokens to B
        net.disconnect(a, b)
        val relayCopy = b.store.message(sent.id)!!
        assertNull("relay must not be able to read it", relayCopy.text)
        assertEquals(1, relayCopy.hopCount)

        net.connect(b, c)            // B walks to C
        assertEquals(listOf("meet at the bakery"), c.received.map { it.text })
        assertNull("relay drops its copy after the signed ack", b.store.message(sent.id))
        net.disconnect(b, c)

        net.connect(a, b)            // ack travels back to A in B's hello
        assertEquals(DeliveryStatus.DELIVERED, a.store.message(sent.id)!!.status)
    }

    @Test
    fun forgedAckIsIgnored() {
        val net = FakeNetwork()
        val a = net.Node("A", SprayAndWaitRouter()); val b = net.Node("B", SprayAndWaitRouter())
        val c = net.Node("C"); val mallory = net.Node("M", SprayAndWaitRouter())
        val sent = a.engine.sendDirect(c.contact, "hello", Priority.NORMAL)
        net.connect(a, b)
        // Mallory, not the destination, claims delivery
        mallory.store.putAck(
            org.crisislink.core.model.Ack(
                sent.id, mallory.identity.nodeId, mallory.identity.keys, net.nowMs,
                mallory.identity.sign(org.crisislink.core.protocol.Packets.ackSignedBytes(sent.id, net.nowMs)),
            ),
        )
        net.connect(mallory, b)
        assertTrue("B still carries the message", b.store.message(sent.id) != null)
    }

    @Test
    fun tamperedPacketsAreRejected() {
        val net = FakeNetwork()
        val a = net.Node("A"); val b = net.Node("B")
        a.engine.sendDirect(b.contact, "hi", Priority.NORMAL)
        net.tamper = { p -> p.copyOf().also { it[it.size - 70] = (it[it.size - 70] + 1).toByte() } }
        net.connect(a, b)
        assertTrue(b.received.isEmpty())
        assertTrue(b.rejected.isNotEmpty())
    }

    @Test
    fun broadcastIsReadableByAnyoneAndHopCapped() {
        val net = FakeNetwork()
        val nodes = (0..5).map { net.Node("N$it") }
        nodes[0].engine.sendBroadcast("water truck at 3pm", Priority.NORMAL)
        for (i in 0 until 5) { net.connect(nodes[i], nodes[i + 1]); net.disconnect(nodes[i], nodes[i + 1]) }
        assertEquals("water truck at 3pm", nodes[1].received.single().text)
        assertTrue(nodes[3].received.isNotEmpty())
        assertTrue("hop cap stops the flood", nodes[4].received.isEmpty() && nodes[5].received.isEmpty())
    }

    @Test
    fun expiredMessagesAreNotForwarded() {
        val net = FakeNetwork()
        val a = net.Node("A"); val b = net.Node("B")
        a.engine.sendDirect(b.contact, "late", Priority.NORMAL)
        net.nowMs += 3 * 3600 * 1000L
        net.connect(a, b)
        assertTrue(b.received.isEmpty())
    }

    @Test
    fun noDuplicateSendsOnSameLink() {
        val net = FakeNetwork()
        val a = net.Node("A"); val b = net.Node("B")
        a.engine.sendDirect(b.contact, "once", Priority.NORMAL)
        net.connect(a, b)
        val before = net.sentPackets
        a.engine.reroute(); net.pump()
        assertEquals(before, net.sentPackets)
        assertEquals(1, b.received.size)
    }
}
