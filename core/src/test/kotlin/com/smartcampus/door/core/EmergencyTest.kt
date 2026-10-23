package com.smartcampus.door.core

import com.smartcampus.door.core.audio.AlarmPlayer
import com.smartcampus.door.core.audio.AlarmSynth
import com.smartcampus.door.core.audio.AssetSource
import com.smartcampus.door.core.audio.AudioPlayer
import com.smartcampus.door.core.net.LinkEvent
import com.smartcampus.door.core.net.MessageSink
import com.smartcampus.door.core.protocol.AudioSettings
import com.smartcampus.door.core.protocol.Emergency
import com.smartcampus.door.core.protocol.ServerMessage
import com.smartcampus.door.core.protocol.TimeWindow
import com.smartcampus.door.core.protocol.protocolJson
import com.smartcampus.door.core.storage.FileStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingAlarm : AlarmPlayer {
    val events = mutableListOf<String>()
    override fun start(kind: String, level: Float) {
        events += "start $kind ${"%.2f".format(level)}"
    }

    override fun stop() {
        events += "stop"
    }
}

private object NoAssets : AssetSource {
    override suspend fun fetch(assetId: String, sha256: String, size: Long): File = error("no downloads in this test")
}

private object SilentPlayer : AudioPlayer {
    override suspend fun play(file: File, volumePercent: Int) = Unit
}

