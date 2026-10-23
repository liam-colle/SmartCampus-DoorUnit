package com.smartcampus.door.core

import com.smartcampus.door.core.audio.AssetSource
import com.smartcampus.door.core.audio.AudioPlayer
import com.smartcampus.door.core.audio.CardTone
import com.smartcampus.door.core.audio.CardTones
import com.smartcampus.door.core.net.LinkEvent
import com.smartcampus.door.core.net.MessageSink
import com.smartcampus.door.core.protocol.AudioSettings
import com.smartcampus.door.core.protocol.CardMenu
import com.smartcampus.door.core.protocol.Emergency
import com.smartcampus.door.core.protocol.MenuStatus
import com.smartcampus.door.core.protocol.TimeWindow
import com.smartcampus.door.core.protocol.ProtocolException
import com.smartcampus.door.core.protocol.RegistrationWindow
import com.smartcampus.door.core.protocol.ServerMessage
import com.smartcampus.door.core.protocol.parseServerMessage
import com.smartcampus.door.core.storage.FileStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object NoDownloads : AssetSource {
    override suspend fun fetch(assetId: String, sha256: String, size: Long): File = error("no download expected")
}

private object NoSound : AudioPlayer {
    override suspend fun play(file: File, volumePercent: Int) = Unit
}

private class RecordingTones : CardTones {
    val played = mutableListOf<Pair<CardTone, Float>>()
    override fun play(tone: CardTone, level: Float) {
        played += tone to level
    }

    val kinds get() = played.map { it.first }
}

private class TapSink : MessageSink {
    val sent = mutableListOf<Triple<String, JsonElement, String>>()
    override suspend fun send(type: String, payload: JsonElement, id: String) {
        sent += Triple(type, payload, id)
    }

