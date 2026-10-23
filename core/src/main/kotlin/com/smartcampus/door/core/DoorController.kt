package com.smartcampus.door.core

import com.smartcampus.door.core.audio.AssetSource
import com.smartcampus.door.core.audio.AlarmPlayer
import com.smartcampus.door.core.audio.AudioPlayer
import com.smartcampus.door.core.audio.NoAlarm
import com.smartcampus.door.core.net.LinkEvent
import com.smartcampus.door.core.net.MessageSink
import com.smartcampus.door.core.protocol.Emergency
import com.smartcampus.door.core.protocol.ServerMessage
import com.smartcampus.door.core.protocol.ackPayload
import com.smartcampus.door.core.protocol.nackPayload
import com.smartcampus.door.core.protocol.protocolJson
import com.smartcampus.door.core.protocol.statePayload
import com.smartcampus.door.core.state.DoorState
import com.smartcampus.door.core.state.contains
import com.smartcampus.door.core.state.isAsleep
import com.smartcampus.door.core.state.localTime
import com.smartcampus.door.core.storage.FileStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId

/** The alarm loudness of an emergency, 0..1. A power cut is only a heads-up, so it is much softer than a danger alarm. */
fun alarmLevel(emergency: Emergency, inQuietHours: Boolean): Float = when {
    emergency.testMode -> if (inQuietHours) 0f else emergency.testVolume.coerceIn(0, 100) / 100f
    emergency.kind == "power" -> POWER_CUT_LEVEL
    else -> 1f
}

private const val POWER_CUT_LEVEL = 0.35f

/** Recent command ids kept so a replayed command is acknowledged again but never run twice. */
private const val REMEMBERED_IDS = 200

/**
 * The brain of the door unit: turns what the server sends into the state the screen shows, keeps
 * that state on disk (offline mode), acknowledges every command once and reports back.
 */
class DoorController(
    private val storage: FileStorage,
    private val assets: AssetSource,
    private val player: AudioPlayer,
    private val scope: CoroutineScope,
    private val alarm: AlarmPlayer = NoAlarm,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _door = MutableStateFlow(loadState())
    private val _online = MutableStateFlow(false)
    private val seenIds: ArrayDeque<String> = ArrayDeque(loadSeenIds())
    private var session: MessageSink? = null
    private var offlineSince: Long? = clock()

    init {
        // A restart in the middle of an emergency must not silence it: it ends only when the server says so.
        _door.value.emergency?.let(::soundAlarm)
    }

    val door: StateFlow<DoorState> = _door.asStateFlow()
    val online: StateFlow<Boolean> = _online.asStateFlow()

    suspend fun onEvent(event: LinkEvent) {
        when (event) {
            is LinkEvent.Connected -> {
                session = event.session
                offlineSince = null
                _online.value = true
            }
            is LinkEvent.Disconnected -> {
                session = null
                offlineSince = offlineSince ?: clock()
                _online.value = false
            }
            is LinkEvent.Message -> handle(event.message)
        }
    }

    private suspend fun handle(message: ServerMessage) {
        if (message is ServerMessage.SessionReady) {
            update(_door.value.withDesired(message.desired, message.serverTime, clock()))
            report()
            return
        }
        if (message.id in seenIds) {
            acknowledge(message.id) // the server did not see our first ack: repeat it, do not run the command again
            return
        }
        if (message is ServerMessage.AudioPlayRequest) {
            handleAudio(message)
            return
        }
        update(_door.value.apply(message))
        remember(message.id)
        acknowledge(message.id)
        report()
    }

    private suspend fun handleAudio(message: ServerMessage.AudioPlayRequest) {
        val request = message.value
        val state = _door.value
        if (state.audio.quietHours.contains(state.localTime(clock(), zone))) {
            // Acknowledged on purpose: the server must not replay a broadcast we deliberately stayed silent for.
            remember(message.id)
            acknowledge(message.id)
            return
        }
        val file = try {
            assets.fetch(request.assetId, request.sha256, request.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            remember(message.id)
            session?.send("nack", nackPayload(message.id, "download failed: ${e.message}"))
            return
        }
        remember(message.id)
        acknowledge(message.id)
        scope.launch { runCatching { player.play(file, state.audio.volume) } }
    }

    /** Sends the state the console draws its live preview from. */
    suspend fun report() {
        val now = clock()
        val state = _door.value
        val payload = buildJsonObject {
            put("roomStatus", state.status?.status ?: "")
            put("announcements", JsonArray(state.visibleAnnouncements(now).map { JsonPrimitive(it.announcementId) }))
            put("configApplied", true)
            state.emergency?.let { put("emergency", it.emergencyId) }
            put("screen", if (state.isAsleep(now, zone)) "sleep" else "on")
            put("volume", state.audio.volume)
            offlineSince?.let { put("offlineSince", it) }
        }
        session?.send("state.report", statePayload(payload))
    }

    private suspend fun acknowledge(ref: String) {
        session?.send("ack", ackPayload(ref))
    }

    private fun update(state: DoorState) {
        val pruned = state.withoutExpired(clock())
        val before = _door.value.emergency
        _door.value = pruned
        if (before != pruned.emergency) {
            val current = pruned.emergency
            if (current != null) soundAlarm(current) else alarm.stop()
        }
        storage.write(STATE_KEY, protocolJson.encodeToString(DoorState.serializer(), pruned))
    }

    /**
     * A real emergency overrides the volume setting and quiet hours: people must hear it. A test
     * plays at its own volume and stays silent in quiet hours, so a test never wakes anyone.
     */
    private fun soundAlarm(emergency: Emergency) {
        val state = _door.value
        val quiet = state.audio.quietHours.contains(state.localTime(clock(), zone))
        val level = alarmLevel(emergency, quiet)
        if (level > 0f) alarm.start(emergency.kind, level) else alarm.stop()
    }

    private fun remember(id: String) {
        seenIds.addLast(id)
        while (seenIds.size > REMEMBERED_IDS) seenIds.removeFirst()
        storage.write(SEEN_KEY, protocolJson.encodeToString(ListSerializer(String.serializer()), seenIds.toList()))
    }

    private fun loadState(): DoorState =
        storage.read(STATE_KEY)?.let { runCatching { protocolJson.decodeFromString(DoorState.serializer(), it) }.getOrNull() } ?: DoorState()

    private fun loadSeenIds(): List<String> =
        storage.read(SEEN_KEY)?.let { runCatching { protocolJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty()

    private companion object {
        const val STATE_KEY = "state.json"
        const val SEEN_KEY = "seen-ids.json"
    }
}
