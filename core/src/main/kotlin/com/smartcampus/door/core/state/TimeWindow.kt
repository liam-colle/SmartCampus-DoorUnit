package com.smartcampus.door.core.state

import com.smartcampus.door.core.protocol.TimeWindow
import java.time.LocalTime

/** True when [now] is inside the window. A window whose start is after its end crosses midnight (22:00 to 06:00). */
fun TimeWindow.contains(now: LocalTime): Boolean {
    if (!enabled) return false
    val start = LocalTime.parse(from)
    val end = LocalTime.parse(to)
    return if (start <= end) now >= start && now < end else now >= start || now < end
}
