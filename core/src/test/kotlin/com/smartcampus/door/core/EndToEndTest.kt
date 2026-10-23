package com.smartcampus.door.core

import com.smartcampus.door.core.audio.AssetDownloader
import com.smartcampus.door.core.audio.AudioPlayer
import com.smartcampus.door.core.crypto.P256
import com.smartcampus.door.core.crypto.SoftwareIdentity
import com.smartcampus.door.core.net.DeviceLink
import com.smartcampus.door.core.net.EnrollmentClient
import com.smartcampus.door.core.net.LinkEvent
import com.smartcampus.door.core.storage.FileStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Talks to a real SmartCampus server: enrollment, approval by an admin, the signed handshake, commands,
 * a signed audio download, revocation. Skipped unless SC_E2E_URL is set:
 *
 *   SC_E2E_URL=http://localhost:3000 SC_E2E_SERVER_KEY=<base64 SPKI of the server key>
 *   SC_E2E_ADMIN_EMAIL=... SC_E2E_ADMIN_PASSWORD=... ./gradlew :core:test --tests '*EndToEndTest*'
 */
class EndToEndTest {
    private val url = System.getenv("SC_E2E_URL")
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient()
    private var cookie = ""

    private class RecordingPlayer : AudioPlayer {
        val played = mutableListOf<Pair<Long, Int>>()
        override suspend fun play(file: File, volumePercent: Int) {
            played += file.length() to volumePercent
        }
    }

    private fun admin(method: String, path: String, body: String? = null, raw: ByteArray? = null, type: String = "application/json"): JsonObject? {
        val payload = (raw ?: body?.toByteArray())?.toRequestBody(type.toMediaType())
        val request = Request.Builder().url("$url/api$path").method(method, payload ?: if (method == "GET") null else ByteArray(0).toRequestBody())
            .header("Cookie", cookie).build()
        http.newCall(request).execute().use { response ->
            if (path == "/auth/login") cookie = response.headers("Set-Cookie").joinToString("; ") { it.substringBefore(";") }
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful) { "$method $path -> ${response.code} $text" }
            return if (text.isBlank()) null else json.parseToJsonElement(text).jsonObject
        }
    }

    private suspend fun until(timeoutMs: Long = 8_000, what: String, condition: () -> Boolean) {
        withTimeout(timeoutMs) { while (!condition()) delay(50) }.also { assertTrue(condition(), what) }
    }

    @Test
    fun enrolls_connects_follows_commands_downloads_audio_and_is_revoked(): Unit = runBlocking {
        assumeTrue("SC_E2E_URL not set", url != null)
        val serverKey = P256.parseSpki(Base64.getDecoder().decode(System.getenv("SC_E2E_SERVER_KEY")))
        admin("POST", "/auth/login", """{"email":"${System.getenv("SC_E2E_ADMIN_EMAIL")}","password":"${System.getenv("SC_E2E_ADMIN_PASSWORD")}"}""")

        // 1. Enrollment: the device asks, an admin approves after comparing the fingerprint.
        val identity = SoftwareIdentity.generate()
        val enrollment = EnrollmentClient(url)
        val ticket = enrollment.request(identity, "door", "kotlin-e2e")
        assertEquals("pending", enrollment.status(ticket.deviceId))
        val waiting = admin("GET", "/devices/requests")!!["items"]!!.toString()
        assertTrue(ticket.fingerprint in waiting, "the console shows the same fingerprint the device computed")
        admin("POST", "/devices/requests/${ticket.deviceId}/approve", """{"name":"Kotlin e2e door"}""")
        assertEquals("active", enrollment.status(ticket.deviceId))

        // 2. Handshake against the real gateway, then the controller on top of it.
        val storage = FileStorage(Files.createTempDirectory("e2e-state").toFile())
        val player = RecordingPlayer()
        val assets = AssetDownloader(url, ticket.deviceId, identity, Files.createTempDirectory("e2e-cache").toFile())
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val controller = DoorController(storage, assets, player, scope)
        val events = mutableListOf<LinkEvent>()
        val link = DeviceLink(url, ticket.deviceId, identity, serverKey)
        val running = scope.launch {
            link.run { event ->
                synchronized(events) { events += event }
                controller.onEvent(event)
            }
        }
        until(what = "handshake completes and the full desired state arrives") { controller.online.value && controller.door.value.lastSyncAt != null }

        // 3. Commands from the console reach the controller and change the state.
        admin("PUT", "/devices/${ticket.deviceId}/display-config", """{"layout":"split","theme":"light"}""")
        until(what = "display config applied") { controller.door.value.config.layout == "split" && controller.door.value.config.theme == "light" }
        admin("POST", "/announcements", """{"title":"Fire drill","text":"10:30 outside","priority":"urgent","targets":{"devices":["${ticket.deviceId}"]}}""")
        until(what = "announcement shown") { controller.door.value.visibleAnnouncements(System.currentTimeMillis()).any { it.priority == "urgent" } }
        admin("PUT", "/devices/${ticket.deviceId}/audio-settings", """{"volume":42,"quietHours":{"enabled":false,"from":"22:00","to":"06:00"}}""")
        until(what = "audio settings applied") { controller.door.value.audio.volume == 42 }

        // 4. Every command was acknowledged, and the reported state reached the server.
        until(what = "all commands acknowledged") {
            admin("GET", "/devices/${ticket.deviceId}/commands")!!["items"]!!.toString().let { "\"status\":\"pending\"" !in it && "\"status\":\"sent\"" !in it }
        }
        val device = admin("GET", "/devices/${ticket.deviceId}")!!
        assertEquals("on", device["reported"]!!.jsonObject["screen"]!!.jsonPrimitive.content)

        // 5. Audio: upload, broadcast, then a signed download whose hash the device verifies.
        val wav = Base64.getDecoder().decode(
            "UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAIA+AAACABAAZGF0YQAAAAA=",
        )
        val asset = admin("POST", "/assets?name=e2e-chime", raw = wav, type = "audio/wav")!!
        admin("POST", "/intercom/broadcasts", """{"assetId":"${asset["id"]!!.jsonPrimitive.content}","targets":{"devices":["${ticket.deviceId}"]}}""")
        until(what = "audio downloaded, verified and played") { player.played.isNotEmpty() }
        assertEquals(wav.size.toLong() to 42, player.played.single())

        // 6. Revocation closes the socket and the link stops by itself.
        admin("POST", "/devices/${ticket.deviceId}/revoke")
        withTimeout(8_000) { running.join() }
        assertTrue(synchronized(events) { events.filterIsInstance<LinkEvent.Disconnected>().last().revoked })
        scope.coroutineContext[Job]?.cancel()
        Unit
    }
}
