package com.smartcampus.door.core

import com.smartcampus.door.core.audio.AssetSource
import com.smartcampus.door.core.audio.AlarmPlayer
import com.smartcampus.door.core.audio.AudioPlayer
import com.smartcampus.door.core.audio.CardTone
import com.smartcampus.door.core.audio.CardTones
import com.smartcampus.door.core.audio.NoTones
import com.smartcampus.door.core.audio.NoAlarm
import com.smartcampus.door.core.net.LinkEvent
import com.smartcampus.door.core.net.MessageSink
import com.smartcampus.door.core.protocol.CardOutcome
import com.smartcampus.door.core.protocol.CardMenu
import com.smartcampus.door.core.protocol.Emergency
import com.smartcampus.door.core.protocol.ServerMessage
import com.smartcampus.door.core.protocol.ackPayload
import com.smartcampus.door.core.protocol.badgePayload
import com.smartcampus.door.core.protocol.maintenanceActionPayload
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

/** The same card read twice within this time (a card resting on the antenna) is one tap. */
private const val TAP_DEBOUNCE_MS = 2_000L

/** An answer to a tap that comes later than this is stale: the person has walked away. */
private const val CARD_RESULT_MAX_AGE_MS = 10_000L

/** Taps still waiting for an answer. Bounded so a server that never answers cannot grow the map. */
private const val MAX_PENDING_TAPS = 16

/** Someone whose card the server accepted for the maintenance menu, and what the server lets them do. */
data class MaintenanceSession(val name: String, val openedAt: Long, val menu: CardMenu = CardMenu())

/**
 * The last card presented and what the server made of it, for the screen that confirms the read.
 * [outcome] is the server's, or one of the two the unit makes up itself.
 */
data class CardEvent(val outcome: String, val name: String?, val at: Long) {
    /** False while the server has not answered yet, or when it could not be asked. */
    val granted: Boolean get() = isAccessGranted(outcome)
}

/** Shown instead of a server outcome when the unit cannot reach the server at all. */
const val OUTCOME_OFFLINE = "offline"

/** The card was read and the server has not answered yet. */
const val OUTCOME_CHECKING = "checking"

/** Outcomes that mean the card was accepted for something: the menu, a greeting, or registration. */
fun isAccessGranted(outcome: String): Boolean =
    outcome == CardOutcome.MAINTENANCE || outcome == CardOutcome.RECOGNISED || outcome == CardOutcome.CAPTURED

