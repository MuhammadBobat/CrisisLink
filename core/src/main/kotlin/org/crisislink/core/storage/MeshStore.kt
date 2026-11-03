package org.crisislink.core.storage

import org.crisislink.core.model.Ack
import org.crisislink.core.model.Contact
import org.crisislink.core.model.DeliveryStatus
import org.crisislink.core.model.Message

/** Persistence the mesh engine needs. Calls are made from the engine's single worker thread. */
interface MeshStore {
    fun messages(): List<Message>
    fun message(id: String): Message?
    fun upsert(message: Message)
    fun delete(id: String)

    fun acks(): List<Ack>
    fun hasAck(messageId: String): Boolean
    fun putAck(ack: Ack)
    fun deleteAcksOlderThan(cutoffMs: Long)

    fun contacts(): List<Contact>
    fun contact(nodeId: String): Contact?
    fun putContact(contact: Contact)
    fun deleteContact(nodeId: String)

    fun loadEncounters(): Pair<Map<String, Double>, Long>?
    fun saveEncounters(table: Map<String, Double>, updatedAtMs: Long)
}

/** For tests and as a reference implementation. */
class InMemoryMeshStore : MeshStore {
    private val msgs = LinkedHashMap<String, Message>()
    private val ackMap = LinkedHashMap<String, Ack>()
    private val contactMap = LinkedHashMap<String, Contact>()
    private var encounters: Pair<Map<String, Double>, Long>? = null

    override fun messages() = msgs.values.toList()
    override fun message(id: String) = msgs[id]
    override fun upsert(message: Message) { msgs[message.id] = message }
    override fun delete(id: String) { msgs.remove(id) }
    override fun acks() = ackMap.values.toList()
    override fun hasAck(messageId: String) = messageId in ackMap
    override fun putAck(ack: Ack) { ackMap[ack.messageId] = ack }
    override fun deleteAcksOlderThan(cutoffMs: Long) { ackMap.values.removeIf { it.createdAtMs < cutoffMs } }
    override fun contacts() = contactMap.values.toList()
    override fun contact(nodeId: String) = contactMap[nodeId]
    override fun putContact(contact: Contact) { contactMap[contact.nodeId] = contact }
    override fun deleteContact(nodeId: String) { contactMap.remove(nodeId) }
    override fun loadEncounters() = encounters
    override fun saveEncounters(table: Map<String, Double>, updatedAtMs: Long) { encounters = table to updatedAtMs }
}

/** Buffer rules the routing policy was trained with. */
object BufferPolicy {
    const val CAPACITY = 12

    /** Messages occupying buffer slots: anything still being carried (not delivered to us, not finished). */
    fun carried(all: List<Message>, nowMs: Long): List<Message> =
        all.filter { it.status != DeliveryStatus.DELIVERED && !it.isExpiredAt(nowMs) }

    fun freeFraction(all: List<Message>, nowMs: Long): Double =
        (1.0 - carried(all, nowMs).size.toDouble() / CAPACITY).coerceIn(0.0, 1.0)

    /**
     * Finds room for one more carried message. A full buffer only admits urgent messages, by
     * evicting the oldest normal one; [force] (our own new message) may evict too.
     * Returns (ok, evicted message id or null).
     */
    fun makeRoom(all: List<Message>, incomingUrgent: Boolean, nowMs: Long, force: Boolean = false): Pair<Boolean, String?> {
        val carried = carried(all, nowMs)
        if (carried.size < CAPACITY) return true to null
        if (!incomingUrgent && !force) return false to null
        val victim = carried.filter { !it.isUrgent }.minByOrNull { it.createdAtMs } ?: return false to null
        return true to victim.id
    }
}
