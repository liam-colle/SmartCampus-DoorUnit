package com.smartcampus.door.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

const val PROTOCOL_VERSION = 1

/** Largest frame the server accepts, before and after encryption. */
const val MAX_FRAME_BYTES = 64 * 1024

val protocolJson = Json {
    ignoreUnknownKeys = true
    // Defaults are part of the contract: a partial display config from the server is valid.
    coerceInputValues = true
    encodeDefaults = true
}

@Serializable
data class TimeWindow(val enabled: Boolean = false, val from: String = "22:00", val to: String = "06:00")

@Serializable
data class DisplayShow(
    val roomName: Boolean = true,
    val building: Boolean = true,
    val floor: Boolean = true,
    val capacity: Boolean = true,
    val clock: Boolean = true,
    val announcements: Boolean = true,
)

@Serializable
data class DisplayConfig(
    val layout: String = "standard",
    val theme: String = "dark",
    val accentColor: String = "#00a86b",
    val show: DisplayShow = DisplayShow(),
    val sleep: TimeWindow = TimeWindow(),
)

@Serializable
data class AudioSettings(val volume: Int = 80, val quietHours: TimeWindow = TimeWindow())

@Serializable
data class RoomStatus(
    val roomId: String,
    val status: String,
    val label: String,
    val color: String,
    val icon: String,
    val since: Long? = null,
)

@Serializable
data class RoomInfo(
    val roomId: String,
    val code: String,
    val name: String = "",
    val building: String = "",
    val floor: Int = 0,
    val capacity: Int? = null,
)

@Serializable
data class Announcement(
    val announcementId: String,
    val title: String? = null,
    val text: String,
    val priority: String = "info",
    val expiresAt: Long? = null,
)

@Serializable
data class AudioPlay(
    val announcementId: String,
    val title: String? = null,
    val assetId: String,
    val sha256: String,
    val size: Long,
)

/** An emergency takes over the screen and sounds an alarm until it is cleared. */
@Serializable
data class Emergency(
    val emergencyId: String,
    val kind: String,
    val title: String,
    val instructions: String,
    val testMode: Boolean = false,
    val testVolume: Int = 20,
    val startedAt: Long = 0,
)

/** What the server sends after the handshake. Only these types exist for a door unit. */
sealed interface ServerMessage {
    val id: String

    data class SessionReady(override val id: String, val desired: JsonObject, val serverTime: Long, val pendingCommands: Int) : ServerMessage
    data class RoomStatusSet(override val id: String, val value: RoomStatus) : ServerMessage
    data class RoomInfoSet(override val id: String, val value: RoomInfo) : ServerMessage
    data class DisplayConfigSet(override val id: String, val config: DisplayConfig) : ServerMessage
    data class AudioSettingsSet(override val id: String, val value: AudioSettings) : ServerMessage
    data class AnnounceShow(override val id: String, val value: Announcement) : ServerMessage
    data class AnnounceClear(override val id: String, val announcementId: String) : ServerMessage
    data class AudioPlayRequest(override val id: String, val value: AudioPlay) : ServerMessage
    data class EmergencySet(override val id: String, val value: Emergency) : ServerMessage
    data class EmergencyClear(override val id: String, val emergencyId: String) : ServerMessage

    /** A valid command for another kind of device (e.g. access.acl.sync): acknowledged, not acted on. */
    data class Ignored(override val id: String, val type: String) : ServerMessage
}

class ProtocolException(message: String) : Exception(message)

/** Parses a decrypted frame. Throws [ProtocolException] for anything malformed. */
fun parseServerMessage(frame: ByteArray): Pair<ServerMessage, Long> {
    if (frame.size > MAX_FRAME_BYTES) throw ProtocolException("frame too large")
    val root = try {
        protocolJson.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject
    } catch (e: Exception) {
        throw ProtocolException("invalid JSON")
    }
    val id = root["id"]?.jsonPrimitive?.contentOrNull ?: throw ProtocolException("missing id")
    val type = root["type"]?.jsonPrimitive?.contentOrNull ?: throw ProtocolException("missing type")
    val seq = root["seq"]?.jsonPrimitive?.long ?: throw ProtocolException("missing seq")
    val payload = root["payload"]?.jsonObject ?: throw ProtocolException("missing payload")

    val message = try {
        decode(id, type, payload)
    } catch (e: kotlinx.serialization.SerializationException) {
        throw ProtocolException("bad $type payload: ${e.message}")
    }
    return message to seq
}

private inline fun <reified T> JsonObject.decode(): T = protocolJson.decodeFromJsonElement<T>(this)

private fun decode(id: String, type: String, payload: JsonObject): ServerMessage =
    when (type) {
        "session.ready" -> ServerMessage.SessionReady(
            id,
            payload["desired"]?.jsonObject ?: JsonObject(emptyMap()),
            payload["serverTime"]?.jsonPrimitive?.long ?: 0,
            payload["pendingCommands"]?.jsonPrimitive?.long?.toInt() ?: 0,
        )
        "room.status.set" -> ServerMessage.RoomStatusSet(id, payload.decode())
        "room.info.set" -> ServerMessage.RoomInfoSet(id, payload.decode())
        "display.config.set" -> ServerMessage.DisplayConfigSet(id, payload["config"]!!.jsonObject.decode())
        "audio.settings.set" -> ServerMessage.AudioSettingsSet(id, payload.decode())
        "announce.show" -> ServerMessage.AnnounceShow(id, payload.decode())
        "announce.clear" -> ServerMessage.AnnounceClear(id, payload["announcementId"]!!.jsonPrimitive.content)
        "audio.play" -> ServerMessage.AudioPlayRequest(id, payload.decode())
        "emergency.set" -> ServerMessage.EmergencySet(id, payload.decode())
        "emergency.clear" -> ServerMessage.EmergencyClear(id, payload["emergencyId"]!!.jsonPrimitive.content)
        else -> ServerMessage.Ignored(id, type)
    }

/** Builds the plaintext envelope of a device message. `seq` starts at 1 per connection. */
fun buildEnvelope(id: String, type: String, seq: Long, payload: JsonElement, nowMs: Long = System.currentTimeMillis()): ByteArray =
    buildJsonObject {
        put("v", PROTOCOL_VERSION)
        put("id", id)
        put("type", type)
        put("ts", nowMs)
        put("seq", seq)
        put("payload", payload)
    }.toString().toByteArray(Charsets.UTF_8)

fun ackPayload(ref: String): JsonObject = buildJsonObject { put("ref", ref) }

fun nackPayload(ref: String, reason: String): JsonObject = buildJsonObject {
    put("ref", ref)
    put("reason", reason.take(200))
}

fun statePayload(state: JsonObject): JsonObject = buildJsonObject { put("state", state) }

