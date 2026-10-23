package com.smartcampus.door.core.state

import com.smartcampus.door.core.protocol.Announcement
import com.smartcampus.door.core.protocol.AudioSettings
import com.smartcampus.door.core.protocol.DisplayConfig
import com.smartcampus.door.core.protocol.Emergency
import com.smartcampus.door.core.protocol.RoomInfo
import com.smartcampus.door.core.protocol.RoomStatus
import com.smartcampus.door.core.protocol.ServerMessage
import com.smartcampus.door.core.protocol.protocolJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Everything the door screen needs, persisted after every change so that a restart or a network
 * outage keeps showing the last known state (offline mode).
 */
@Serializable
data class DoorState(
    val room: RoomInfo? = null,
    val status: RoomStatus? = null,
    val announcements: Map<String, Announcement> = emptyMap(),
    val config: DisplayConfig = DisplayConfig(),
    val audio: AudioSettings = AudioSettings(),
    /** Shown over everything else, with an alarm, until the server clears it. Persisted so a restart keeps it. */
    val emergency: Emergency? = null,
    /** serverTime - deviceTime at the last session.ready. Expiry uses it so a wrong device clock cannot show stale announcements. */
    val clockOffsetMs: Long = 0,
    val lastSyncAt: Long? = null,
) {
    /** Announcements still valid at [deviceNowMs], most urgent first. */
    fun visibleAnnouncements(deviceNowMs: Long): List<Announcement> {
        val serverNow = deviceNowMs + clockOffsetMs
        return announcements.values
            .filter { it.expiresAt == null || it.expiresAt > serverNow }
            .sortedWith(compareByDescending<Announcement> { priorityRank(it.priority) }.thenBy { it.announcementId })
    }

    /** Applies the full desired state sent at the start of a session. It replaces what the server manages. */
    fun withDesired(desired: JsonObject, serverTime: Long, deviceNowMs: Long): DoorState {
        fun <T> field(name: String, decode: (JsonObject) -> T): T? = desired[name]?.jsonObject?.let(decode)
        return copy(
            room = field("room") { protocolJson.decodeFromJsonElement<RoomInfo>(it) } ?: room,
            status = field("roomStatus") { protocolJson.decodeFromJsonElement<RoomStatus>(it) } ?: status,
            config = field("displayConfig") { protocolJson.decodeFromJsonElement<DisplayConfig>(it) } ?: config,
            audio = field("audioSettings") { protocolJson.decodeFromJsonElement<AudioSettings>(it) } ?: audio,
            // The desired state is complete: no `emergency` (or null) means the alert is over, even if we missed the clear.
            emergency = desired["emergency"]?.takeIf { it is JsonObject }?.let { protocolJson.decodeFromJsonElement<Emergency>(it) },
            announcements = field("announcements") { map ->
                map.values.associate { value ->
                    val announcement = protocolJson.decodeFromJsonElement<Announcement>(value)
                    announcement.announcementId to announcement
                }
            } ?: announcements,
            clockOffsetMs = serverTime - deviceNowMs,
            lastSyncAt = deviceNowMs,
        )
    }

    /** Applies one command. Commands that do not change what is shown (audio, ignored) return the state as is. */
    fun apply(message: ServerMessage): DoorState =
        when (message) {
            is ServerMessage.RoomStatusSet -> copy(status = message.value)
            is ServerMessage.RoomInfoSet -> copy(room = message.value)
            is ServerMessage.DisplayConfigSet -> copy(config = message.config)
            is ServerMessage.AudioSettingsSet -> copy(audio = message.value)
            is ServerMessage.AnnounceShow -> copy(announcements = announcements + (message.value.announcementId to message.value))
            is ServerMessage.AnnounceClear -> copy(announcements = announcements - message.announcementId)
            is ServerMessage.EmergencySet -> copy(emergency = message.value)
            // A clear for an alert we no longer show (a newer one took over) must not end the newer one.
            is ServerMessage.EmergencyClear -> if (emergency?.emergencyId == message.emergencyId) copy(emergency = null) else this
            else -> this
        }

    /** Drops expired announcements so the persisted state does not grow forever. */
    fun withoutExpired(deviceNowMs: Long): DoorState {
        val visible = visibleAnnouncements(deviceNowMs).map { it.announcementId }.toSet()
        return if (visible.size == announcements.size) this else copy(announcements = announcements.filterKeys { it in visible })
    }

    private fun priorityRank(priority: String) = when (priority) {
        "urgent" -> 2
        "important" -> 1
        else -> 0
    }
}

/** Wall clock time of day as the room sees it: the device clock corrected by the server offset. */
fun DoorState.localTime(deviceNowMs: Long, zone: java.time.ZoneId): java.time.LocalTime =
    java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(deviceNowMs + clockOffsetMs), zone)

/** True while the night sleep window is on: the screen goes black. */
fun DoorState.isAsleep(deviceNowMs: Long, zone: java.time.ZoneId): Boolean = config.sleep.contains(localTime(deviceNowMs, zone))
