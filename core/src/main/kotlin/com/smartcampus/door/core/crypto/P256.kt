package com.smartcampus.door.core.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.EllipticCurve
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/** P-256 helpers for the ephemeral Diffie-Hellman of the handshake. */
internal object P256 {
    private const val COORDINATE_BYTES = 32
    const val POINT_BYTES = 1 + 2 * COORDINATE_BYTES

    /**
     * The NIST P-256 domain parameters (FIPS 186-4, D.1.2.3), written out. Why not ask the platform:
     * `AlgorithmParameters.getInstance("EC")` does not exist before Android 8, and the app supports Android 6.
     */
    private val params: ECParameterSpec = run {
        fun hex(value: String) = BigInteger(value, 16)
        val field = ECFieldFp(hex("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF"))
        val curve = EllipticCurve(
            field,
            hex("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC"),
            hex("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B"),
        )
        val generator = ECPoint(
            hex("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296"),
            hex("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5"),
        )
        ECParameterSpec(curve, generator, hex("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551"), 1)
    }

    // y^2 = x^3 + ax + b (mod p), the curve equation, to reject points that are not on the curve.
    private val p = (params.curve.field as ECFieldFp).p

    fun generateEphemeral(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

    /** Uncompressed point: 0x04 || X || Y, the format the server expects. */
    fun encodePoint(key: PublicKey): ByteArray {
        val w = (key as ECPublicKey).w
        return byteArrayOf(0x04) + fixedLength(w.affineX) + fixedLength(w.affineY)
    }

    fun decodePoint(bytes: ByteArray): PublicKey {
        require(bytes.size == POINT_BYTES && bytes[0] == 0x04.toByte()) { "not an uncompressed P-256 point" }
        val x = BigInteger(1, bytes.copyOfRange(1, 1 + COORDINATE_BYTES))
        val y = BigInteger(1, bytes.copyOfRange(1 + COORDINATE_BYTES, POINT_BYTES))
        require(isOnCurve(x, y)) { "point is not on the P-256 curve" }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }

    fun agree(privateKey: PrivateKey, peer: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(peer, true)
            generateSecret()
        }

    fun parseSpki(spki: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))

    private fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
        if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false
        val left = y.multiply(y).mod(p)
        val right = x.pow(3).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        return left == right
    }

    /** BigInteger drops leading zeros and may add a sign byte; coordinates must be exactly 32 bytes. */
    private fun fixedLength(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val trimmed = if (raw.size > COORDINATE_BYTES) raw.copyOfRange(raw.size - COORDINATE_BYTES, raw.size) else raw
        return ByteArray(COORDINATE_BYTES - trimmed.size) + trimmed
    }
}
