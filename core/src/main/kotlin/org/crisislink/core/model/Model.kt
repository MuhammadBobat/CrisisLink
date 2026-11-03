package org.crisislink.core.model

enum class Priority { URGENT, NORMAL }

enum class DeliveryStatus { PENDING, SENT, DELIVERED }

/** Default message lifetime; matches the TTL the routing policy was trained with. */
const val DEFAULT_TTL_SECONDS = 2 * 3600

/**
 * A routable message as stored on this node.
 *
 * Header fields up to [payload] are signed by the source and cannot be changed by relays.
 * [hopCount] and [sprayTokens] travel with the copy but are not signed; [copiesMade],
 * [status] and [text] are local bookkeeping and never leave the device.
 */
data class Message(
    val id: String,                 // 32 hex chars (16 random bytes)
    val sourceId: String,           // 16 hex chars
    val destinationId: String?,     // null = broadcast
    val priority: Priority,
    val createdAtMs: Long,
    val ttlSeconds: Int = DEFAULT_TTL_SECONDS,
    val sourceKeys: PublicKeys,
    val payload: ByteArray,         // HPKE ciphertext (direct) or padded UTF-8 (broadcast)
    val signature: ByteArray,
    val hopCount: Int = 0,
    val sprayTokens: Int = 0,
    val copiesMade: Int = 0,
    val status: DeliveryStatus = DeliveryStatus.PENDING,
    val text: String? = null,
) {
    val isBroadcast: Boolean get() = destinationId == null
    val isUrgent: Boolean get() = priority == Priority.URGENT
    val expiresAtMs: Long get() = createdAtMs + ttlSeconds * 1000L

    fun isExpiredAt(nowMs: Long): Boolean = nowMs >= expiresAtMs

    /** Remaining lifetime as a fraction of the TTL, in [0, 1]. */
    fun ttlLeft(nowMs: Long): Double =
        ((expiresAtMs - nowMs).toDouble() / (ttlSeconds * 1000.0)).coerceIn(0.0, 1.0)

    override fun equals(other: Any?) = other is Message && other.id == id && other.status == status &&
        other.hopCount == hopCount && other.copiesMade == copiesMade && other.text == text
    override fun hashCode() = id.hashCode()
}

/** A node's public keys. The node ID is derived from them, so they are self-certifying. */
data class PublicKeys(val encryption: ByteArray, val signing: ByteArray) {
    override fun equals(other: Any?) = other is PublicKeys &&
        encryption.contentEquals(other.encryption) && signing.contentEquals(other.signing)
    override fun hashCode() = encryption.contentHashCode() * 31 + signing.contentHashCode()
}

data class Contact(
    val nodeId: String,
    val name: String,
    val keys: PublicKeys,
    val verified: Boolean = false,
)

/** Proof, signed by the destination, that a message arrived. Relays drop their copies on seeing it. */
data class Ack(
    val messageId: String,
    val recipientId: String,
    val recipientKeys: PublicKeys,
    val createdAtMs: Long,
    val signature: ByteArray,
) {
    override fun equals(other: Any?) = other is Ack && other.messageId == messageId && other.recipientId == recipientId
    override fun hashCode() = messageId.hashCode() * 31 + recipientId.hashCode()
}
