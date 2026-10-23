package com.smartcampus.door.core.crypto

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ChannelException(message: String) : Exception(message)

/**
 * AES-256-GCM with one key per direction. Frame = counter (8 bytes, big endian) || ciphertext || tag.
 * The IV is 4 zero bytes followed by the counter and the direction label is the AAD. The receiver
 * requires the counter to be exactly the next one, so replayed, dropped or reordered frames fail.
 */
class SecureChannel(
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
    private val sendLabel: String,
    private val receiveLabel: String,
) {
    private var sendCounter = 0L
    private var receiveCounter = 0L

    fun encrypt(plaintext: ByteArray): ByteArray {
        val counter = sendCounter++
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(TAG_BITS, iv(counter)))
        cipher.updateAAD(sendLabel.toByteArray())
        return ByteBuffer.allocate(COUNTER_BYTES).putLong(counter).array() + cipher.doFinal(plaintext)
    }

    /** Throws [ChannelException] on any problem. After an error the channel must be discarded. */
    fun decrypt(frame: ByteArray): ByteArray {
        if (frame.size < COUNTER_BYTES + TAG_BYTES) throw ChannelException("frame too short")
        val counter = ByteBuffer.wrap(frame, 0, COUNTER_BYTES).long
        if (counter != receiveCounter) throw ChannelException("unexpected frame counter")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(receiveKey, "AES"), GCMParameterSpec(TAG_BITS, iv(counter)))
        cipher.updateAAD(receiveLabel.toByteArray())
        val plain = try {
            cipher.doFinal(frame, COUNTER_BYTES, frame.size - COUNTER_BYTES)
        } catch (e: java.security.GeneralSecurityException) {
            throw ChannelException("authentication failed")
        }
        receiveCounter++
        return plain
    }

    private fun iv(counter: Long): ByteArray = ByteBuffer.allocate(IV_BYTES).putInt(0).putLong(counter).array()

    private companion object {
        const val COUNTER_BYTES = 8
        const val TAG_BYTES = 16
        const val TAG_BITS = TAG_BYTES * 8
        const val IV_BYTES = 12
    }
}
