package com.smartcampus.door.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartcampus.door.core.DoorController
import com.smartcampus.door.core.protocol.Announcement
import com.smartcampus.door.core.protocol.DisplayConfig
import com.smartcampus.door.core.state.DoorState
import com.smartcampus.door.core.state.isAsleep
import com.smartcampus.door.core.state.localTime
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val zone: ZoneId = ZoneId.systemDefault()
private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

/** Re-evaluates expiry, the clock and night sleep every few seconds without touching the network. */
@Composable
private fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(5_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun DoorScreen(controller: DoorController) {
    val door by controller.door.collectAsState()
    val online by controller.online.collectAsState()
    val now = rememberNow()
    val asleep = door.isAsleep(now, zone)

    DimWhileAsleep(asleep)
    if (asleep) {
        // Night sleep: black, with the backlight at its lowest, still connected so an urgent announcement can wake it.
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    DoorContent(door, online, now)
}

/** There is no API to switch the panel off without device admin rights, so the brightness goes to the minimum. */
@Composable
private fun DimWhileAsleep(asleep: Boolean) {
    val activity = LocalContext.current as? Activity
    SideEffect {
        activity?.window?.let { window ->
            window.attributes = window.attributes.apply {
                screenBrightness = if (asleep) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }
}

private data class Palette(val background: Color, val surface: Color, val text: Color, val muted: Color)

private fun paletteFor(config: DisplayConfig) =
    if (config.theme == "light") Palette(Color(0xFFF3F6F4), Color.White, Color(0xFF14231B), Color(0xFF5D6D64))
    else Palette(Color(0xFF0E0F10), Color(0xFF1A1D1F), Color(0xFFE8F1EC), Color(0xFF8A9A92))

@Composable
private fun DoorContent(door: DoorState, online: Boolean, now: Long) {
    val palette = paletteFor(door.config)
    val accent = parseColor(door.config.accentColor)
    val announcements = if (door.config.show.announcements) door.visibleAnnouncements(now) else emptyList()

    Box(Modifier.fillMaxSize().background(palette.background).padding(32.dp)) {
        when (door.config.layout) {
            "split" -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                RoomBlock(door, now, palette, accent, compact = false, modifier = Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(palette.surface).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Announcements", color = palette.muted, fontSize = 18.sp)
                    if (announcements.isEmpty()) Text("Nothing to show.", color = palette.muted, fontSize = 20.sp)
                    announcements.take(3).forEach { AnnouncementBanner(it, palette, accent) }
                }
            }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                RoomBlock(door, now, palette, accent, compact = door.config.layout == "compact", modifier = Modifier.weight(1f).fillMaxWidth())
                announcements.firstOrNull()?.let { AnnouncementBanner(it, palette, accent) }
            }
        }
        if (!online) OfflineBadge(Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun RoomBlock(door: DoorState, now: Long, palette: Palette, accent: Color, compact: Boolean, modifier: Modifier) {
    val room = door.room
    val show = door.config.show
    val place = listOfNotNull(
        room?.building?.takeIf { show.building && it.isNotBlank() },
        room?.takeIf { show.floor }?.let { if (it.floor == 0) "Ground floor" else if (it.floor < 0) "Basement ${-it.floor}" else "Floor ${it.floor}" },
    ).joinToString(" · ")

    Column(modifier, verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(place, color = palette.muted, fontSize = 22.sp)
            if (show.roomName) {
                Text(
                    room?.name?.ifBlank { room.code } ?: "No room linked",
                    color = palette.text,
                    fontSize = if (compact) 44.sp else 72.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (room != null && room.name.isNotBlank()) Text(room.code, color = palette.muted, fontSize = 22.sp)
            }
        }
        StatusBand(door, accent, palette, compact)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(if (show.capacity && room?.capacity != null) "${room.capacity} seats" else "", color = palette.muted, fontSize = 22.sp)
            if (show.clock) Text(door.localTime(now, zone).format(clockFormat), color = palette.text, fontSize = 40.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun StatusBand(door: DoorState, accent: Color, palette: Palette, compact: Boolean) {
    val status = door.status
    val color = status?.let { parseColor(it.color, accent) } ?: palette.muted
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 28.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (status != null) Icon(iconFor(status.icon), contentDescription = null, tint = color, modifier = Modifier.size(if (compact) 40.dp else 64.dp))
        Text(status?.label ?: "No status", color = color, fontSize = if (compact) 36.sp else 56.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Banner colour per priority: info follows the accent, important is amber, urgent is red. */
private fun priorityColor(priority: String, accent: Color) = when (priority) {
    "urgent" -> Color(0xFFE5484D)
    "important" -> Color(0xFFF5A524)
    else -> accent
}

@Composable
private fun AnnouncementBanner(announcement: Announcement, palette: Palette, accent: Color) {
    val color = priorityColor(announcement.priority, accent)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.14f))) {
        Box(Modifier.width(8.dp).fillMaxHeight().background(color))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            announcement.title?.takeIf { it.isNotBlank() }?.let { Text(it, color = palette.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold) }
            Text(announcement.text, color = palette.text, fontSize = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun OfflineBadge(modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color(0xCC000000)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.WifiOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text("Offline, showing the last known state", color = Color.White, fontSize = 15.sp)
    }
}

private fun iconFor(name: String): ImageVector = when (name) {
    "check" -> Icons.Filled.Check
    "user" -> Icons.Filled.Person
    "lock" -> Icons.Filled.Lock
    "wrench" -> Icons.Filled.Build
    "calendar" -> Icons.Filled.CalendarMonth
    "clock" -> Icons.Filled.Schedule
    "alert" -> Icons.Filled.Warning
    "book" -> Icons.Filled.MenuBook
    "coffee" -> Icons.Filled.Coffee
    "ban" -> Icons.Filled.Block
    "wifi-off" -> Icons.Filled.WifiOff
    else -> Icons.Filled.Info
}
