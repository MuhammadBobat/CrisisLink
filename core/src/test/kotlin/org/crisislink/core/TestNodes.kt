package org.crisislink.core

import org.crisislink.core.crypto.Identity
import org.crisislink.core.mesh.Link
import org.crisislink.core.mesh.MeshEngine
import org.crisislink.core.mesh.MeshListener
import org.crisislink.core.model.Contact
import org.crisislink.core.model.Message
import org.crisislink.core.routing.ProphetRouter
import org.crisislink.core.routing.Router
import org.crisislink.core.storage.InMemoryMeshStore

/** In-process mesh for tests: packets are queued and delivered by [pump], never re-entrantly. */
class FakeNetwork(var nowMs: Long = 1_800_000_000_000L) {
    private val queue = ArrayDeque<Triple<MeshEngine, String, ByteArray>>()
    val nodes = mutableListOf<Node>()
    var tamper: ((ByteArray) -> ByteArray)? = null
    var sentPackets = 0

    /** [skewMs] makes this phone's clock run ahead (positive) or behind (negative) of the network's. */
    inner class Node(val name: String, router: Router = ProphetRouter(), val skewMs: Long = 0) {
        val identity = Identity.generate()
        val store = InMemoryMeshStore()
        val received = mutableListOf<Message>()
        val delivered = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val engine = MeshEngine(identity, store, router, { 1.0 }, { nowMs + skewMs }, object : MeshListener {
            override fun onMessageReceived(message: Message) { received += message }
            override fun onMessageDelivered(messageId: String) { delivered += messageId }
            override fun onRejected(linkId: String, reason: String) { rejected += reason }
        })
        val contact get() = Contact(identity.nodeId, name, identity.keys)
        init { nodes += this }
    }

    private inner class FakeLink(override val id: String, private val to: MeshEngine, private val toLinkId: String) : Link {
        override val rssi = -60
        override val connectedAtMs = nowMs
        override fun send(packet: ByteArray) {
            sentPackets++
            queue.addLast(Triple(to, toLinkId, tamper?.invoke(packet) ?: packet))
        }
    }

    fun connect(a: Node, b: Node) {
        val ab = "${a.name}->${b.name}"
        val ba = "${b.name}->${a.name}"
        a.engine.onLinkUp(FakeLink(ab, b.engine, ba))
        b.engine.onLinkUp(FakeLink(ba, a.engine, ab))
        pump()
    }

    fun disconnect(a: Node, b: Node) {
        a.engine.onLinkDown("${a.name}->${b.name}")
        b.engine.onLinkDown("${b.name}->${a.name}")
    }

    fun pump() {
        var guard = 0
        while (queue.isNotEmpty()) {
            check(++guard < 10_000) { "message storm" }
            val (engine, linkId, bytes) = queue.removeFirst()
            engine.onPacket(linkId, bytes)
        }
    }
}
