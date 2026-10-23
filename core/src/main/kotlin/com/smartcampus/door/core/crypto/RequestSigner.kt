package com.smartcampus.door.core.crypto

import com.smartcampus.door.core.util.B64


/** Headers of a request signed with the device key (asset downloads). */
data class SignedHeaders(val deviceId: String, val timestamp: String, val signature: String)

/**
 * Signs method, path and timestamp, as the server verifies them. A stolen URL is useless without the
 * key, and the server refuses timestamps more than 60 seconds off, so a captured request cannot be replayed later.
 * `path` has no query string.
 */
fun signRequest(identity: DeviceIdentity, deviceId: String, method: String, path: String, nowMs: Long): SignedHeaders {
    val timestamp = nowMs.toString()
    val message = "smartcampus/v1/request\u0000${method.uppercase()}\n$path\n$timestamp".toByteArray()
    return SignedHeaders(deviceId, timestamp, B64.encode(identity.sign(message)))
}
