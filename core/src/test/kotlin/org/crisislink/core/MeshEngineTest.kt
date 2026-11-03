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

    // ---- phone clocks -------------------------------------------------------------------------

    @Test
    fun senderClockThreeHoursBehindStillDelivers() {
        val net = FakeNetwork()
        val a = net.Node("A", skewMs = -3 * 3600_000L); val b = net.Node("B")
        a.engine.sendDirect(b.contact, "my clock is wrong", Priority.NORMAL)
        net.connect(a, b)
        assertEquals(listOf("my clock is wrong"), b.received.map { it.text })
    }

    @Test
    fun senderClockFiveHoursAheadStillDelivers() {
        val net = FakeNetwork()
        val a = net.Node("A", skewMs = 5 * 3600_000L); val b = net.Node("B")
        a.engine.sendDirect(b.contact, "from the future", Priority.NORMAL)
        net.connect(a, b)
        assertEquals(1, b.received.size)
    }

    @Test
    fun absurdTimestampsAreStillRejected() {
        val net = FakeNetwork()
        val a = net.Node("A", skewMs = 72 * 3600_000L); val b = net.Node("B")
        a.engine.sendDirect(b.contact, "three days off", Priority.NORMAL)
        net.connect(a, b)
        assertTrue(b.received.isEmpty())
    }

    @Test
    fun ageTravelsWithTheMessageAcrossHops() {
        val net = FakeNetwork()
        val a = net.Node("A", SprayAndWaitRouter()); val b = net.Node("B", SprayAndWaitRouter())
        val c = net.Node("C", SprayAndWaitRouter()); val d = net.Node("D")
        val sent = a.engine.sendDirect(d.contact, "slow courier", Priority.NORMAL)

        net.connect(a, b); net.disconnect(a, b)
        net.nowMs += 90 * 60_000L                       // B carries it for 90 minutes
        net.connect(b, c); net.disconnect(b, c)
        val atC = c.store.message(sent.id)!!
        assertEquals(90 * 60_000L, atC.ageAtReceiptMs)

        val cNow = net.nowMs + c.skewMs
        assertFalse(atC.isExpiredAt(cNow + 20 * 60_000L))   // 110 min old
        assertTrue(atC.isExpiredAt(cNow + 40 * 60_000L))    // 130 min old, past the 2 h lifetime
    }

    @Test
    fun relayWithWrongClockStillExpiresOnTime() {
        val net = FakeNetwork()
        val a = net.Node("A", SprayAndWaitRouter()); val b = net.Node("B", SprayAndWaitRouter(), skewMs = -4 * 3600_000L)
        val d = net.Node("D")
        val sent = a.engine.sendDirect(d.contact, "x", Priority.NORMAL)
        net.connect(a, b); net.disconnect(a, b)
        val atB = b.store.message(sent.id)!!
        net.nowMs += 3 * 3600_000L
        assertTrue("2 h lifetime ends 2 h after arrival whatever B's clock says", atB.isExpiredAt(net.nowMs + b.skewMs))
    }

    // ---- urgent-flag abuse --------------------------------------------------------------------

    private fun filler(net: FakeNetwork, owner: FakeNetwork.Node, n: Int, from: String = "ee".repeat(8)) =
        (0 until n).forEach {
            owner.store.upsert(
                org.crisislink.core.model.Message(
                    id = "%032x".format(it + 1), sourceId = from, destinationId = "dd".repeat(8), priority = Priority.NORMAL,
                    createdAtMs = net.nowMs, sourceKeys = owner.identity.keys, payload = ByteArray(0), signature = ByteArray(0),
                    firstSeenMs = net.nowMs + it,
                ),
            )
        }

    @Test
    fun strangerCannotEvictWithUrgentFlag() {
        val net = FakeNetwork()
        val mallory = net.Node("M"); val victim = net.Node("V")
        filler(net, victim, org.crisislink.core.storage.BufferPolicy.CAPACITY)
        net.connect(mallory, victim)
        mallory.engine.sendBroadcast("EMERGENCY (not really)", Priority.URGENT); net.pump()
        assertEquals(org.crisislink.core.storage.BufferPolicy.CAPACITY, victim.store.messages().size)
        assertTrue(victim.store.messages().none { it.sourceId == mallory.identity.nodeId })
    }

    @Test
    fun contactsUrgentMessageMayEvictAStrangersNormalOne() {
        val net = FakeNetwork()
        val alice = net.Node("A"); val victim = net.Node("V")
        victim.store.putContact(alice.contact)
        filler(net, victim, org.crisislink.core.storage.BufferPolicy.CAPACITY)
        net.connect(alice, victim)
        alice.engine.sendBroadcast("water at the school", Priority.URGENT); net.pump()
        val held = victim.store.messages()
        assertEquals(org.crisislink.core.storage.BufferPolicy.CAPACITY, held.size)
        assertTrue(held.any { it.sourceId == alice.identity.nodeId })
    }

    @Test
    fun manyThrowawayIdentitiesCannotFillTheBufferWithUrgent() {
        val net = FakeNetwork()
        val victim = net.Node("V")
        repeat(10) { i ->
            val m = net.Node("M$i")
            m.engine.sendBroadcast("spam $i", Priority.URGENT)
            net.connect(m, victim); net.disconnect(m, victim)
        }
        val urgent = victim.store.messages().count { it.isUrgent }
        assertEquals(org.crisislink.core.storage.BufferPolicy.MAX_STRANGER_URGENT, urgent)
        assertTrue(victim.rejected.isNotEmpty())
    }

    @Test
    fun oneStrangerIsRateLimitedAndRecoversAfterAnHour() {
        val net = FakeNetwork()
        val mallory = net.Node("M"); val victim = net.Node("V")
        net.connect(mallory, victim)
        fun spam(i: Int) {
            mallory.store.messages().forEach { mallory.store.delete(it.id) }     // keep M's own buffer free
            mallory.engine.sendBroadcast("spam $i", Priority.URGENT); net.pump()
            victim.store.messages().forEach { victim.store.delete(it.id) }       // keep V's cap from being the limit
        }
        repeat(8) { spam(it) }
        assertEquals(org.crisislink.core.storage.BufferPolicy.MAX_URGENT_PER_STRANGER_PER_HOUR, victim.received.size)
        net.nowMs += 61 * 60_000L
        spam(99)
        assertEquals(org.crisislink.core.storage.BufferPolicy.MAX_URGENT_PER_STRANGER_PER_HOUR + 1, victim.received.size)
    }

    @Test
    fun normalTrafficIsUnaffectedByTheUrgentLimits() {
        val net = FakeNetwork()
        val a = net.Node("A"); val b = net.Node("B")
        a.engine.sendDirect(b.contact, "ordinary", Priority.NORMAL)
        net.connect(a, b)
        assertEquals(1, b.received.size)
    }
}
