package com.smartcampus.door.core.net

import com.smartcampus.door.core.crypto.DeviceIdentity
import com.smartcampus.door.core.crypto.fingerprintOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64

data class EnrollmentTicket(val deviceId: String, val fingerprint: String)

class EnrollmentException(message: String, val status: Int) : IOException(message)

/** REST side of enrollment: ask to be connected with our public key, then poll the answer. */
class EnrollmentClient(private val baseUrl: String, private val client: OkHttpClient = OkHttpClient()) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun request(identity: DeviceIdentity, type: String, name: String): EnrollmentTicket = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("publicKey", Base64.getEncoder().encodeToString(identity.publicKeySpki))
            put("type", type)
            if (name.isNotBlank()) put("name", name)
        }.toString().toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url("${baseUrl.trimEnd('/')}/api/enroll").post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw EnrollmentException(errorOf(text) ?: "the server answered ${response.code}", response.code)
            val parsed = json.parseToJsonElement(text).jsonObject
            EnrollmentTicket(parsed["deviceId"]!!.jsonPrimitive.content, parsed["fingerprint"]!!.jsonPrimitive.content)
        }
    }

    /** pending, active, denied, expired, revoked or unknown. */
    suspend fun status(deviceId: String): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url("${baseUrl.trimEnd('/')}/api/enroll/$deviceId").build()).execute().use { response ->
            if (!response.isSuccessful) throw EnrollmentException("the server answered ${response.code}", response.code)
            json.parseToJsonElement(response.body!!.string()).jsonObject["status"]!!.jsonPrimitive.content
        }
    }

    private fun errorOf(text: String): String? =
        runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
}

sealed interface EnrollPhase {
    data object Requesting : EnrollPhase

    /** Show the fingerprint: the admin compares it with the one in the console before approving. */
    data class Waiting(val deviceId: String, val fingerprint: String) : EnrollPhase

    data class Approved(val deviceId: String) : EnrollPhase
    data object Denied : EnrollPhase
    data object Revoked : EnrollPhase
    data class Failed(val message: String) : EnrollPhase
}

private const val POLL_INTERVAL_MS = 3_000L

/**
 * Drives enrollment as a flow of phases. With a [knownDeviceId] (a request made before a restart) it
 * resumes polling; an expired or forgotten request is simply asked again with the same key.
 */
fun enrollmentFlow(
    client: EnrollmentClient,
    identity: DeviceIdentity,
    type: String,
    name: String,
    knownDeviceId: String?,
    onTicket: (EnrollmentTicket) -> Unit,
): Flow<EnrollPhase> = flow {
    val fingerprint = fingerprintOf(identity.publicKeySpki)
    var deviceId = knownDeviceId
    while (true) {
        try {
            if (deviceId == null) {
                emit(EnrollPhase.Requesting)
                val ticket = client.request(identity, type, name)
                deviceId = ticket.deviceId
                onTicket(ticket)
            }
            when (client.status(deviceId)) {
                "active" -> return@flow emit(EnrollPhase.Approved(deviceId))
                "denied" -> return@flow emit(EnrollPhase.Denied)
                "revoked" -> return@flow emit(EnrollPhase.Revoked)
                "pending" -> emit(EnrollPhase.Waiting(deviceId, fingerprint))
                else -> deviceId = null // expired or unknown: ask again
            }
        } catch (e: IOException) {
            // A denied key is refused for a while, and a flaky network just means trying again.
            if (e is EnrollmentException && e.status == 403) return@flow emit(EnrollPhase.Denied)
            emit(EnrollPhase.Failed(e.message ?: "network error"))
        }
        delay(POLL_INTERVAL_MS)
    }
}
