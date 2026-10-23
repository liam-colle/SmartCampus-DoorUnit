package com.smartcampus.door.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartcampus.door.core.protocol.Emergency
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private val EmergencyBackground = Color(0xFF07080A)
private val EmergencyText = Color(0xFFF2F5F3)
private val EmergencyMuted = Color(0xFFA3AFA9)

/** Accent per kind. An unknown kind from a newer server gets the fire colour: it is still an alert. */
private fun accentOf(kind: String) = when (kind) {
    "biohazard" -> Color(0xFFB4E61D)
    "nuclear" -> Color(0xFFFFD21F)
    "bomb" -> Color(0xFFFF2D55)
    "seismic" -> Color(0xFFFF9F1C)
    "power" -> Color(0xFF3EA6FF)
    else -> Color(0xFFFF5A36)
}

/**
 * Full screen takeover. It shows the door code, light travelling along chevrons towards the
 * emblem of the emergency, then what it is and what to do. Nothing else, so it reads at a glance.
 */
@Composable
fun EmergencyScreen(doorCode: String?, emergency: Emergency) {
    val accent = accentOf(emergency.kind)
    val transition = rememberInfiniteTransition(label = "emergency")
    val sweep by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    val breathe by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "breathe")

    Box(
        Modifier
            .fillMaxSize()
            .background(EmergencyBackground)
            // A faint wash that breathes with the alarm: it draws the eye without flashing.
            .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.05f + 0.07f * breathe), Color.Transparent))),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                doorCode.orEmpty(),
                color = EmergencyText,
                fontSize = 40.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 6.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                Canvas(Modifier.fillMaxSize()) { drawEmblem(emergency.kind, accent, sweep, breathe, horizontal = maxWidth > maxHeight) }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (emergency.testMode) TestTag(accent)
                Text(emergency.title, color = accent, fontSize = 38.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text(
                    emergency.instructions,
                    color = EmergencyMuted,
                    fontSize = 21.sp,
                    lineHeight = 29.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 760.dp),
                )
            }
        }
    }
}

/** A test must never be mistaken for the real thing by someone who only glances at the screen. */
@Composable
private fun TestTag(accent: Color) {
    Text(
        "TEST — NO REAL EMERGENCY",
        color = EmergencyBackground,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(accent).padding(horizontal = 14.dp, vertical = 5.dp),
    )
}

/** Chevrons on both sides pointing at the emblem (left/right on a wide screen, above/below on a tall one). */
private fun DrawScope.drawEmblem(kind: String, accent: Color, sweep: Float, breathe: Float, horizontal: Boolean) {
    val center = Offset(size.width / 2, size.height / 2)
    val half = (if (horizontal) size.width else size.height) / 2
    val ring = min(min(size.width, size.height) * 0.36f, half * 0.34f)

    // Why a travelling wave: the eye follows it towards the emblem, which is the point of the screen.
    val chevron = ring * 0.42f
    val spacing = chevron * 1.05f
    val first = ring * 1.45f
    val count = ((half - first) / spacing).toInt().coerceIn(1, 5)
    val position = sweep * count
    for (i in 0 until count) {
        val behind = (position - i + count) % count
        val alpha = 0.12f + 0.88f * (1f - behind / count).pow(2)
        val distance = first + (count - 1 - i) * spacing // i = 0 is the outermost chevron
        for (side in intArrayOf(-1, 1)) {
            val at = if (horizontal) Offset(center.x + side * distance, center.y) else Offset(center.x, center.y + side * distance)
            // Pointing at the emblem: a chevron on the negative side faces the positive direction.
            val degrees = if (horizontal) (if (side < 0) 0f else 180f) else (if (side < 0) 90f else 270f)
            drawChevron(at, chevron, degrees, accent.copy(alpha = alpha))
        }
    }

    drawCircle(accent.copy(alpha = 0.10f + 0.10f * breathe), radius = ring * (1.12f + 0.06f * breathe), center = center)
    drawCircle(accent, radius = ring, center = center, style = Stroke(width = ring * 0.06f))
    drawGlyph(kind, center, ring * 0.58f, accent)
}

