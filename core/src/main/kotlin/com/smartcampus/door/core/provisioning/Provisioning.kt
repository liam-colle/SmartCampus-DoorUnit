package com.smartcampus.door.core.provisioning

import com.smartcampus.door.core.crypto.fingerprintOf
import com.smartcampus.door.core.crypto.parseServerKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.TimeUnit

/** What an installer provisions: where the server is and the key the terminal will pin. */
data class ProvisioningLink(val serverUrl: String, val serverPublicKey: String)

/** A server key with the fingerprint worked out here from the key itself, never taken from the server's word. */
data class ServerKeyInfo(val publicKey: String, val fingerprint: String)

class ProvisioningException(message: String) : IOException(message)

private const val LINK_PREFIX = "smartcampus://provision"

/** Largest answer accepted from the key endpoint: a P-256 key is about 100 bytes. */
private const val MAX_KEY_RESPONSE_BYTES = 4_096L

/**
 * Reads the QR code the console shows: `smartcampus://provision?url=…&key=…`. The key travels in the
 * code, so it never crosses the network on the way to the terminal.
 */
fun parseProvisioningLink(text: String): ProvisioningLink {
    val trimmed = text.trim()
    if (!trimmed.startsWith("$LINK_PREFIX?")) throw ProvisioningException("This is not a Smart Campus provisioning code")
    val query = trimmed.substringAfter('?').split('&').filter { it.contains('=') }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }
    val url = query["url"]?.trim()?.trimEnd('/').orEmpty()
    val key = query["key"]?.filterNot(Char::isWhitespace).orEmpty()
    if (url.toHttpUrlOrNull() == null) throw ProvisioningException("The server address in the code is not valid")
    validateServerKey(key)
    return ProvisioningLink(url, key)
}

/** Throws unless [base64] is a P-256 public key in SPKI form. */
fun validateServerKey(base64: String): ByteArray {
    val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull() ?: throw ProvisioningException("The server key is not valid base64")
    runCatching { parseServerKey(bytes) }.getOrNull() ?: throw ProvisioningException("The server key is not a valid P-256 public key")
    return bytes
}

/**
 * Asks the server for the key it proves with (`GET /api/server-key`). Trusting the answer is a
 * deliberate trust on first use: the installer confirms the fingerprint, and from then on the
 * terminal pins the key and refuses any other.
 */
class ServerKeyClient(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(serverUrl: String): ServerKeyInfo = withContext(Dispatchers.IO) {
        val base = serverUrl.trim().trimEnd('/')
        if (base.toHttpUrlOrNull() == null) throw ProvisioningException("The server address must look like https://campus.example.org")
        try {
            client.newCall(Request.Builder().url("$base/api/server-key").build()).execute().use { response ->
                if (!response.isSuccessful) throw ProvisioningException("The server answered ${response.code}")
                val body = response.body ?: throw ProvisioningException("The server sent an empty answer")
                val text = body.source().use { it.request(MAX_KEY_RESPONSE_BYTES + 1); if (it.buffer.size > MAX_KEY_RESPONSE_BYTES) null else it.buffer.readUtf8() }
                    ?: throw ProvisioningException("The answer is too large to be a key")
                val key = runCatching { json.parseToJsonElement(text).jsonObject["publicKey"]?.jsonPrimitive?.content }.getOrNull()
                    ?: throw ProvisioningException("The server did not send a key")
                ServerKeyInfo(key, fingerprintOf(validateServerKey(key)))
            }
        } catch (e: ProvisioningException) {
            throw e
        } catch (e: IOException) {
            throw ProvisioningException("Cannot reach the server: ${e.message}")
        }
    }
}
