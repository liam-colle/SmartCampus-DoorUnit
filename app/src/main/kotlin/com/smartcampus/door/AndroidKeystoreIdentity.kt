package com.smartcampus.door

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.smartcampus.door.core.crypto.DeviceIdentity
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The device key lives in the Android Keystore: the private half is generated there and can sign, but
 * the app (and anyone who dumps its files) can never read it. That is what "the private key never
 * leaves the device" means in practice.
 */
class AndroidKeystoreIdentity private constructor(private val privateKey: PrivateKey, override val publicKeySpki: ByteArray) : DeviceIdentity {
    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "smartcampus-device-key"

        fun loadOrCreate(): AndroidKeystoreIdentity {
            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
            if (!store.containsAlias(ALIAS)) generate()
            val entry = store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
            return AndroidKeystoreIdentity(entry.privateKey, entry.certificate.publicKey.encoded)
        }

        /** Forgets the key. Used when the device is revoked or reset: it must enroll again with a new key. */
        fun delete() {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS)
        }

        private fun generate() {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
                initialize(spec)
                generateKeyPair()
            }
        }
    }
}
