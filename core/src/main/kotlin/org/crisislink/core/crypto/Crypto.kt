package org.crisislink.core.crypto

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.util.Bytes
import com.google.crypto.tink.util.SecretBytes
import org.crisislink.core.model.PublicKeys
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * Sealing to a recipient's static key (HPKE, RFC 9180: DHKEM-X25519, HKDF-SHA256,
 * ChaCha20-Poly1305) and Ed25519 signature checks. The recipient may be offline for hours, so
 * there is no interactive handshake and therefore no forward secrecy.
 */
object Crypto {
    init {
        HybridConfig.register()
    }

    private val params: HpkeParameters = HpkeParameters.builder()
        .setVariant(HpkeParameters.Variant.NO_PREFIX)
        .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
        .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
        .setAeadId(HpkeParameters.AeadId.CHACHA20_POLY1305)
        .build()

    private val random = SecureRandom()

    /** Encrypts [plaintext] so only [recipient] can open it. [context] (the signed header) is bound in. */
    fun seal(recipient: PublicKeys, plaintext: ByteArray, context: ByteArray): ByteArray {
        val key = HpkePublicKey.create(params, Bytes.copyFrom(recipient.encryption), null)
        val handle = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary())
            .build()
        return handle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
            .encrypt(pad(plaintext), context)
    }

    /** Returns the plaintext, or null if the ciphertext was not sealed to [identity] with this context. */
    fun open(identity: Identity, ciphertext: ByteArray, context: ByteArray): ByteArray? = try {
        val pub = HpkePublicKey.create(params, Bytes.copyFrom(identity.keys.encryption), null)
        val priv = HpkePrivateKey.create(pub, SecretBytes.copyFrom(identity.encryptionPrivate, InsecureSecretKeyAccess.get()))
        val handle = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(priv).withRandomId().makePrimary())
            .build()
        unpad(handle.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java).decrypt(ciphertext, context))
    } catch (e: GeneralSecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    fun verify(signer: PublicKeys, signature: ByteArray, data: ByteArray): Boolean = try {
        Ed25519Verify(signer.signing).verify(signature, data)
        true
    } catch (e: GeneralSecurityException) {
        false
    }

    /** Pads to 256/512/1024/2048 bytes (then multiples of 1024) so sizes leak less about content. */
    fun pad(data: ByteArray): ByteArray {
        val needed = data.size + 2
        val size = BUCKETS.firstOrNull { it >= needed } ?: ((needed + 1023) / 1024 * 1024)
        val out = ByteArray(size)
        random.nextBytes(out)
        ByteBuffer.wrap(out).putShort(data.size.toShort())
        System.arraycopy(data, 0, out, 2, data.size)
        return out
    }

    fun unpad(padded: ByteArray): ByteArray {
        require(padded.size >= 2) { "Too short" }
        val len = ByteBuffer.wrap(padded).short.toInt() and 0xffff
        require(len <= padded.size - 2) { "Bad padding" }
        return padded.copyOfRange(2, 2 + len)
    }

    private val BUCKETS = intArrayOf(256, 512, 1024, 2048)
}
