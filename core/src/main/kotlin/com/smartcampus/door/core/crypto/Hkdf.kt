package com.smartcampus.door.core.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF with SHA-256 (RFC 5869), what the handshake uses to turn the ECDH secret into session keys. */
internal object Hkdf {
    private const val HASH_BYTES = 32

    fun derive(secret: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_BYTES) { "unsupported output length" }
        val pseudoRandomKey = hmac(salt, secret)
        val output = java.io.ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (output.size() < length) {
            block = hmac(pseudoRandomKey, block + info + byteArrayOf(counter.toByte()))
            output.write(block)
            counter++
        }
        return output.toByteArray().copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            // An empty salt is a valid HKDF input but not a valid HMAC key spec, so use the zero block it stands for.
            init(SecretKeySpec(if (key.isEmpty()) ByteArray(HASH_BYTES) else key, "HmacSHA256"))
            doFinal(data)
        }
}
