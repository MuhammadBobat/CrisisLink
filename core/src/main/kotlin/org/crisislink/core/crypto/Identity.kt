package org.crisislink.core.crypto

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.X25519
import org.crisislink.core.model.PublicKeys
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * This node's long-term keys: X25519 for receiving sealed messages, Ed25519 for signing.
 * The node ID is derived from both public keys, so anyone holding them can check it.
 */
class Identity(
    val encryptionPrivate: ByteArray,
    val signingPrivate: ByteArray,
    val keys: PublicKeys,
) {
    val nodeId: String = nodeIdOf(keys)

    fun sign(data: ByteArray): ByteArray = Ed25519Sign(signingPrivate).sign(data)

    /** Compact form for storage; the caller is responsible for encrypting it at rest. */
    fun serialize(): ByteArray = ByteBuffer.allocate(1 + 32 * 4).apply {
        put(1)
        put(encryptionPrivate); put(keys.encryption)
        put(signingPrivate); put(keys.signing)
    }.array()

    companion object {
        fun generate(): Identity {
            val encPriv = X25519.generatePrivateKey()
            val sign = Ed25519Sign.KeyPair.newKeyPair()
            return Identity(encPriv, sign.privateKey, PublicKeys(X25519.publicFromPrivate(encPriv), sign.publicKey))
        }

        fun deserialize(bytes: ByteArray): Identity {
            val b = ByteBuffer.wrap(bytes)
            require(b.get() == 1.toByte()) { "Unknown identity format" }
            fun next() = ByteArray(32).also { b.get(it) }
            val encPriv = next(); val encPub = next(); val signPriv = next(); val signPub = next()
            require(X25519.publicFromPrivate(encPriv).contentEquals(encPub)) { "Corrupt identity" }
            return Identity(encPriv, signPriv, PublicKeys(encPub, signPub))
        }

        /** First 8 bytes of SHA-256(encryption key || signing key), as 16 lowercase hex chars. */
        fun nodeIdOf(keys: PublicKeys): String {
            val d = MessageDigest.getInstance("SHA-256").apply {
                update(keys.encryption); update(keys.signing)
            }.digest()
            return d.copyOf(8).toHex()
        }

        /** Human-comparable fingerprint for in-person verification, e.g. "3f2a 91c0 77de 0b4e 12aa". */
        fun fingerprint(keys: PublicKeys): String {
            val d = MessageDigest.getInstance("SHA-256").apply {
                update(keys.encryption); update(keys.signing)
            }.digest()
            return d.copyOf(10).toHex().chunked(4).joinToString(" ")
        }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Odd hex length" }
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
