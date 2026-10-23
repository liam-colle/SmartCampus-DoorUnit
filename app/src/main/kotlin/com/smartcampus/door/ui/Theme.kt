package com.smartcampus.door.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Brand = Color(0xFF00A86B)

private val Scheme = darkColorScheme(
    primary = Brand,
    onPrimary = Color(0xFF06120D),
    background = Color(0xFF0E0F10),
    onBackground = Color(0xFFE8F1EC),
    surface = Color(0xFF151718),
    onSurface = Color(0xFFE8F1EC),
    surfaceVariant = Color(0xFF1D2022),
    onSurfaceVariant = Color(0xFF8A9A92),
    error = Color(0xFFE5484D),
)

@Composable
fun DoorTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)

/** "#rrggbb" from the server, to a Compose colour. Falls back to the brand colour for malformed input. */
fun parseColor(hex: String, fallback: Color = Brand): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)
