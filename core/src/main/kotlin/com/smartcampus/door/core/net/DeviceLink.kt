package com.smartcampus.door.core.net

import com.smartcampus.door.core.crypto.ChannelException
import com.smartcampus.door.core.crypto.Challenge
import com.smartcampus.door.core.crypto.DeviceHandshake
import com.smartcampus.door.core.crypto.DeviceIdentity
import com.smartcampus.door.core.crypto.HandshakeException
import com.smartcampus.door.core.crypto.SecureChannel
import com.smartcampus.door.core.newUlid
import com.smartcampus.door.core.protocol.PROTOCOL_VERSION
import com.smartcampus.door.core.protocol.ProtocolException
import com.smartcampus.door.core.protocol.ServerMessage
import com.smartcampus.door.core.protocol.buildEnvelope
import com.smartcampus.door.core.protocol.parseServerMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.security.PublicKey
import kotlin.random.Random

/** Close codes the server uses (application range 4000-4999). */
object CloseCode {
    const val AUTH_FAILED = 4401
    const val REVOKED = 4403
    const val REPLACED = 4409
}

/** Where the controller sends acknowledgements and reports. A seam so it can be tested without a socket. */
interface MessageSink {
    suspend fun send(type: String, payload: JsonElement, id: String = newUlid())
}

sealed interface LinkEvent {
    /** The handshake succeeded; the first message (session.ready) follows. */
    data class Connected(val session: MessageSink) : LinkEvent

    data class Message(val message: ServerMessage) : LinkEvent

    /** The connection ended. [revoked] means the server removed this device for good: stop reconnecting. */
    data class Disconnected(val code: Int, val reason: String, val revoked: Boolean) : LinkEvent
}

/** The authenticated, encrypted side of a connection. Sends are serialised: the AES counter must follow the send order. */
class LinkSession internal constructor(private val socket: WebSocket, private val channel: SecureChannel) : MessageSink {
    private val lock = Mutex()
    private var seq = 0L

    override suspend fun send(type: String, payload: JsonElement, id: String) {
        lock.withLock {
            val frame = channel.encrypt(buildEnvelope(id, type, ++seq, payload))
            if (!socket.send(ByteString.of(*frame))) throw java.io.IOException("connection is closed")
        }
    }
}

private sealed interface Raw {
    data class Text(val value: String) : Raw
    data class Binary(val value: ByteArray) : Raw
    data class Closed(val code: Int, val reason: String) : Raw
}

private const val HANDSHAKE_TIMEOUT_MS = 10_000L
private const val BACKOFF_MIN_MS = 1_000L
private const val BACKOFF_MAX_MS = 60_000L

/**
 * One persistent connection to /ws/device. Devices always dial out; after any failure the link waits
 * 1 s, then 2 s, 4 s ... up to 60 s, with jitter so a whole building coming back does not stampede the server.
 */
class DeviceLink(
    private val baseUrl: String,
    private val deviceId: String,
    private val identity: DeviceIdentity,
    private val serverPublicKey: PublicKey,
    private val client: OkHttpClient = OkHttpClient(),
) {
    /** Connects and reconnects until cancelled or revoked. Events are delivered one at a time, in order. */
    suspend fun run(onEvent: suspend (LinkEvent) -> Unit) {
        var backoff = BACKOFF_MIN_MS
        while (true) {
            var reachedReady = false
            val closing = try {
                connectOnce({ reachedReady = true }, onEvent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HandshakeException) {
                Raw.Closed(-1, "handshake failed: ${e.message}")
            } catch (e: ChannelException) {
                Raw.Closed(-1, "channel error: ${e.message}")
            } catch (e: ProtocolException) {
                Raw.Closed(-1, "protocol error: ${e.message}")
            } catch (e: Exception) {
                Raw.Closed(-1, e.message ?: e.javaClass.simpleName)
            }
            val revoked = closing.code == CloseCode.REVOKED
            onEvent(LinkEvent.Disconnected(closing.code, closing.reason, revoked))
            if (revoked) return

            if (reachedReady) backoff = BACKOFF_MIN_MS
            delay(Random.nextLong(backoff / 2, backoff + 1))
            backoff = minOf(backoff * 2, BACKOFF_MAX_MS)
        }
    }

    private suspend fun connectOnce(onReady: () -> Unit, onEvent: suspend (LinkEvent) -> Unit): Raw.Closed {
        val inbox = Channel<Raw>(Channel.UNLIMITED)
        val socket = client.newWebSocket(Request.Builder().url(webSocketUrl()).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                inbox.trySend(Raw.Text(text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                inbox.trySend(Raw.Binary(bytes.toByteArray()))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                inbox.trySend(Raw.Closed(code, reason))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                inbox.trySend(Raw.Closed(code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                inbox.trySend(Raw.Closed(-1, t.message ?: "connection failed"))
            }
        })

        try {
            val (session, channel) = handshake(socket, inbox) ?: return awaitClose(inbox)
            onReady()
            onEvent(LinkEvent.Connected(session))

            var lastSeq = 0L
            while (true) {
                when (val raw = inbox.receive()) {
                    is Raw.Closed -> return raw
                    is Raw.Text -> throw ProtocolException("unexpected text frame after the handshake")
                    is Raw.Binary -> {
                        val (message, seq) = parseServerMessage(channel.decrypt(raw.value))
                        if (seq != lastSeq + 1) throw ProtocolException("message out of sequence")
                        lastSeq = seq
                        onEvent(LinkEvent.Message(message))
                    }
                }
            }
        } finally {
            socket.close(1000, null)
            inbox.close()
        }
    }

    /** Returns null when the server closed during the handshake (the close code is then read from the inbox). */
    private suspend fun handshake(socket: WebSocket, inbox: Channel<Raw>): Pair<LinkSession, SecureChannel>? {
        val handshake = DeviceHandshake(deviceId, identity, serverPublicKey)
        val hello = handshake.hello()
        socket.send(
            buildJsonObject {
                put("type", "hello")
                put("v", PROTOCOL_VERSION)
                put("deviceId", hello.deviceId)
                put("nonceA", hello.nonceA)
                put("ephA", hello.ephA)
            }.toString(),
        )

        val reply = withTimeout(HANDSHAKE_TIMEOUT_MS) { inbox.receive() }
        val challengeText = when (reply) {
            is Raw.Text -> reply.value
            is Raw.Closed -> { inbox.trySend(reply); return null }
            is Raw.Binary -> throw HandshakeException("unexpected binary frame during the handshake")
        }
        val json = Json.parseToJsonElement(challengeText).jsonObject
        val challenge = Challenge(
            json["nonceS"]!!.jsonPrimitive.content,
            json["ephS"]!!.jsonPrimitive.content,
            json["sigS"]!!.jsonPrimitive.content,
        )
        val (sigA, channel) = handshake.onChallenge(challenge)
        socket.send(buildJsonObject { put("type", "auth"); put("sigA", sigA) }.toString())
        return LinkSession(socket, channel) to channel
    }

    private suspend fun awaitClose(inbox: Channel<Raw>): Raw.Closed {
        while (true) {
            val raw = withTimeout(HANDSHAKE_TIMEOUT_MS) { inbox.receive() }
            if (raw is Raw.Closed) return raw
        }
    }

    private fun webSocketUrl(): String = baseUrl.trimEnd('/').replaceFirst("http", "ws") + "/ws/device"
}

private val Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
