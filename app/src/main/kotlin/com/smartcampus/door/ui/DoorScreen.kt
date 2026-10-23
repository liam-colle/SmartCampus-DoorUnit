package com.smartcampus.door.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
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

    // An emergency overrides night sleep: the panel wakes at full brightness and shows nothing else.
    val emergency = door.emergency
    DimWhileAsleep(asleep && emergency == null, bright = emergency != null)
    if (emergency != null) {
        EmergencyScreen(door.room?.code, emergency)
        return
    }
    if (asleep) {
        // Night sleep: black, with the backlight at its lowest, still connected so an urgent announcement can wake it.
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    DoorContent(door, online, now)
}

/** There is no API to switch the panel off without device admin rights, so the brightness goes to the minimum. */
@Composable
private fun DimWhileAsleep(asleep: Boolean, bright: Boolean) {
    val activity = LocalContext.current as? Activity
    SideEffect {
        activity?.window?.let { window ->
            window.attributes = window.attributes.apply {
                screenBrightness = when {
                    bright -> 1f
                    asleep -> 0.01f
                    else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
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

    BoxWithConstraints(Modifier.fillMaxSize().background(palette.background)) {
        val landscape = maxWidth > maxHeight
        val s = Scale(minOf(maxWidth, maxHeight), LocalDensity.current)
        Box(Modifier.fillMaxSize().padding(s.dp(6f))) {
            // Symptom: in portrait the split layout gave each column half of a narrow screen and the status label
            // broke in the middle of a word. Fix: side by side only on a landscape screen, stacked otherwise.
            val gap = s.dp(5f)
            when {
                door.config.layout == "split" && landscape -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    RoomBlock(door, now, palette, accent, s, compact = false, modifier = Modifier.weight(1f).fillMaxHeight())
                    AnnouncementPanel(announcements, palette, accent, s, Modifier.weight(1f).fillMaxHeight())
                }
                door.config.layout == "split" -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    RoomBlock(door, now, palette, accent, s, compact = false, modifier = Modifier.weight(1f).fillMaxWidth())
                    AnnouncementPanel(announcements, palette, accent, s, Modifier.weight(1f).fillMaxWidth())
                }
                else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    RoomBlock(door, now, palette, accent, s, compact = door.config.layout == "compact", modifier = Modifier.weight(1f).fillMaxWidth())
                    announcements.firstOrNull()?.let { AnnouncementBanner(it, palette, accent, s) }
                }
            }
            if (!online) OfflineBadge(s, Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun AnnouncementPanel(announcements: List<Announcement>, palette: Palette, accent: Color, s: Scale, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(s.dp(4f))).background(palette.surface).padding(s.dp(4f)), verticalArrangement = Arrangement.spacedBy(s.dp(3f))) {
        Text("Announcements", color = palette.muted, fontSize = s.sp(4.6f), lineHeight = s.sp(5.8f))
        if (announcements.isEmpty()) Text("Nothing to show.", color = palette.muted, fontSize = s.sp(5f), lineHeight = s.sp(6.2f))
        announcements.take(3).forEach { AnnouncementBanner(it, palette, accent, s) }
    }
}

@Composable
private fun RoomBlock(door: DoorState, now: Long, palette: Palette, accent: Color, s: Scale, compact: Boolean, modifier: Modifier) {
    val room = door.room
    val show = door.config.show
    val place = listOfNotNull(
        room?.building?.takeIf { show.building && it.isNotBlank() },
        room?.takeIf { show.floor }?.let { if (it.floor == 0) "Ground floor" else if (it.floor < 0) "Basement ${-it.floor}" else "Floor ${it.floor}" },
    ).joinToString(" · ")

    Column(modifier, verticalArrangement = Arrangement.SpaceBetween) {
        Column(verticalArrangement = Arrangement.spacedBy(s.dp(1f))) {
            if (place.isNotEmpty()) FitText(place, palette.muted, s.sp(5.2f), FontWeight.Normal, Modifier.fillMaxWidth(), singleLine = true, align = TextAlign.Start)
            if (show.roomName) {
                FitText(
                    room?.name?.ifBlank { room.code } ?: "No room linked",
                    palette.text,
                    s.sp(if (compact) 10f else 14f),
                    FontWeight.SemiBold,
                    Modifier.fillMaxWidth(),
                    maxLines = 2,
                    align = TextAlign.Start,
                )
                if (room != null && room.name.isNotBlank()) FitText(room.code, palette.muted, s.sp(5.2f), FontWeight.Normal, Modifier.fillMaxWidth(), singleLine = true, align = TextAlign.Start)
            }
        }
        StatusBand(door, accent, palette, s, compact)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(if (show.capacity && room?.capacity != null) "${room.capacity} seats" else "", color = palette.muted, fontSize = s.sp(5.2f), lineHeight = s.sp(6.5f))
            if (show.clock) Text(door.localTime(now, zone).format(clockFormat), color = palette.text, fontSize = s.sp(9f), lineHeight = s.sp(11f), fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun StatusBand(door: DoorState, accent: Color, palette: Palette, s: Scale, compact: Boolean) {
    val status = door.status
    val color = status?.let { parseColor(it.color, accent) } ?: palette.muted
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(s.dp(4f))).background(color.copy(alpha = 0.15f)).padding(horizontal = s.dp(5f), vertical = s.dp(4f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(s.dp(3.5f)),
    ) {
        if (status != null) Icon(iconFor(status.icon), contentDescription = null, tint = color, modifier = Modifier.size(s.dp(if (compact) 8f else 11f)))
        // weight: the label takes what the icon leaves, so a long label shrinks instead of pushing past the band.
        FitText(status?.label ?: "No status", color, s.sp(if (compact) 8f else 11f), FontWeight.SemiBold, Modifier.weight(1f), maxLines = 2, align = TextAlign.Start)
    }
}

/** Banner colour per priority: info follows the accent, important is amber, urgent is red. */
private fun priorityColor(priority: String, accent: Color) = when (priority) {
    "urgent" -> Color(0xFFE5484D)
    "important" -> Color(0xFFF5A524)
    else -> accent
}

@Composable
private fun AnnouncementBanner(announcement: Announcement, palette: Palette, accent: Color, s: Scale) {
    val color = priorityColor(announcement.priority, accent)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(s.dp(3f))).background(color.copy(alpha = 0.14f))) {
        Box(Modifier.width(s.dp(1.6f)).fillMaxHeight().background(color))
        Column(Modifier.padding(s.dp(3.5f)), verticalArrangement = Arrangement.spacedBy(s.dp(1f))) {
            announcement.title?.takeIf { it.isNotBlank() }?.let {
                FitText(it, palette.text, s.sp(5.6f), FontWeight.SemiBold, Modifier.fillMaxWidth(), maxLines = 2, align = TextAlign.Start)
            }
            FitText(announcement.text, palette.text, s.sp(4.8f), FontWeight.Normal, Modifier.fillMaxWidth(), maxLines = 4, align = TextAlign.Start)
        }
    }
}

@Composable
private fun OfflineBadge(s: Scale, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color(0xCC000000)).padding(horizontal = s.dp(3.2f), vertical = s.dp(1.8f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(s.dp(1.8f)),
    ) {
        Icon(Icons.Filled.WifiOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(s.dp(4f)))
        Text("Offline, showing the last known state", color = Color.White, fontSize = s.sp(3.4f), lineHeight = s.sp(4.4f))
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
