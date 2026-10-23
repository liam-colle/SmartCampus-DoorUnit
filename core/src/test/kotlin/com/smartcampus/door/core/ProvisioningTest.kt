package com.smartcampus.door.core

import com.smartcampus.door.core.crypto.fingerprintOf
import com.smartcampus.door.core.crypto.SoftwareIdentity
import com.smartcampus.door.core.provisioning.ProvisioningException
import com.smartcampus.door.core.provisioning.ServerKeyClient
import com.smartcampus.door.core.provisioning.parseProvisioningLink
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProvisioningTest {
    private val spki = SoftwareIdentity.generate().publicKeySpki
    private val key = Base64.getEncoder().encodeToString(spki)

    private fun link(url: String = "http://192.168.1.20:3000", k: String = key) =
        "smartcampus://provision?url=${URLEncoder.encode(url, "UTF-8")}&key=${URLEncoder.encode(k, "UTF-8")}"

    @Test
    fun reads_the_code_the_console_shows_including_plus_and_slash_in_the_key() {
        val parsed = parseProvisioningLink(link("http://192.168.1.20:3000/"))
        assertEquals("http://192.168.1.20:3000", parsed.serverUrl)
        assertEquals(key, parsed.serverPublicKey)
    }

    @Test
    fun reads_a_code_produced_by_the_console_encoder() {
        // Produced by the console with URLSearchParams, for a real server key (it contains + and /).
        val code = "smartcampus://provision?url=http%3A%2F%2F192.168.1.20%3A3000&key=MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAETktns1PhLGI8PG4tU28rpQXHxu4g%2Fk0dGCMGGo3Eq%2Bxl0r9iBn%2F0ccDZPP7hqTGxeR6HdKBXziorJ%2B7USwl3rg%3D%3D"
        val parsed = parseProvisioningLink(code)
        assertEquals("http://192.168.1.20:3000", parsed.serverUrl)
        assertEquals("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAETktns1PhLGI8PG4tU28rpQXHxu4g/k0dGCMGGo3Eq+xl0r9iBn/0ccDZPP7hqTGxeR6HdKBXziorJ+7USwl3rg==", parsed.serverPublicKey)
    }

    @Test
    fun refuses_anything_that_is_not_a_valid_code() {
        assertFailsWith<ProvisioningException> { parseProvisioningLink("https://example.org/?url=x&key=y") }
        assertFailsWith<ProvisioningException> { parseProvisioningLink(link(url = "not a url")) }
        assertFailsWith<ProvisioningException> { parseProvisioningLink(link(k = "AAAA")) }
        assertFailsWith<ProvisioningException> { parseProvisioningLink(link(k = "")) }
        assertFailsWith<ProvisioningException> { parseProvisioningLink("smartcampus://provision") }
    }

    private fun serve(status: Int, body: String, block: suspend (String) -> Unit) = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/server-key") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun fetches_the_key_and_works_out_the_fingerprint_itself() = serve(200, """{"publicKey":"$key","fingerprint":"0000"}""") { url ->
        val info = ServerKeyClient().fetch(url)
        assertEquals(key, info.publicKey)
        // The server also sent a fingerprint ("0000"): the terminal must not take its word for it.
        assertEquals(fingerprintOf(spki), info.fingerprint)
    }

    @Test
    fun refuses_a_bad_answer() {
        serve(200, """{"publicKey":"AAAA"}""") { url -> assertFailsWith<ProvisioningException> { ServerKeyClient().fetch(url) } }
        serve(200, "not json") { url -> assertFailsWith<ProvisioningException> { ServerKeyClient().fetch(url) } }
        serve(500, "{}") { url -> assertFailsWith<ProvisioningException> { ServerKeyClient().fetch(url) } }
        serve(200, "x".repeat(10_000)) { url -> assertFailsWith<ProvisioningException> { ServerKeyClient().fetch(url) } }
    }

    @Test
    fun says_so_when_the_server_cannot_be_reached() = runTest {
        assertFailsWith<ProvisioningException> { ServerKeyClient().fetch("http://127.0.0.1:1") }
    }
}
