package com.smartcampus.door.core

import com.smartcampus.door.core.crypto.ChannelException
import com.smartcampus.door.core.crypto.Challenge
import com.smartcampus.door.core.crypto.DeviceHandshake
import com.smartcampus.door.core.crypto.Hkdf
import com.smartcampus.door.core.crypto.HandshakeException
import com.smartcampus.door.core.crypto.P256
import com.smartcampus.door.core.crypto.SecureChannel
import com.smartcampus.door.core.crypto.SoftwareIdentity
import com.smartcampus.door.core.crypto.fingerprintOf
import com.smartcampus.door.core.crypto.formatFingerprint
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class CryptoTest {
    @Test
    fun hkdf_matches_the_rfc5869_test_vector() {
        val okm = Hkdf.derive(
            secret = ByteArray(22) { 0x0b },
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertContentEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm)
    }

    private fun channelPair(): Pair<SecureChannel, SecureChannel> {
        val c2s = ByteArray(32) { 1 }
        val s2c = ByteArray(32) { 2 }
        return SecureChannel(c2s, s2c, "c2s", "s2c") to SecureChannel(s2c, c2s, "s2c", "c2s")
    }

    @Test
    fun channel_round_trips_in_both_directions() {
        val (device, server) = channelPair()
        assertEquals("hello server", server.decrypt(device.encrypt("hello server".toByteArray())).decodeToString())
        assertEquals("hello device", device.decrypt(server.encrypt("hello device".toByteArray())).decodeToString())
    }

    @Test
    fun channel_rejects_a_tampered_frame() {
        val (device, server) = channelPair()
        val frame = device.encrypt("secret".toByteArray())
        frame[frame.size - 1] = (frame.last().toInt() xor 1).toByte()
        assertFailsWith<ChannelException> { server.decrypt(frame) }
    }

    @Test
    fun channel_rejects_a_replayed_or_reordered_frame() {
        val (device, server) = channelPair()
        val first = device.encrypt("one".toByteArray())
        val second = device.encrypt("two".toByteArray())
        assertFailsWith<ChannelException> { server.decrypt(second) } // skipped one
        server.decrypt(first)
        assertFailsWith<ChannelException> { server.decrypt(first) } // replay
    }

    @Test
    fun channel_rejects_a_frame_reflected_in_the_wrong_direction() {
        val (device, _) = channelPair()
        val own = device.encrypt("mine".toByteArray())
        assertFailsWith<ChannelException> { device.decrypt(own) }
    }

    @Test
    fun point_encoding_is_65_bytes_and_round_trips() {
        repeat(20) {
            val key = P256.generateEphemeral().public
            val bytes = P256.encodePoint(key)
            assertEquals(65, bytes.size)
            assertContentEquals(bytes, P256.encodePoint(P256.decodePoint(bytes)))
        }
    }

    @Test
    fun point_decoding_refuses_points_that_are_not_on_the_curve() {
        val bytes = P256.encodePoint(P256.generateEphemeral().public)
        bytes[64] = (bytes[64].toInt() xor 1).toByte()
        assertFailsWith<IllegalArgumentException> { P256.decodePoint(bytes) }
    }

    @Test
    fun handshake_refuses_a_challenge_not_signed_by_the_provisioned_server_key() {
        val serverKey = SoftwareIdentity.generate()
        val handshake = DeviceHandshake("01M5M1QKV6ES3FWS1VJ2CVGXBK", SoftwareIdentity.generate(), P256.parseSpki(serverKey.publicKeySpki))
        handshake.hello()
        val b64 = Base64.getEncoder()
        val forged = Challenge(
            b64.encodeToString(ByteArray(32)),
            b64.encodeToString(P256.encodePoint(P256.generateEphemeral().public)),
            b64.encodeToString(ByteArray(70) { 7 }),
        )
        assertFailsWith<HandshakeException> { handshake.onChallenge(forged) }
    }

    @Test
    fun fingerprint_is_sha256_hex_shown_in_groups_of_four() {
        val fingerprint = fingerprintOf(ByteArray(10))
        assertEquals(64, fingerprint.length)
        assertTrue(formatFingerprint(fingerprint).split(" ").all { it.length == 4 })
    }

    @Test
    fun ulids_are_26_crockford_characters_and_sort_by_time() {
        val a = newUlid(1_000_000)
        val b = newUlid(2_000_000)
        assertTrue(ULID_REGEX.matches(a))
        assertTrue(a < b)
    }
}