/** A "›" of height 2·[size] centred on [at], rotated so it points along [degrees] (0 = to the right). */
private fun DrawScope.drawChevron(at: Offset, size: Float, degrees: Float, color: Color) {
    val path = Path().apply {
        moveTo(at.x - size * 0.5f, at.y - size)
        lineTo(at.x + size * 0.5f, at.y)
        lineTo(at.x - size * 0.5f, at.y + size)
    }
    rotate(degrees, pivot = at) {
        drawPath(path, color, style = Stroke(width = size * 0.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The emblem of each emergency, drawn with paths so it stays sharp at any size. [r] is its radius. */
private fun DrawScope.drawGlyph(kind: String, c: Offset, r: Float, color: Color) {
    fun p(x: Float, y: Float) = Offset(c.x + x * r, c.y + y * r)
    // Paths are described in unit coordinates (-1..1) around the centre.
    fun Path.move(x: Float, y: Float) = moveTo(p(x, y).x, p(x, y).y)
    fun Path.line(x: Float, y: Float) = lineTo(p(x, y).x, p(x, y).y)
    fun Path.curve(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
        cubicTo(p(x1, y1).x, p(x1, y1).y, p(x2, y2).x, p(x2, y2).y, p(x3, y3).x, p(x3, y3).y)
    fun polyline(points: List<Pair<Float, Float>>) = Path().apply {
        move(points[0].first, points[0].second)
        points.drop(1).forEach { line(it.first, it.second) }
    }
    val line = Stroke(width = r * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round)

    when (kind) {
        "biohazard" -> {
            // Three overlapping rings around a hub, the shape of the biohazard trefoil.
            for (k in 0 until 3) {
                val a = (-90.0 + 120.0 * k) * PI / 180
                drawCircle(color, radius = r * 0.52f, center = p((cos(a) * 0.5).toFloat(), (sin(a) * 0.5).toFloat()), style = Stroke(width = r * 0.14f))
            }
            drawCircle(color, radius = r * 0.14f, center = c)
        }
        "nuclear" -> {
            for (k in 0 until 3) {
                drawArc(color, startAngle = -90f + 120f * k - 30f, sweepAngle = 60f, useCenter = true, topLeft = p(-1f, -1f), size = Size(2 * r, 2 * r))
            }
            drawCircle(EmergencyBackground, radius = r * 0.3f, center = c)
            drawCircle(color, radius = r * 0.17f, center = c)
        }
        "bomb" -> {
            drawCircle(color, radius = r * 0.66f, center = p(-0.12f, 0.24f))
            drawLine(color, p(0.2f, -0.28f), p(0.5f, -0.6f), strokeWidth = r * 0.16f, cap = StrokeCap.Round)
            for (degrees in listOf(-20.0, -75.0, -130.0)) {
                val a = degrees * PI / 180
                drawLine(color, p(0.62f, -0.72f), p(0.62f + (cos(a) * 0.34).toFloat(), -0.72f + (sin(a) * 0.34).toFloat()), strokeWidth = r * 0.09f, cap = StrokeCap.Round)
            }
        }
        "seismic" -> drawPath(
            polyline(listOf(-1f to 0f, -0.58f to 0f, -0.38f to -0.72f, -0.14f to 0.82f, 0.12f to -0.92f, 0.36f to 0.5f, 0.55f to 0f, 1f to 0f)),
            color,
            style = line,
        )
        "power" -> drawPath(
            polyline(listOf(0.18f to -1f, -0.56f to 0.1f, -0.06f to 0.1f, -0.2f to 1f, 0.56f to -0.16f, 0.06f to -0.16f)).apply { close() },
            color,
        )
        "fire" -> {
            drawPath(
                Path().apply {
                    move(0f, -1f)
                    curve(0.2f, -0.55f, 0.75f, -0.25f, 0.7f, 0.3f)
                    curve(0.65f, 0.8f, 0.3f, 1f, 0f, 1f)
                    curve(-0.3f, 1f, -0.7f, 0.8f, -0.7f, 0.3f)
                    curve(-0.75f, -0.05f, -0.4f, -0.3f, -0.3f, -0.65f)
                    curve(-0.2f, -0.85f, -0.05f, -0.9f, 0f, -1f)
                    close()
                },
                color,
            )
            // The inner flame is cut out with the background colour, which reads as the heart of the fire.
            drawPath(
                Path().apply {
                    move(0f, 0.05f)
                    curve(0.3f, 0.35f, 0.35f, 0.9f, 0f, 0.9f)
                    curve(-0.35f, 0.9f, -0.3f, 0.35f, 0f, 0.05f)
                    close()
                },
                EmergencyBackground,
            )
        }
        else -> {
            drawPath(polyline(listOf(0f to -1f, 0.95f to 0.85f, -0.95f to 0.85f)).apply { close() }, color, style = line)
            drawLine(color, p(0f, -0.3f), p(0f, 0.25f), strokeWidth = r * 0.16f, cap = StrokeCap.Round)
            drawCircle(color, radius = r * 0.09f, center = p(0f, 0.55f))
        }
    }
}
