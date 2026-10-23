package com.smartcampus.door.core

import com.smartcampus.door.core.audio.AssetException
import com.smartcampus.door.core.audio.AssetSource
import com.smartcampus.door.core.audio.AudioPlayer
import com.smartcampus.door.core.net.LinkEvent
import com.smartcampus.door.core.net.MessageSink
import com.smartcampus.door.core.protocol.Announcement
import com.smartcampus.door.core.protocol.AudioPlay
import com.smartcampus.door.core.protocol.AudioSettings
import com.smartcampus.door.core.protocol.RoomStatus
import com.smartcampus.door.core.protocol.ServerMessage
import com.smartcampus.door.core.protocol.TimeWindow
import com.smartcampus.door.core.storage.FileStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RecordingSink : MessageSink {
    val sent = mutableListOf<Pair<String, JsonElement>>()
    override suspend fun send(type: String, payload: JsonElement, id: String) {
        sent += type to payload
    }

    fun refs(type: String) = sent.filter { it.first == type }.map { it.second.jsonObject["ref"]!!.jsonPrimitive.content }
}

private class FakeAssets(private val failure: Exception? = null) : AssetSource {
    var fetched = 0
    override suspend fun fetch(assetId: String, sha256: String, size: Long): File {
        fetched++
        failure?.let { throw it }
        return File.createTempFile("asset", ".bin")
    }
}

private class FakePlayer : AudioPlayer {
    val played = mutableListOf<Int>()
    override suspend fun play(file: File, volumePercent: Int) {
        played += volumePercent
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DoorControllerTest {
    private val noon = java.time.LocalDate.of(2027, 1, 15).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val midnight = java.time.LocalDate.of(2027, 1, 15).atTime(23, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val dir = Files.createTempDirectory("door-test").toFile()

    private fun status(key: String, label: String) = RoomStatus("r", key, label, "#00a86b", "check")
    private val audio = ServerMessage.AudioPlayRequest("A1", AudioPlay("n1", "Bell", "asset1", "0".repeat(64), 10))

    private suspend fun connected(controller: DoorController): RecordingSink =
        RecordingSink().also { controller.onEvent(LinkEvent.Connected(it)) }

    private fun controller(scope: TestScope, assets: AssetSource = FakeAssets(), player: AudioPlayer = FakePlayer(), now: Long = noon) =
        DoorController(FileStorage(dir), assets, player, scope, ZoneOffset.UTC) { now }

    @Test
    fun applies_a_status_acknowledges_it_and_reports() = runTest {
        val controller = controller(this)
        val sink = connected(controller)
        controller.onEvent(LinkEvent.Message(ServerMessage.RoomStatusSet("C1", status("occupied", "Occupied"))))

        assertEquals("Occupied", controller.door.value.status?.label)
        assertEquals(listOf("C1"), sink.refs("ack"))
        assertTrue(sink.sent.any { it.first == "state.report" })
    }

    @Test
    fun a_replayed_command_is_acknowledged_again_but_never_run_twice() = runTest {
        val controller = controller(this)
        val sink = connected(controller)
        controller.onEvent(LinkEvent.Message(ServerMessage.RoomStatusSet("C1", status("occupied", "Occupied"))))
        controller.onEvent(LinkEvent.Message(ServerMessage.RoomStatusSet("C1", status("closed", "Closed"))))

        assertEquals("Occupied", controller.door.value.status?.label)
        assertEquals(listOf("C1", "C1"), sink.refs("ack"))
    }

    @Test
    fun the_last_state_survives_a_restart_and_an_outage() = runTest {
        val first = controller(this)
        connected(first)
        first.onEvent(LinkEvent.Message(ServerMessage.RoomStatusSet("C1", status("closed", "Closed"))))
        first.onEvent(LinkEvent.Message(ServerMessage.AnnounceShow("C2", Announcement("n", "Fire drill", "Evacuate", "urgent"))))
        first.onEvent(LinkEvent.Disconnected(-1, "network down", revoked = false))

        val second = controller(this)
        assertEquals("Closed", second.door.value.status?.label)
        assertEquals(listOf("n"), second.door.value.visibleAnnouncements(noon).map { it.announcementId })
        assertEquals(false, second.online.value)
    }

    @Test
    fun a_command_replayed_after_a_restart_is_still_recognised() = runTest {
        val first = controller(this)
        connected(first)
        first.onEvent(LinkEvent.Message(ServerMessage.RoomStatusSet("C1", status("closed", "Closed"))))

        val second = controller(this)
        val sink = connected(second)
        second.onEvent(LinkEvent.Message(ServerMessage.RoomStatusSet("C1", status("free", "Free"))))
        assertEquals("Closed", second.door.value.status?.label)
        assertEquals(listOf("C1"), sink.refs("ack"))
    }

    @Test
    fun clearing_an_announcement_removes_it() = runTest {
        val controller = controller(this)
        connected(controller)
        controller.onEvent(LinkEvent.Message(ServerMessage.AnnounceShow("C1", Announcement("n", null, "x"))))
        controller.onEvent(LinkEvent.Message(ServerMessage.AnnounceClear("C2", "n")))
        assertTrue(controller.door.value.announcements.isEmpty())
    }

    @Test
    fun plays_audio_at_the_configured_volume_after_acknowledging() = runTest {
        val player = FakePlayer()
        val assets = FakeAssets()
        val controller = controller(this, assets, player)
        val sink = connected(controller)
        controller.onEvent(LinkEvent.Message(ServerMessage.AudioSettingsSet("S1", AudioSettings(volume = 35))))
        controller.onEvent(LinkEvent.Message(audio))
        advanceUntilIdle()

        assertEquals(listOf(35), player.played)
        assertEquals(1, assets.fetched)
        assertTrue("A1" in sink.refs("ack"))
    }

    @Test
    fun stays_silent_but_acknowledges_during_quiet_hours() = runTest {
        val player = FakePlayer()
        val assets = FakeAssets()
        val controller = controller(this, assets, player, now = midnight)
        val sink = connected(controller)
        controller.onEvent(LinkEvent.Message(ServerMessage.AudioSettingsSet("S1", AudioSettings(80, TimeWindow(true, "22:00", "06:00")))))
        controller.onEvent(LinkEvent.Message(audio))
        advanceUntilIdle()

        assertTrue(player.played.isEmpty())
        assertEquals(0, assets.fetched)
        assertTrue("A1" in sink.refs("ack"))
    }

    @Test
    fun rejects_audio_whose_download_fails() = runTest {
        val player = FakePlayer()
        val controller = controller(this, FakeAssets(AssetException("hash mismatch")), player)
        val sink = connected(controller)
        controller.onEvent(LinkEvent.Message(audio))
        advanceUntilIdle()

        assertTrue(player.played.isEmpty())
        assertEquals(listOf("A1"), sink.refs("nack"))
        assertTrue(sink.refs("ack").isEmpty())
    }

    @Test
    fun session_ready_resyncs_the_full_desired_state_and_clock_offset() = runTest {
        val controller = controller(this)
        val sink = connected(controller)
        val desired = kotlinx.serialization.json.buildJsonObject {}
        controller.onEvent(LinkEvent.Message(ServerMessage.SessionReady("R", desired, serverTime = noon + 7_000, pendingCommands = 0)))
        assertEquals(7_000, controller.door.value.clockOffsetMs)
        assertEquals("state.report", sink.sent.last().first)
    }
}