    fun ofType(type: String) = sent.filter { it.first == type }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CardAccessTest {
    private var now = java.time.LocalDate.of(2027, 1, 15).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val dir = Files.createTempDirectory("card-test").toFile()

    private val tones = RecordingTones()

    private fun controller(scope: TestScope) =
        DoorController(FileStorage(dir), NoDownloads, NoSound, scope, tones = tones, zone = ZoneOffset.UTC) { now }

    private suspend fun connected(controller: DoorController) = TapSink().also { controller.onEvent(LinkEvent.Connected(it)) }

    private fun result(ref: String, outcome: String = "maintenance", maintenance: Boolean = true, name: String? = "Ada") =
        LinkEvent.Message(ServerMessage.CardResult("S" + ref.takeLast(5), ref, outcome, maintenance, name))

    private suspend fun DoorController.receive(message: ServerMessage) = onEvent(LinkEvent.Message(message))

    private val fire = Emergency("E1", "fire", "Fire", "Leave the building")

    @Test
    fun a_tap_is_sent_to_the_server_and_an_accepted_card_opens_the_menu() = runTest {
        val controller = controller(this)
        val sink = connected(controller)

        controller.onCardRead("04A22B1C")
        val (type, payload, id) = sink.sent.single()
        assertEquals("event.badge", type)
        assertEquals("04A22B1C", payload.jsonObject["badgeUid"]!!.jsonPrimitive.content)
        assertNull(controller.maintenance.value)

        controller.onEvent(result(id))
        assertEquals("Ada", controller.maintenance.value?.name)
    }

    @Test
    fun a_card_the_server_does_not_accept_never_opens_the_menu() = runTest {
        val controller = controller(this)
        val sink = connected(controller)
        controller.onCardRead("04A22B1C")

        controller.onEvent(result(sink.sent.single().third, outcome = "recognised", maintenance = false, name = "Sam"))
        assertNull(controller.maintenance.value)
        assertEquals("recognised", controller.lastCard.value?.outcome)
    }

    @Test
    fun a_result_that_does_not_answer_one_of_our_taps_is_ignored() = runTest {
        val controller = controller(this)
        connected(controller)

        controller.onEvent(result("01HZZZZZZZZZZZZZZZZZZZZZZZ"))
        assertNull(controller.maintenance.value)
        assertNull(controller.lastCard.value)
    }

    @Test
    fun an_answer_is_used_once_and_only_while_it_is_fresh() = runTest {
        val controller = controller(this)
        val sink = connected(controller)
        controller.onCardRead("04A22B1C")
        val id = sink.sent.single().third

        now += 11_000
        controller.onEvent(result(id))
        assertNull(controller.maintenance.value)

        now += 5_000
        controller.onCardRead("04B33C2D")
        val second = sink.sent.last().third
        controller.onEvent(result(second))
        controller.closeMaintenance()
        controller.onEvent(result(second))
        assertNull(controller.maintenance.value)
    }

    @Test
    fun offline_a_tap_is_not_sent_and_says_so() = runTest {
        val controller = controller(this)
        controller.onCardRead("04A22B1C")

        assertEquals(OUTCOME_OFFLINE, controller.lastCard.value?.outcome)
        assertNull(controller.maintenance.value)
    }

    @Test
    fun a_card_resting_on_the_antenna_counts_once() = runTest {
        val controller = controller(this)
        val sink = connected(controller)
        controller.onCardRead("04A22B1C")
        now += 500
        controller.onCardRead("04A22B1C")
        assertEquals(1, sink.ofType("event.badge").size)

        now += 2_500
        controller.onCardRead("04A22B1C")
        assertEquals(2, sink.ofType("event.badge").size)
    }

    @Test
    fun an_emergency_keeps_the_menu_shut_and_closes_one_that_is_open() = runTest {
        val controller = controller(this)
        val sink = connected(controller)
        controller.onCardRead("04A22B1C")
        controller.onEvent(result(sink.sent.single().third))
        assertNotNull(controller.maintenance.value)

        controller.receive(ServerMessage.EmergencySet("C1", fire))
        assertNull(controller.maintenance.value)

        now += 5_000
        controller.onCardRead("04B33C2D")
        controller.onEvent(result(sink.sent.last().third))
        assertNull(controller.maintenance.value)
    }

    @Test
    fun losing_the_connection_closes_the_menu() = runTest {
        val controller = controller(this)
        val sink = connected(controller)
        controller.onCardRead("04A22B1C")
        controller.onEvent(result(sink.sent.single().third))

        controller.onEvent(LinkEvent.Disconnected(-1, "network down", revoked = false))
        assertNull(controller.maintenance.value)
    }

    @Test
    fun a_card_result_is_not_a_command_so_it_is_never_acknowledged() = runTest {
        val controller = controller(this)
        val sink = connected(controller)
        controller.onCardRead("04A22B1C")
        controller.onEvent(result(sink.sent.single().third))

        assertTrue(sink.ofType("ack").isEmpty())
        assertTrue(sink.ofType("nack").isEmpty())
    }

    @Test
    fun registration_mode_follows_the_server_and_ends_by_itself() = runTest {
        val controller = controller(this)
        connected(controller)
        val window = RegistrationWindow("R1", true, now + 60_000)

        controller.receive(ServerMessage.RegistrationModeSet("C1", window))
        assertTrue(controller.door.value.isRegistering(now))
        assertFalse(controller.door.value.isRegistering(now + 61_000))

        controller.receive(ServerMessage.RegistrationModeSet("C2", RegistrationWindow("OTHER", false)))
        assertTrue(controller.door.value.isRegistering(now), "a stop for another session must not end this one")

        controller.receive(ServerMessage.RegistrationModeSet("C3", RegistrationWindow("R1", false)))
        assertFalse(controller.door.value.isRegistering(now))
    }

    @Test
    fun a_desired_state_without_a_registration_window_ends_the_mode() {
        val state = com.smartcampus.door.core.state.DoorState(registration = RegistrationWindow("R1", true, null))
        val desired = buildJsonObject { put("serverTime", 0) }
        assertNull(state.withDesired(desired, 0, 0).registration)
    }

    @Test
    fun parses_the_two_new_messages_and_rejects_a_malformed_card_result() {
        fun frame(type: String, payload: String) =
            """{"v":1,"id":"01HZ0000000000000000000000","type":"$type","ts":1,"seq":2,"payload":$payload}""".toByteArray()

        val (result, _) = parseServerMessage(frame("card.result", """{"ref":"01HZ0000000000000000000001","outcome":"maintenance","maintenance":true,"name":"Ada"}"""))
        assertEquals(ServerMessage.CardResult("01HZ0000000000000000000000", "01HZ0000000000000000000001", "maintenance", true, "Ada"), result)

        val (mode, _) = parseServerMessage(frame("registration.mode.set", """{"sessionId":"R1","active":true,"expiresAt":99}"""))
        assertEquals(RegistrationWindow("R1", true, 99), (mode as ServerMessage.RegistrationModeSet).value)

        assertFailsWith<Exception> { parseServerMessage(frame("card.result", """{"ref":"x"}""")) }
        assertFailsWith<ProtocolException> { parseServerMessage("not json".toByteArray()) }
    }

    @Test
    fun formats_a_serial_number_as_upper_case_hex_and_only_accepts_real_lengths() {
        assertEquals("04A22B1C", cardUidHex(byteArrayOf(0x04, 0xA2.toByte(), 0x2B, 0x1C)))
        assertEquals("000000FF", cardUidHex(byteArrayOf(0, 0, 0, -1)))
        assertTrue(isPlausibleCardUid(ByteArray(4)) && isPlausibleCardUid(ByteArray(7)) && isPlausibleCardUid(ByteArray(10)))
        assertFalse(isPlausibleCardUid(ByteArray(0)) || isPlausibleCardUid(ByteArray(5)) || isPlausibleCardUid(ByteArray(64)))
    }

    // ---- sounds, feedback and menu actions ----

    private val menu = CardMenu(listOf(MenuStatus("free", "Free", "#00a86b", "check")), canRevoke = true)

    private fun accepted(ref: String) =
        LinkEvent.Message(ServerMessage.CardResult("S" + ref.takeLast(5), ref, "maintenance", true, "Ada", menu))

    @Test
    fun a_tap_beeps_when_read_then_signals_granted_or_denied() = runTest {
        val controller = controller(this)
        val sink = connected(controller)

        controller.onCardRead("04A22B1C")
        assertEquals(listOf(CardTone.READ), tones.kinds)
        assertEquals(OUTCOME_CHECKING, controller.lastCard.value?.outcome)
        assertFalse(controller.lastCard.value!!.granted)

        controller.onEvent(accepted(sink.sent.last().third))
        assertEquals(listOf(CardTone.READ, CardTone.GRANTED), tones.kinds)
        assertTrue(controller.lastCard.value!!.granted)

        now += 5_000
        controller.onCardRead("04B33C2D")
        controller.onEvent(result(sink.sent.last().third, outcome = "unknown", maintenance = false, name = null))
        assertEquals(CardTone.DENIED, tones.kinds.last())
        assertFalse(controller.lastCard.value!!.granted)
    }

    @Test
    fun an_unknown_card_is_denied_and_an_offline_tap_is_denied_too() = runTest {
        val controller = controller(this)
        controller.onCardRead("04A22B1C")
        assertEquals(listOf(CardTone.READ, CardTone.DENIED), tones.kinds)
    }

    @Test
    fun the_sounds_follow_the_volume_and_stay_silent_in_quiet_hours_and_emergencies() = runTest {
        val controller = controller(this)
        connected(controller)
        controller.receive(ServerMessage.AudioSettingsSet("C1", AudioSettings(volume = 40)))
        controller.onCardRead("04A22B1C")
        assertEquals(0.4f, tones.played.single().second)

        tones.played.clear()
        controller.receive(ServerMessage.AudioSettingsSet("C2", AudioSettings(volume = 40, quietHours = TimeWindow(true, "11:00", "13:00"))))
        now += 5_000
        controller.onCardRead("04B33C2D")
        assertTrue(tones.played.isEmpty(), "quiet hours")

        controller.receive(ServerMessage.AudioSettingsSet("C3", AudioSettings(volume = 40)))
        controller.receive(ServerMessage.EmergencySet("C4", fire))
        now += 5_000
        controller.onCardRead("04C44D3E")
        assertTrue(tones.played.isEmpty(), "emergency")

        controller.receive(ServerMessage.EmergencyClear("C5", "E1"))
        controller.receive(ServerMessage.AudioSettingsSet("C6", AudioSettings(volume = 0)))
        now += 5_000
        controller.onCardRead("04D55E4F")
        assertTrue(tones.played.isEmpty(), "volume 0")
    }

    @Test
    fun the_menu_carries_what_the_server_offered() = runTest {
        val controller = controller(this)
        val sink = connected(controller)
        controller.onCardRead("04A22B1C")
        controller.onEvent(accepted(sink.sent.last().third))

        assertEquals(menu, controller.maintenance.value?.menu)
    }

    @Test
    fun an_action_is_sent_with_its_status_and_the_answer_is_shown() = runTest {
        val controller = controller(this)
        val sink = connected(controller)
        controller.onCardRead("04A22B1C")
        controller.onEvent(accepted(sink.sent.last().third))

        controller.maintenanceAction("status.set", "free")
        val (type, payload, id) = sink.sent.last()
        assertEquals("maintenance.action", type)
        assertEquals("free", payload.jsonObject["status"]!!.jsonPrimitive.content)

        controller.onEvent(LinkEvent.Message(ServerMessage.MaintenanceResult("S00001", id, "status.set", ok = false, error = "Unknown status")))
        assertEquals(ActionOutcome("status.set", false, "Unknown status"), controller.actionOutcome.value)
        assertEquals(CardTone.DENIED, tones.kinds.last())
    }

    @Test
    fun an_action_result_for_something_we_did_not_ask_is_ignored() = runTest {
        val controller = controller(this)
        connected(controller)
        controller.onEvent(LinkEvent.Message(ServerMessage.MaintenanceResult("S00001", "01HZ0000000000000000000009", "restart", true, null)))
        assertNull(controller.actionOutcome.value)
    }

    @Test
    fun closing_the_menu_forgets_its_last_answer() = runTest {
        val controller = controller(this)
        val sink = connected(controller)
        controller.onCardRead("04A22B1C")
        controller.onEvent(accepted(sink.sent.last().third))
        controller.maintenanceAction("restart")
        controller.onEvent(LinkEvent.Message(ServerMessage.MaintenanceResult("S00001", sink.sent.last().third, "restart", true, null)))

        controller.closeMaintenance()
        assertNull(controller.maintenance.value)
        assertNull(controller.actionOutcome.value)
    }

    @Test
    fun parses_the_menu_and_the_action_answer() {
        fun frame(type: String, payload: String) =
            """{"v":1,"id":"01HZ0000000000000000000000","type":"$type","ts":1,"seq":2,"payload":$payload}""".toByteArray()

        val (card, _) = parseServerMessage(
            frame(
                "card.result",
                """{"ref":"01HZ0000000000000000000001","outcome":"maintenance","maintenance":true,"menu":{"statuses":[{"key":"free","label":"Free","color":"#00a86b","icon":"check"}],"canRevoke":false}}""",
            ),
        )
        assertEquals(1, (card as ServerMessage.CardResult).menu?.statuses?.size)
        assertEquals(false, card.menu?.canRevoke)

        val (answer, _) = parseServerMessage(frame("maintenance.result", """{"ref":"01HZ0000000000000000000001","action":"revoke","ok":false,"error":"no"}"""))
        assertEquals(ServerMessage.MaintenanceResult("01HZ0000000000000000000000", "01HZ0000000000000000000001", "revoke", false, "no"), answer)
    }

    @Test
    fun only_accepted_outcomes_count_as_access_granted() {
        listOf("maintenance", "recognised", "captured").forEach { assertTrue(isAccessGranted(it), it) }
        listOf("unknown", "disabled", "duplicate", "already_registered", "rate_limited", OUTCOME_OFFLINE, OUTCOME_CHECKING)
            .forEach { assertFalse(isAccessGranted(it), it) }
    }
}
