package org.crisislink.core.routing

import org.crisislink.core.model.Message

/** What we know about the peer in front of us, from its signed handshake. */
data class PeerSummary(
    val nodeId: String,
    val battery: Double,
    val bufferFree: Double,
    val predictability: Map<String, Double>,
    val messageIds: Set<String>,
    val deliveredIds: Set<String>,
)

/** Live conditions of the link to one peer. */
data class LinkContext(val rssi: Int, val connectedForMs: Long = 0, val neighbours: Int = 0)

/** Local state a router may use besides the messages themselves. */
data class LocalState(
    val nodeId: String,
    val battery: Double,
    val bufferFree: Double,
    val deliveredIds: Set<String>,
    val encounters: EncounterTable,
)

enum class RouterKind { DQN, SPRAY_AND_WAIT, PROPHET }

/**
 * Hop-by-hop forwarding from the local view: for each message we hold, decide whether to hand a
 * copy to the peer in front of us. Shared rules live here; subclasses only score unicast messages.
 * Order: messages for the peer itself, then urgent, then normal, each by score; capped per exchange.
 */
abstract class Router {
    abstract val kind: RouterKind

    /** Returns null to hold, otherwise a score (higher = send sooner). */
    protected abstract fun score(m: Message, peer: PeerSummary, link: LinkContext, local: LocalState, nowMs: Long): Double?

    fun selectForPeer(held: List<Message>, peer: PeerSummary, link: LinkContext, local: LocalState, nowMs: Long): List<Message> {
        val picked = ArrayList<Triple<Message, Int, Double>>()
        for (m in held) {
            if (m.isExpiredAt(nowMs)) continue
            if (m.id in peer.messageIds || m.id in peer.deliveredIds || m.id in local.deliveredIds) continue
            if (m.hopCount >= MAX_HOPS) continue
            when {
                m.destinationId == peer.nodeId -> picked += Triple(m, 0, Double.MAX_VALUE)
                m.isBroadcast -> if (m.hopCount < MAX_BROADCAST_HOPS) picked += Triple(m, rank(m), 0.0)
                else -> score(m, peer, link, local, nowMs)?.let { picked += Triple(m, rank(m), it) }
            }
        }
        return picked
            .sortedWith(compareBy<Triple<Message, Int, Double>> { it.second }.thenByDescending { it.third })
            .take(MAX_PER_EXCHANGE)
            .map { it.first }
    }

    private fun rank(m: Message) = if (m.isUrgent) 1 else 2

    companion object {
        /** Messages sent per connection. The simulator allowed 2 per 10 s step on the same link. */
        const val MAX_PER_EXCHANGE = 6
        /** Hop limit for broadcasts, which have no destination to route towards. */
        const val MAX_BROADCAST_HOPS = 3
        const val SPRAY_COPIES = 8
    }
}

/** Learned policy; forwards when Q(forward) − Q(hold) exceeds the policy margin. */
class DqnRouter(private val policy: DqnPolicy) : Router() {
    override val kind = RouterKind.DQN

    override fun score(m: Message, peer: PeerSummary, link: LinkContext, local: LocalState, nowMs: Long): Double? {
        val dst = m.destinationId!!
        val features = buildFeatures(
            DecisionInputs(
                urgent = m.isUrgent,
                ttlLeft = m.ttlLeft(nowMs),
                hops = m.hopCount,
                pSelfToDst = local.encounters.predictability(dst, nowMs),
                pPeerToDst = peer.predictability[dst] ?: 0.0,
                peerFree = peer.bufferFree,
                selfFree = local.bufferFree,
                peerBattery = peer.battery,
                selfBattery = local.battery,
                linkQuality = linkQualityFromRssi(link.rssi),
                contactAgeSeconds = link.connectedForMs / 1000.0,
                neighbours = link.neighbours,
                isSource = m.sourceId == local.nodeId,
                peerIsDestination = false,
                copiesMade = m.copiesMade,
            ),
        )
        val adv = policy.advantage(features)
        return if (adv > policy.margin) adv else null
    }
}

/** PRoPHET: forward when the peer is more likely to meet the destination. */
class ProphetRouter : Router() {
    override val kind = RouterKind.PROPHET

    override fun score(m: Message, peer: PeerSummary, link: LinkContext, local: LocalState, nowMs: Long): Double? {
        val dst = m.destinationId!!
        val delta = (peer.predictability[dst] ?: 0.0) - local.encounters.predictability(dst, nowMs)
        return if (delta > 0) delta else null
    }
}

/** Binary spray-and-wait: split copy tokens while more than one remains, then wait for the destination. */
class SprayAndWaitRouter : Router() {
    override val kind = RouterKind.SPRAY_AND_WAIT

    override fun score(m: Message, peer: PeerSummary, link: LinkContext, local: LocalState, nowMs: Long): Double? =
        if (m.sprayTokens > 1) -m.createdAtMs.toDouble() else null

    companion object {
        /** Tokens handed to the receiver; the sender keeps the rest. */
        fun tokensToGive(tokens: Int): Int = tokens / 2
    }
}