private class Sink : MessageSink {
    val sent = mutableListOf<Pair<String, JsonElement>>()
    override suspend fun send(type: String, payload: JsonElement, id: String) {
        sent += type to payload
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class EmergencyTest {
    private val noon = java.time.LocalDate.of(2027, 1, 15).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val night = java.time.LocalDate.of(2027, 1, 15).atTime(23, 30).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun emergency(id: String = "E1", kind: String = "fire", test: Boolean = false, volume: Int = 20) =
        Emergency(id, kind, "Fire alarm", "Leave the building.", testMode = test, testVolume = volume, startedAt = 1)

    private fun controller(scope: TestScope, alarm: AlarmPlayer, now: Long = noon, dir: File = Files.createTempDirectory("emergency-test").toFile()) =
        DoorController(FileStorage(dir), NoAssets, SilentPlayer, scope, alarm, ZoneOffset.UTC) { now }

    private suspend fun DoorController.receive(message: ServerMessage) = onEvent(LinkEvent.Message(message))

    @Test
    fun a_real_emergency_sounds_at_full_level_until_cleared() = runTest {
        val alarm = RecordingAlarm()
        val controller = controller(this, alarm)
        controller.receive(ServerMessage.EmergencySet("C1", emergency()))
        assertEquals("E1", controller.door.value.emergency?.emergencyId)
        assertEquals(listOf("start fire 1.00"), alarm.events)

        controller.receive(ServerMessage.EmergencyClear("C2", "E1"))
        assertNull(controller.door.value.emergency)
        assertEquals("stop", alarm.events.last())
    }

    @Test
    fun a_real_emergency_ignores_the_volume_setting_and_quiet_hours() = runTest {
        val alarm = RecordingAlarm()
        val controller = controller(this, alarm, now = night)
        val quiet = AudioSettings(volume = 0, quietHours = TimeWindow(enabled = true, from = "22:00", to = "06:00"))
        controller.receive(ServerMessage.AudioSettingsSet("S1", quiet))
        controller.receive(ServerMessage.EmergencySet("C1", emergency(kind = "bomb")))
        assertEquals(listOf("start bomb 1.00"), alarm.events)
    }

    @Test
    fun a_test_plays_at_its_own_volume_and_stays_silent_in_quiet_hours() = runTest {
        val loud = RecordingAlarm()
        controller(this, loud).receive(ServerMessage.EmergencySet("C1", emergency(test = true, volume = 15)))
        assertEquals(listOf("start fire 0.15"), loud.events)

        val quiet = RecordingAlarm()
        val atNight = controller(this, quiet, now = night)
        atNight.receive(ServerMessage.AudioSettingsSet("S1", AudioSettings(quietHours = TimeWindow(enabled = true, from = "22:00", to = "06:00"))))
        atNight.receive(ServerMessage.EmergencySet("C2", emergency(test = true, volume = 15)))
        assertEquals(listOf("stop"), quiet.events) // the screen still shows the test, the speaker stays off
        assertEquals("E1", atNight.door.value.emergency?.emergencyId)
    }

    @Test
    fun a_power_cut_is_a_soft_alarm() {
        assertEquals(0.35f, alarmLevel(emergency(kind = "power"), inQuietHours = false))
        assertEquals(1f, alarmLevel(emergency(kind = "nuclear"), inQuietHours = false))
    }

    @Test
    fun a_newer_emergency_replaces_the_alarm_and_an_old_clear_does_not_end_it() = runTest {
        val alarm = RecordingAlarm()
        val controller = controller(this, alarm)
        controller.receive(ServerMessage.EmergencySet("C1", emergency("E1", "fire")))
        controller.receive(ServerMessage.EmergencySet("C2", emergency("E2", "seismic")))
        controller.receive(ServerMessage.EmergencyClear("C3", "E1"))

        assertEquals("E2", controller.door.value.emergency?.emergencyId)
        assertEquals(listOf("start fire 1.00", "start seismic 1.00"), alarm.events)
    }

    @Test
    fun a_restart_keeps_the_alarm_going_and_a_replayed_start_does_not_restart_it() = runTest {
        val dir = Files.createTempDirectory("emergency-restart").toFile()
        controller(this, RecordingAlarm(), dir = dir).receive(ServerMessage.EmergencySet("C1", emergency()))

        val alarm = RecordingAlarm()
        val restarted = controller(this, alarm, dir = dir)
        assertEquals(listOf("start fire 1.00"), alarm.events)

        val sink = Sink().also { restarted.onEvent(LinkEvent.Connected(it)) }
        val desired = buildJsonObject { put("emergency", protocolJson.encodeToJsonElement(Emergency.serializer(), emergency())) }
        restarted.receive(ServerMessage.SessionReady("R1", desired, noon, 0))
        assertEquals(1, alarm.events.size)
        assertEquals("E1", sink.sent.first { it.first == "state.report" }.second.jsonObject["state"]!!.jsonObject["emergency"]!!.toString().trim('"'))
    }

    @Test
    fun the_desired_state_without_an_emergency_ends_one_we_missed_the_clear_for() = runTest {
        val alarm = RecordingAlarm()
        val controller = controller(this, alarm)
        controller.receive(ServerMessage.EmergencySet("C1", emergency()))
        Sink().also { controller.onEvent(LinkEvent.Connected(it)) }

        controller.receive(ServerMessage.SessionReady("R1", buildJsonObject { put("emergency", JsonNull) }, noon, 0))
        assertNull(controller.door.value.emergency)
        assertEquals("stop", alarm.events.last())
    }

    @Test
    fun every_alarm_renders_a_loud_seamless_distinct_loop() {
        val kinds = listOf("fire", "biohazard", "nuclear", "bomb", "seismic", "power")
        val loops = kinds.associateWith { AlarmSynth.cycle(it) }
        for ((kind, samples) in loops) {
            val peak = samples.maxOf { abs(it.toInt()) }
            assertTrue(peak in 25_000..Short.MAX_VALUE, "$kind peak $peak")
            // The echo wraps around the loop, so the edges are not silent; what matters is that the jump from the
            // last sample back to the first is no bigger than the signal's own steepest step (no audible click).
            val steepest = samples.toList().zipWithNext { a, b -> abs(b - a) }.max()
            val wrapJump = abs(samples.first() - samples.last())
            assertTrue(wrapJump <= 2 * steepest, "$kind wrap jump $wrapJump vs steepest step $steepest")
            assertTrue(samples.size >= AlarmSynth.SAMPLE_RATE * 2, "$kind is at least two seconds")
        }
        assertEquals(kinds.size, loops.values.map { it.toList().hashCode() }.toSet().size)
        assertNotEquals(AlarmSynth.cycle("fire").toList(), AlarmSynth.cycle("power").toList())
    }

    @Test
    fun an_unknown_kind_still_sounds_an_alarm() {
        assertTrue(AlarmSynth.cycle("alien-landing").any { it.toInt() != 0 })
    }
}
