package com.smartcampus.door.core.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the installer provisions on the device. The server public key is the trust anchor of the
 * handshake (certificate pinning, P3): it is entered here, never fetched from the network.
 */
@Serializable
data class DeviceConfig(
    val serverUrl: String,
    /** SPKI DER, base64. */
    val serverPublicKey: String,
    val name: String = "",
    /** Set once the connection request was made, so a restart resumes instead of asking again. */
    val deviceId: String? = null,
    /** True once an admin approved the device. An enrolled device boots straight into its last state, network or not. */
    val enrolled: Boolean = false,
)

class DeviceConfigStore(private val storage: FileStorage) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): DeviceConfig? = storage.read(KEY)?.let { json.decodeFromString<DeviceConfig>(it) }

    fun save(config: DeviceConfig) = storage.write(KEY, json.encodeToString(DeviceConfig.serializer(), config))

    fun clear() = storage.delete(KEY)

    private companion object {
        const val KEY = "device.json"
    }
}
