package org.crisislink.core

import org.crisislink.core.crypto.Identity
import org.crisislink.core.model.Message
import org.crisislink.core.model.Priority
import org.crisislink.core.routing.DqnPolicy
import org.crisislink.core.routing.DqnRouter
import org.crisislink.core.routing.EncounterTable
import org.crisislink.core.routing.FEATURE_NAMES
import org.crisislink.core.routing.LinkContext
import org.crisislink.core.routing.LocalState
import org.crisislink.core.routing.PeerSummary
import org.crisislink.core.routing.ProphetRouter
import org.crisislink.core.routing.Router
import org.crisislink.core.routing.SprayAndWaitRouter
import org.crisislink.core.storage.BufferPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTest {
    private val now = 1_800_000_000_000L
    private val keys = Identity.generate().keys
    private val me = "aaaaaaaaaaaaaaaa"
    private val peerId = "bbbbbbbbbbbbbbbb"
    private val dst = "cccccccccccccccc"

    /** Stub network: advantage = 4 * p_delta - 2, i.e. forward iff the peer is better placed. */
    private val stub = DqnPolicy.fromJson(
        """{"feature_names":[${FEATURE_NAMES.joinToString(",") { "\"$it\"" }}],
           "layers":[{"w":[${(0 until 16).joinToString(",") { if (it == 5) "[0,4]" else "[0,0]" }}],"b":[0,-2],"act":"linear"}]}""",
    )

    private fun msg(id: String, to: String? = dst, pr: Priority = Priority.NORMAL, hops: Int = 0, created: Long = now, tokens: Int = 0) =
        Message(id, me, to, pr, created, sourceKeys = keys, payload = ByteArray(0), signature = ByteArray(0), hopCount = hops, sprayTokens = tokens)

    private fun peer(p: Map<String, Double> = emptyMap(), have: Set<String> = emptySet(), delivered: Set<String> = emptySet()) =
        PeerSummary(peerId, 1.0, 1.0, p, have, delivered)

    private val local = LocalState(me, 1.0, 1.0, emptySet(), EncounterTable(emptyMap(), now))
    private val link = LinkContext(rssi = -60)
    private fun Router.pick(held: List<Message>, peer: PeerSummary) = selectForPeer(held, peer, link, local, now).map { it.id }

    @Test
    fun destinationFirstThenUrgent() {
        val r = DqnRouter(stub)
        val picked = r.pick(listOf(msg("normal"), msg("urgent", pr = Priority.URGENT), msg("direct", to = peerId)), peer(mapOf(dst to 0.9)))
        assertEquals(listOf("direct", "urgent", "normal"), picked)
    }

    @Test
    fun holdsWhenPeerIsNoBetterPlaced() {
        assertTrue(DqnRouter(stub).pick(listOf(msg("m")), peer()).isEmpty())
    }

    @Test
    fun skipsKnownDeliveredAndExpired() {
        val held = listOf(msg("has"), msg("done"), msg("old", created = now - 3 * 3600_000L), msg("fresh"))
        val picked = DqnRouter(stub).pick(held, peer(mapOf(dst to 0.9), have = setOf("has"), delivered = setOf("done")))
        assertEquals(listOf("fresh"), picked)
    }

    @Test
    fun budgetAndHopLimit() {
        val held = (0 until 10).map { msg("m$it") } + msg("tired", hops = 10)
        val picked = DqnRouter(stub).pick(held, peer(mapOf(dst to 0.9)))
        assertEquals(Router.MAX_PER_EXCHANGE, picked.size)
        assertFalse("tired" in picked)
    }

    @Test
    fun broadcastHopCap() {
        val picked = ProphetRouter().pick(listOf(msg("b0", to = null), msg("b3", to = null, hops = Router.MAX_BROADCAST_HOPS)), peer())
        assertEquals(listOf("b0"), picked)
    }

    @Test
    fun sprayAndWaitStopsAtOneToken() {
        val r = SprayAndWaitRouter()
        assertEquals(listOf("many"), r.pick(listOf(msg("many", tokens = 8), msg("last", tokens = 1)), peer()))
        assertEquals(4, SprayAndWaitRouter.tokensToGive(8))
    }

    @Test
    fun prophetForwardsOnlyTowardsBetterPeer() {
        assertEquals(listOf("m"), ProphetRouter().pick(listOf(msg("m")), peer(mapOf(dst to 0.5))))
        assertTrue(ProphetRouter().pick(listOf(msg("m")), peer()).isEmpty())
    }

    @Test
    fun encounterTable() {
        val t = EncounterTable(emptyMap(), now)
        t.onEncounter("B", mapOf("C" to 0.8), selfId = "A", nowMs = now)
        assertEquals(0.75, t.predictability("B", now), 1e-9)
        assertEquals(0.75 * 0.8 * 0.25, t.predictability("C", now), 1e-9)
        assertTrue(t.predictability("B", now + 3600_000) < 0.75)
    }

    @Test
    fun fullBufferAdmitsOnlyUrgentByEvictingOldestNormal() {
        val full = (0 until BufferPolicy.CAPACITY).map { msg("n$it", created = now + it) }
        assertEquals(false to null, BufferPolicy.makeRoom(full, incomingUrgent = false, nowMs = now))
        assertEquals(true to "n0", BufferPolicy.makeRoom(full, incomingUrgent = true, nowMs = now))
    }

    @Test
    fun evictionPrefersStrangersThenContactsThenOurOwn() {
        val contact = "c0".repeat(8); val stranger = "e1".repeat(8)
        // the contact's message is the oldest, but a stranger's message must go first
        val full = (0 until BufferPolicy.CAPACITY).map { i ->
            val src = when (i) { 0 -> contact; 1 -> me; else -> stranger }
            msg("m$i", created = now + i).copy(sourceId = src, firstSeenMs = now + i)
        }
        assertEquals("m2", BufferPolicy.makeRoom(full, true, now, selfId = me, contactIds = setOf(contact)).second)
        val noStrangers = full.filter { it.sourceId != stranger }.let { kept ->
            kept + (kept.size until BufferPolicy.CAPACITY).map { msg("pad$it", pr = Priority.NORMAL).copy(sourceId = contact, firstSeenMs = now + 100 + it) }
        }
        assertEquals("m0", BufferPolicy.makeRoom(noStrangers, true, now, selfId = me, contactIds = setOf(contact)).second)
    }
}
