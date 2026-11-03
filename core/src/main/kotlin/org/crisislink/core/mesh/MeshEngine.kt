package org.crisislink.core.mesh

import org.crisislink.core.crypto.Crypto
import org.crisislink.core.crypto.Identity
import org.crisislink.core.crypto.toHex
import org.crisislink.core.model.Ack
import org.crisislink.core.model.Contact
import org.crisislink.core.model.DEFAULT_TTL_SECONDS
import org.crisislink.core.model.DeliveryStatus
import org.crisislink.core.model.Message
import org.crisislink.core.model.Priority
import org.crisislink.core.protocol.Packets
import org.crisislink.core.routing.EncounterTable
import org.crisislink.core.routing.LinkContext
import org.crisislink.core.routing.LocalState
import org.crisislink.core.routing.PeerSummary
import org.crisislink.core.routing.Router
import org.crisislink.core.routing.SprayAndWaitRouter
import org.crisislink.core.storage.BufferPolicy
import org.crisislink.core.storage.MeshStore
import java.security.SecureRandom

/** A connection to one neighbour, whichever BLE role carries it. Sends whole packets. */
interface Link {
    val id: String
    val rssi: Int
    val connectedAtMs: Long
    fun send(packet: ByteArray)
}

interface MeshListener {
    fun onMessageReceived(message: Message) {}
    fun onMessageDelivered(messageId: String) {}
    fun onPeersChanged(peers: List<PeerInfo>) {}
    fun onRejected(linkId: String, reason: String) {}
}

data class PeerInfo(val linkId: String, val nodeId: String?, val rssi: Int, val connectedAtMs: Long)

/**
 * Store-and-forward over whichever neighbours are in range. Transport-agnostic and synchronous:
 * the Android layer calls it from one worker thread.
 *
 * Per link: both sides send a signed HELLO; on receiving the peer's HELLO a node records the
 * encounter, absorbs verified ACKs, and sends the messages its [Router] picks for that peer.
 * Nothing unsigned or unverifiable is stored or forwarded.
 */
