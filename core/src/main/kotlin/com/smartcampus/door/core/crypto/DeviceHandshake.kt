package com.smartcampus.door.core.crypto

import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.util.Base64

class HandshakeException(message: String) : Exception(message)

/** First message, device to server (plain JSON). Binary fields are base64. */
data class Hello(val deviceId: String, val nonceA: String, val ephA: String)

/** Server answer carrying its own nonce, ephemeral key and signature. */
data class Challenge(val nonceS: String, val ephS: String, val sigS: String)

/**
 * Device side of the mutual handshake: hello -> challenge -> auth, then both sides hold the same
 * AES keys. The server key is provisioned on the device and never learned from the network, so a
 * relay that does not own the server private key cannot complete the handshake.
 */
class DeviceHandshake(
    private val deviceId: String,
    private val identity: DeviceIdentity,
    private val serverPublicKey: PublicKey,
) {
    private val random = SecureRandom()
    private val nonceA = ByteArray(NONCE_BYTES).also(random::nextBytes)
    private val ephemeral: KeyPair = P256.generateEphemeral()
    private val ephA: ByteArray = P256.encodePoint(ephemeral.public)
    private var finished = false

    fun hello(): Hello = Hello(deviceId, b64(nonceA), b64(ephA))

    /** Verifies the server signature, then returns our signature (the `auth` message) and the session channel. */
    fun onChallenge(challenge: Challenge): Pair<String, SecureChannel> {
        if (finished) throw HandshakeException("unexpected challenge")
        finished = true

        val nonceS = unb64(challenge.nonceS)
        val ephS = unb64(challenge.ephS)
        val transcript = transcript(deviceId, nonceA, nonceS, ephA, ephS)

        if (!verify(SERVER_CONTEXT + transcript, unb64(challenge.sigS))) throw HandshakeException("server signature invalid")

        val shared = try {
            P256.agree(ephemeral.private, P256.decodePoint(ephS))
        } catch (e: IllegalArgumentException) {
            throw HandshakeException("invalid server ephemeral key")
        }

        val signature = identity.sign(DEVICE_CONTEXT + transcript)
        val keys = Hkdf.derive(shared, MessageDigest.getInstance("SHA-256").digest(transcript), SESSION_INFO.toByteArray(), 64)
        val clientToServer = keys.copyOfRange(0, 32)
        val serverToClient = keys.copyOfRange(32, 64)
        return b64(signature) to SecureChannel(clientToServer, serverToClient, "c2s", "s2c")
    }

    private fun verify(message: ByteArray, signature: ByteArray): Boolean =
        try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(serverPublicKey)
                update(message)
                verify(signature)
            }
        } catch (e: java.security.GeneralSecurityException) {
            false
        }

    internal companion object {
        const val NONCE_BYTES = 32
        val SERVER_CONTEXT = "smartcampus/v1/server\u0000".toByteArray()
        val DEVICE_CONTEXT = "smartcampus/v1/device\u0000".toByteArray()
        const val SESSION_INFO = "smartcampus/v1/session"

        /** T = deviceId || nonceA || nonceS || ephA || ephS, each field prefixed by its 4 byte big endian length. */
        fun transcript(deviceId: String, nonceA: ByteArray, nonceS: ByteArray, ephA: ByteArray, ephS: ByteArray): ByteArray =
            listOf(deviceId.toByteArray(Charsets.UTF_8), nonceA, nonceS, ephA, ephS)
                .map { ByteBuffer.allocate(4).putInt(it.size).array() + it }
                .reduce(ByteArray::plus)

        fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        fun unb64(text: String): ByteArray =
            try {
                Base64.getDecoder().decode(text)
            } catch (e: IllegalArgumentException) {
                throw HandshakeException("malformed base64 in the handshake")
            }
    }
}