/** What the server said about the last menu action; the menu shows it under the buttons. */
data class ActionOutcome(val action: String, val ok: Boolean, val error: String?)

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
    private val tones: CardTones = NoTones,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _door = MutableStateFlow(loadState())
    private val _online = MutableStateFlow(false)
    private val seenIds: ArrayDeque<String> = ArrayDeque(loadSeenIds())
    private var session: MessageSink? = null
    private var offlineSince: Long? = clock()
    private val _maintenance = MutableStateFlow<MaintenanceSession?>(null)
    private val _lastCard = MutableStateFlow<CardEvent?>(null)
    private val _actionOutcome = MutableStateFlow<ActionOutcome?>(null)
    private val pendingTaps = LinkedHashMap<String, Long>()
    private val pendingActions = LinkedHashMap<String, String>()
    private var lastTap: Pair<String, Long>? = null

    init {
        // A restart in the middle of an emergency must not silence it: it ends only when the server says so.
        _door.value.emergency?.let(::soundAlarm)
    }

    val door: StateFlow<DoorState> = _door.asStateFlow()
    val online: StateFlow<Boolean> = _online.asStateFlow()

    /** Non-null while the maintenance menu is open. */
    val maintenance: StateFlow<MaintenanceSession?> = _maintenance.asStateFlow()
    val lastCard: StateFlow<CardEvent?> = _lastCard.asStateFlow()
    val actionOutcome: StateFlow<ActionOutcome?> = _actionOutcome.asStateFlow()

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
                // The menu was opened on the server's word: without the link it is no longer vouched for.
                _maintenance.value = null
                pendingTaps.clear()
                pendingActions.clear()
            }
            is LinkEvent.Message -> handle(event.message)
        }
    }

    /**
     * A card was read by the NFC antenna. The unit never decides by itself who may open the menu: it
     * forwards the serial number and waits for the server's `card.result`. Offline, the answer is
     * simply "no", and the unit says so.
     */
    suspend fun onCardRead(cardUid: String) {
        val now = clock()
        val previous = lastTap
        lastTap = cardUid to now
        if (previous != null && previous.first == cardUid && now - previous.second < TAP_DEBOUNCE_MS) return

        playTone(CardTone.READ)
        val sink = session
        if (sink == null) {
            _lastCard.value = CardEvent(OUTCOME_OFFLINE, null, now)
            playTone(CardTone.DENIED)
            return
        }
        _lastCard.value = CardEvent(OUTCOME_CHECKING, null, now)
        val id = newUlid(now)
        pendingTaps[id] = now
        while (pendingTaps.size > MAX_PENDING_TAPS) pendingTaps.remove(pendingTaps.keys.first())
        sink.send("event.badge", badgePayload(cardUid, now), id)
    }

    fun closeMaintenance() {
        _maintenance.value = null
        _actionOutcome.value = null
        pendingActions.clear()
    }

    /**
     * Asks the server to run a menu action. The server checks it against the card that opened the menu,
     * so a button the person may not press is refused there even if this screen showed it.
     */
    suspend fun maintenanceAction(action: String, status: String? = null) {
        val sink = session ?: return
        val id = newUlid(clock())
        pendingActions[id] = action
        while (pendingActions.size > MAX_PENDING_TAPS) pendingActions.remove(pendingActions.keys.first())
        _actionOutcome.value = null
        sink.send("maintenance.action", maintenanceActionPayload(action, status), id)
    }

    private fun handleMaintenanceResult(result: ServerMessage.MaintenanceResult) {
        val action = pendingActions.remove(result.ref) ?: return
        _actionOutcome.value = ActionOutcome(action, result.ok, result.error)
        playTone(if (result.ok) CardTone.GRANTED else CardTone.DENIED)
    }

    /** Silent during an emergency (the alarm owns the speaker) and in quiet hours, and follows the volume setting. */
    private fun playTone(tone: CardTone) {
        val state = _door.value
        if (state.emergency != null || state.audio.quietHours.contains(state.localTime(clock(), zone))) return
        val level = state.audio.volume.coerceIn(0, 100) / 100f
        if (level > 0f) tones.play(tone, level)
    }

    private fun handleCardResult(result: ServerMessage.CardResult) {
        val now = clock()
        // Only an answer to a tap we made, and recent: anything else is replayed, forged or late.
        val sentAt = pendingTaps.remove(result.ref) ?: return
        if (now - sentAt > CARD_RESULT_MAX_AGE_MS) return

        val event = CardEvent(result.outcome, result.name, now)
        _lastCard.value = event
        playTone(if (event.granted) CardTone.GRANTED else CardTone.DENIED)
        // An emergency owns the screen: nobody should be able to cover it with a menu.
        val allowed = result.maintenance && result.outcome == CardOutcome.MAINTENANCE && _door.value.emergency == null
        if (allowed) _maintenance.value = MaintenanceSession(result.name.orEmpty(), now, result.menu ?: CardMenu())
    }

    private suspend fun handle(message: ServerMessage) {
        if (message is ServerMessage.CardResult) {
            handleCardResult(message)
            return
        }
        if (message is ServerMessage.MaintenanceResult) {
            handleMaintenanceResult(message)
            return
        }
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
            if (current != null) {
                _maintenance.value = null
                soundAlarm(current)
            } else {
                alarm.stop()
            }
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
