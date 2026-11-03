package org.crisislink.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.crisislink.core.crypto.Identity
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the node's private keys encrypted at rest. An AES-GCM key lives in the Android Keystore
 * (hardware-backed where available, never exportable); the identity file holds only ciphertext.
 */
class KeystoreIdentityStore(context: Context) {
    private val file = File(context.filesDir, "identity.bin")
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun loadOrCreate(): Identity {
        if (file.exists()) {
            runCatching { return Identity.deserialize(decrypt(file.readBytes())) }
            // Unreadable (e.g. keystore wiped by a lock-screen change): start a fresh identity.
        }
        return Identity.generate().also { file.writeBytes(encrypt(it.serialize())) }
    }

    /** Deletes the identity and its wrapping key. Irreversible. */
    fun wipe() {
        file.delete()
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
    }

    private fun key(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_BYTES)) }
        return c.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private companion object {
        const val ALIAS = "crisislink_identity"
        const val IV_BYTES = 12
    }
}
