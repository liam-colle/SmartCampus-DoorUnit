package com.smartcampus.door.core

import com.smartcampus.door.core.protocol.Announcement
import com.smartcampus.door.core.protocol.DisplayConfig
import com.smartcampus.door.core.protocol.ServerMessage
import com.smartcampus.door.core.protocol.TimeWindow
import com.smartcampus.door.core.protocol.parseServerMessage
import com.smartcampus.door.core.protocol.ProtocolException
import com.smartcampus.door.core.protocol.protocolJson
import com.smartcampus.door.core.state.DoorState
import com.smartcampus.door.core.state.contains
import com.smartcampus.door.core.state.isAsleep
import kotlinx.serialization.json.jsonObject
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DoorStateTest {
    private val now = 1_800_000_000_000L

    private fun announcement(id: String, priority: String = "info", expiresAt: Long? = null) =
        Announcement(announcementId = id, text = "text $id", priority = priority, expiresAt = expiresAt)

    @Test
    fun time_window_crosses_midnight() {
        val night = TimeWindow(enabled = true, from = "22:00", to = "06:00")
        assertTrue(night.contains(LocalTime.of(23, 30)))
        assertTrue(night.contains(LocalTime.of(2, 0)))
        assertFalse(night.contains(LocalTime.of(6, 0)))
        assertFalse(night.contains(LocalTime.of(12, 0)))
    }

    @Test
    fun time_window_within_a_day_and_disabled() {
        assertTrue(TimeWindow(true, "12:00", "14:00").contains(LocalTime.of(13, 0)))
        assertFalse(TimeWindow(true, "12:00", "14:00").contains(LocalTime.of(14, 0)))
        assertFalse(TimeWindow(false, "00:00", "23:59").contains(LocalTime.of(10, 0)))
    }

    @Test
    fun announcements_expire_by_themselves_and_urgent_comes_first() {
        val state = DoorState(
            announcements = listOf(
                announcement("a", "info"),
                announcement("b", "urgent"),
                announcement("c", "important", expiresAt = now - 1),
            ).associateBy { it.announcementId },
        )
        assertEquals(listOf("b", "a"), state.visibleAnnouncements(now).map { it.announcementId })
        assertEquals(setOf("a", "b"), state.withoutExpired(now).announcements.keys)
    }

    @Test
    fun expiry_uses_the_server_clock_not_a_wrong_device_clock() {
        // The device clock runs one hour behind the server; the announcement ends at server time + 30 min.
        val serverNow = now + 3_600_000
        val state = DoorState(
            announcements = mapOf("a" to announcement("a", expiresAt = serverNow + 1_800_000)),
            clockOffsetMs = 3_600_000,
        )
        assertEquals(1, state.visibleAnnouncements(now).size)
        assertEquals(0, state.visibleAnnouncements(now + 2_000_000).size)
    }

    @Test
    fun desired_state_replaces_what_the_server_manages() {
        val desired = protocolJson.parseToJsonElement(
            """{"roomStatus":{"roomId":"r","status":"closed","label":"Closed","color":"#e5484d","icon":"lock"},
                "room":{"roomId":"r","code":"B2-104","name":"Lab","building":"Building 2","floor":1,"capacity":24},
                "announcements":{"x":{"announcementId":"x","text":"hi","priority":"urgent"}},
                "displayConfig":{"layout":"split"}}""",
        ).jsonObject
        val state = DoorState(announcements = mapOf("old" to announcement("old"))).withDesired(desired, serverTime = now + 5000, deviceNowMs = now)
        assertEquals("closed", state.status?.status)
        assertEquals("B2-104", state.room?.code)
        assertEquals(setOf("x"), state.announcements.keys)
        assertEquals("split", state.config.layout)
        assertEquals("dark", state.config.theme) // a partial config gets the contract defaults
        assertEquals(5000, state.clockOffsetMs)
    }

    @Test
    fun the_screen_sleeps_inside_the_night_window() {
        val zone = ZoneOffset.UTC
        val state = DoorState(config = DisplayConfig(sleep = TimeWindow(true, "22:00", "06:00")))
        val midnight = java.time.LocalDate.of(2027, 1, 15).atTime(0, 30).toInstant(zone).toEpochMilli()
        val noon = java.time.LocalDate.of(2027, 1, 15).atTime(12, 0).toInstant(zone).toEpochMilli()
        assertTrue(state.isAsleep(midnight, zone))
        assertFalse(state.isAsleep(noon, zone))
    }

    private fun frame(type: String, payload: String) =
        """{"v":1,"id":"01M5M1QKV6ES3FWS1VJ2CVGXBK","type":"$type","ts":1,"seq":3,"payload":$payload}""".toByteArray()

    @Test
    fun parses_commands_and_keeps_unknown_types_as_ignored() {
        val (status, seq) = parseServerMessage(frame("room.status.set", """{"roomId":"r","status":"free","label":"Free","color":"#00a86b","icon":"check"}"""))
        assertEquals(3, seq)
        assertEquals("Free", assertIs<ServerMessage.RoomStatusSet>(status).value.label)
        assertIs<ServerMessage.Ignored>(parseServerMessage(frame("access.acl.sync", """{"rules":[]}""")).first)
    }

    @Test
    fun refuses_malformed_frames() {
        assertFailsWith<ProtocolException> { parseServerMessage("nope".toByteArray()) }
        assertFailsWith<ProtocolException> { parseServerMessage(frame("room.status.set", """{"roomId":"r"}""")) }
        assertFailsWith<ProtocolException> { parseServerMessage(ByteArray(70_000)) }
    }
}
