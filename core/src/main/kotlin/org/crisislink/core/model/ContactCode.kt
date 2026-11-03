package org.crisislink.core.model

import org.crisislink.core.crypto.Identity
import org.crisislink.core.crypto.hexToBytes
import org.crisislink.core.crypto.toHex
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What a contact QR code carries: a display name and both public keys. The node ID is not sent;
 * it is recomputed from the keys, so a code cannot claim someone else's ID.
 */
object ContactCode {
    private const val PREFIX = "crisislink:1:"

    fun encode(name: String, keys: PublicKeys): String =
        PREFIX + URLEncoder.encode(name.trim().take(40), "UTF-8") + ":" + keys.encryption.toHex() + ":" + keys.signing.toHex()

    /** Returns null for anything that is not a well-formed CrisisLink contact code. */
    fun decode(text: String): Contact? = runCatching {
        require(text.startsWith(PREFIX))
        val parts = text.removePrefix(PREFIX).split(":")
        require(parts.size == 3)
        val enc = parts[1].hexToBytes()
        val sign = parts[2].hexToBytes()
        require(enc.size == 32 && sign.size == 32)
        val keys = PublicKeys(enc, sign)
        Contact(Identity.nodeIdOf(keys), URLDecoder.decode(parts[0], "UTF-8").ifBlank { "Unnamed" }, keys)
    }.getOrNull()
}
