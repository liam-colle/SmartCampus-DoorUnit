package com.smartcampus.door.core.crypto

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The long-term ECDSA P-256 key of a device. The private half never leaves the implementation:
 * on Android it lives in the Keystore, in tests it is an in-memory key.
 */
interface DeviceIdentity {
    /** SubjectPublicKeyInfo DER, what the server stores at enrollment. */
    val publicKeySpki: ByteArray

    /** ECDSA with SHA-256, DER encoded. */
    fun sign(data: ByteArray): ByteArray
}

/** Identity backed by a plain JCA key pair. Used by tests and the command line harness. */
class SoftwareIdentity(private val keyPair: KeyPair) : DeviceIdentity {
    override val publicKeySpki: ByteArray get() = keyPair.public.encoded

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(data)
            sign()
        }

    companion object {
        fun generate(): SoftwareIdentity {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            return SoftwareIdentity(generator.generateKeyPair())
        }
    }
}

/** SHA-256 of the public key as lowercase hex. The console shows the same value for the admin to compare. */
fun fingerprintOf(publicKeySpki: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(publicKeySpki).joinToString("") { "%02x".format(it) }

/** "AB12 CD34 ..." for people. */
fun formatFingerprint(fingerprint: String): String = fingerprint.uppercase().chunked(4).joinToString(" ")

/** Parses the server public key provisioned on the device (SPKI DER). Fails on anything that is not an EC key. */
fun parseServerKey(spki: ByteArray): java.security.PublicKey = P256.parseSpki(spki)