class MeshEngine(
    val identity: Identity,
    private val store: MeshStore,
    var router: Router,
    private val battery: () -> Double,
    private val clock: () -> Long = System::currentTimeMillis,
    private val listener: MeshListener = object : MeshListener {},
) {
    private class Peer(val link: Link) {
        var summary: PeerSummary? = null
        val sent = HashSet<String>()
        var windowStartMs = 0L
        var windowCount = 0
    }

    private val peers = LinkedHashMap<String, Peer>()
    private val encounters: EncounterTable
    private val random = SecureRandom()
    val nodeId: String get() = identity.nodeId

    init {
        val saved = store.loadEncounters()
        encounters = EncounterTable(saved?.first ?: emptyMap(), saved?.second ?: clock())
    }

    // ------------------------------------------------------------------ links

    @Synchronized
    fun onLinkUp(link: Link) {
        peers[link.id] = Peer(link)
        link.send(Packets.encodeHello(buildHello()))
        notifyPeers()
    }

    @Synchronized
    fun onLinkDown(linkId: String) {
        if (peers.remove(linkId) != null) notifyPeers()
    }

    @Synchronized
    fun peers(): List<PeerInfo> = peers.values.map { PeerInfo(it.link.id, it.summary?.nodeId, it.link.rssi, it.link.connectedAtMs) }

    @Synchronized
    fun onPacket(linkId: String, bytes: ByteArray) {
        val peer = peers[linkId] ?: return
        if (!withinRateLimit(peer)) return reject(linkId, "rate limit")
        val packet = try {
            Packets.decode(bytes)
        } catch (e: Packets.MalformedPacket) {
            return reject(linkId, e.message ?: "malformed")
        }
        when (packet) {
            is Packets.Hello -> onHello(peer, packet)
            is Packets.MessagePacket -> onMessage(peer, packet.message)
            is Packets.AckPacket -> if (!absorbAck(packet.ack)) reject(linkId, "bad ack")
        }
    }

    // --------------------------------------------------------------- sending

    @Synchronized
    fun sendDirect(contact: Contact, text: String, priority: Priority): Message {
        val now = clock()
        val base = Message(
            id = newId(), sourceId = nodeId, destinationId = contact.nodeId, priority = priority,
            createdAtMs = now, ttlSeconds = DEFAULT_TTL_SECONDS, sourceKeys = identity.keys,
            payload = ByteArray(0), signature = ByteArray(0), sprayTokens = Router.SPRAY_COPIES, text = text,
        )
        val header = Packets.messageHeaderBytes(base)
        val payload = Crypto.seal(contact.keys, text.toByteArray(), header)
        return storeOwn(base.copy(payload = payload, signature = identity.sign(Packets.messageSignedBytes(header, payload))))
    }

    @Synchronized
    fun sendBroadcast(text: String, priority: Priority): Message {
        val base = Message(
            id = newId(), sourceId = nodeId, destinationId = null, priority = priority,
            createdAtMs = clock(), ttlSeconds = DEFAULT_TTL_SECONDS, sourceKeys = identity.keys,
            payload = Crypto.pad(text.toByteArray()), signature = ByteArray(0), text = text,
        )
        val header = Packets.messageHeaderBytes(base)
        return storeOwn(base.copy(signature = identity.sign(Packets.messageSignedBytes(header, base.payload))))
    }

    private fun storeOwn(m: Message): Message {
        val (ok, victim) = BufferPolicy.makeRoom(store.messages(), m.isUrgent, clock(), force = true)
        check(ok) { "Buffer is full of urgent messages" }
        victim?.let { store.delete(it) }
        store.upsert(m)
        peers.values.forEach { exchange(it) }
        return m
    }

    /** Re-run routing for every connected peer (e.g. after the router changes). */
    @Synchronized
    fun reroute() = peers.values.forEach { exchange(it) }

    /** Drop expired messages and stale acks. Call periodically. */
    @Synchronized
    fun purge() {
        val now = clock()
        store.messages().filter { it.isExpiredAt(now) && it.status != DeliveryStatus.DELIVERED && it.sourceId != nodeId }
            .forEach { store.delete(it.id) }
        store.deleteAcksOlderThan(now - 2L * DEFAULT_TTL_SECONDS * 1000)
    }

    // -------------------------------------------------------------- handlers

    private fun onHello(peer: Peer, hello: Packets.Hello) {
        if (!hello.verify()) return reject(peer.link.id, "bad hello signature")
        val now = clock()
        val summary = PeerSummary(
            nodeId = hello.nodeId,
            battery = hello.battery.toDouble(),
            bufferFree = hello.bufferFree.toDouble(),
            predictability = hello.predictability.mapValues { it.value.toDouble() },
            messageIds = hello.messageIds.toSet(),
            deliveredIds = emptySet(),
        )
        val delivered = hello.acks.filter { absorbAck(it) }.map { it.messageId }.toSet()
        peer.summary = summary.copy(deliveredIds = delivered)
        encounters.onEncounter(summary.nodeId, summary.predictability, nodeId, now)
        encounters.snapshot().let { (t, at) -> store.saveEncounters(t, at) }
        notifyPeers()
        exchange(peer)
    }

    private fun onMessage(peer: Peer, m: Message) {
        val now = clock()
        val header = Packets.messageHeaderBytes(m)
        if (!Crypto.verify(m.sourceKeys, m.signature, Packets.messageSignedBytes(header, m.payload))) {
            return reject(peer.link.id, "bad message signature")
        }
        // Clocks on offline phones drift, so only an absurd timestamp is rejected. Lifetime is judged by
        // the age the message carries plus time on this phone, never by the sender's clock.
        if (kotlin.math.abs(m.createdAtMs - now) > MAX_CLOCK_ERROR_MS || m.ttlSeconds !in 1..MAX_TTL_SECONDS) return
        val received = m.copy(firstSeenMs = now)
        if (received.isExpiredAt(now)) return
        if (store.message(m.id) != null || store.hasAck(m.id)) return

        when {
            m.destinationId == nodeId -> {
                val plain = Crypto.open(identity, m.payload, header) ?: return reject(peer.link.id, "cannot open")
                store.upsert(received.copy(status = DeliveryStatus.DELIVERED, text = String(plain), copiesMade = 0))
                val ack = signAck(m.id)
                store.putAck(ack)
                peer.link.send(Packets.encodeAck(ack))
                listener.onMessageReceived(store.message(m.id)!!)
            }
            else -> {
                val contactIds = store.contacts().map { it.nodeId }.toSet()
                val fromContact = m.sourceId in contactIds
                if (m.isUrgent && !fromContact) {
                    // Anyone can set the urgent flag, so strangers get a small share of the buffer and a rate limit.
                    if (BufferPolicy.strangerUrgent(store.messages(), nodeId, contactIds, now) >= BufferPolicy.MAX_STRANGER_URGENT) {
                        return reject(peer.link.id, "urgent from strangers at capacity")
                    }
                    if (!withinUrgentQuota(m.sourceId, now)) return reject(peer.link.id, "urgent rate limit")
                }
                val (ok, victim) = BufferPolicy.makeRoom(
                    store.messages(), incomingUrgent = m.isUrgent && fromContact, now, selfId = nodeId, contactIds = contactIds,
                )
                if (!ok) return
                victim?.let { store.delete(it) }
                val text = if (m.isBroadcast) runCatching { String(Crypto.unpad(m.payload)) }.getOrNull() else null
                store.upsert(received.copy(status = DeliveryStatus.SENT, copiesMade = 0, text = text))
                if (m.isBroadcast) listener.onMessageReceived(store.message(m.id)!!)
            }
        }
        peers.values.filter { it !== peer }.forEach { exchange(it) }
    }

    /** Verifies and records an ack; drops relay copies and marks our own message delivered. */
    private fun absorbAck(ack: Ack): Boolean {
        if (!Crypto.verify(ack.recipientKeys, ack.signature, Packets.ackSignedBytes(ack.messageId, ack.createdAtMs))) return false
        val held = store.message(ack.messageId)
        // Only the destination's signature counts; anyone else's "delivered" claim is ignored.
        if (held != null && held.destinationId != ack.recipientId) return false
        if (store.hasAck(ack.messageId)) return true
        store.putAck(ack)
        if (held != null && held.status != DeliveryStatus.DELIVERED) {
            if (held.sourceId == nodeId) {
                store.upsert(held.copy(status = DeliveryStatus.DELIVERED))
                listener.onMessageDelivered(held.id)
            } else {
                store.delete(held.id)
            }
        }
        return true
    }

    private fun exchange(peer: Peer) {
        val summary = peer.summary ?: return
        val now = clock()
        val all = store.messages()
        val held = all.filter { it.status != DeliveryStatus.DELIVERED && it.id !in peer.sent && !store.hasAck(it.id) }
        val local = LocalState(
            nodeId = nodeId,
            battery = battery(),
            bufferFree = BufferPolicy.freeFraction(all, now),
            deliveredIds = emptySet(),
            encounters = encounters,
        )
        val link = LinkContext(peer.link.rssi, now - peer.link.connectedAtMs, peers.size)
        for (m in router.selectForPeer(held, summary, link, local, now)) {
            val toDestination = m.destinationId == summary.nodeId
            val give = if (toDestination) 0 else SprayAndWaitRouter.tokensToGive(m.sprayTokens)
            peer.link.send(Packets.encodeMessage(m.copy(hopCount = m.hopCount + 1, sprayTokens = give), ageMs = m.ageMs(now)))
            peer.sent += m.id
            store.upsert(
                m.copy(
                    copiesMade = m.copiesMade + if (toDestination) 0 else 1,
                    sprayTokens = m.sprayTokens - give,
                    status = if (m.status == DeliveryStatus.PENDING) DeliveryStatus.SENT else m.status,
                ),
            )
        }
    }

    // --------------------------------------------------------------- helpers

    private fun buildHello(): Packets.Hello {
        val now = clock()
        val all = store.messages()
        val unsigned = Packets.Hello(
            keys = identity.keys,
            timestampMs = now,
            battery = battery().toFloat(),
            bufferFree = BufferPolicy.freeFraction(all, now).toFloat(),
            predictability = encounters.export(now).mapValues { it.value.toFloat() },
            messageIds = BufferPolicy.carried(all, now).map { it.id },
            acks = store.acks().sortedByDescending { it.createdAtMs }.take(MAX_ACKS_IN_HELLO),
            signature = ByteArray(0),
        )
        return unsigned.copy(signature = identity.sign(Packets.helloSignedBytes(unsigned)))
    }

    private fun signAck(messageId: String): Ack {
        val now = clock()
        return Ack(messageId, nodeId, identity.keys, now, identity.sign(Packets.ackSignedBytes(messageId, now)))
    }

    private val urgentSeen = HashMap<String, ArrayDeque<Long>>()

    private fun withinUrgentQuota(sourceId: String, now: Long): Boolean {
        if (urgentSeen.size > 1000) urgentSeen.clear()    // bound memory against many throwaway identities
        val q = urgentSeen.getOrPut(sourceId) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() > 3600_000L) q.removeFirst()
        if (q.size >= BufferPolicy.MAX_URGENT_PER_STRANGER_PER_HOUR) return false
        q.addLast(now)
        return true
    }

    private fun withinRateLimit(peer: Peer): Boolean {
        val now = clock()
        if (now - peer.windowStartMs > 60_000) { peer.windowStartMs = now; peer.windowCount = 0 }
        return ++peer.windowCount <= MAX_PACKETS_PER_MINUTE
    }

    private fun reject(linkId: String, reason: String) = listener.onRejected(linkId, reason)

    private fun notifyPeers() = listener.onPeersChanged(peers())

    private fun newId() = ByteArray(16).also { random.nextBytes(it) }.toHex()

    companion object {
        const val MAX_ACKS_IN_HELLO = 32
        const val MAX_PACKETS_PER_MINUTE = 120
        const val MAX_CLOCK_ERROR_MS = 48 * 3600_000L
        const val MAX_TTL_SECONDS = 24 * 3600
    }
}
