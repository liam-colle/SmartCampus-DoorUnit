package com.smartcampus.door.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/**
 * Sizes as a share of the screen's short side (1 unit = 1%), so a screen keeps its proportions from a
 * 5.5" handheld to a wall tablet, in either orientation. Text ignores the system font scale on purpose:
 * a kiosk must not break its layout because someone changed an accessibility setting on the device.
 */
class Scale(shortSide: Dp, private val density: Density) {
    private val unit = shortSide / 100
    fun dp(units: Float): Dp = unit * units
    fun sp(units: Float): TextUnit = with(density) { (unit * units).toSp() }
}

private const val MIN_FIT = 0.45f

/**
 * Text that shrinks until it fits its box, so a long title or a long procedure written by an admin
 * never overflows or gets cut. [size] is the preferred size, it never goes below 45% of it, then
 * an ellipsis shows that the rest is cut.
 * The line height is set explicitly: Material keeps its default 24sp one when only the font size
 * changes, which makes big multi-line text overlap itself.
 */
@Composable
fun FitText(
    text: String,
    color: Color,
    size: TextUnit,
    weight: FontWeight,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign = TextAlign.Center,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) {
    var scale by remember(text, size) { mutableFloatStateOf(1f) }
    Text(
        text,
        color = color,
        fontSize = size * scale,
        lineHeight = size * scale * 1.25f,
        fontWeight = weight,
        letterSpacing = letterSpacing,
        textAlign = align,
        maxLines = if (singleLine) 1 else maxLines,
        softWrap = !singleLine,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (it.hasVisualOverflow && scale > MIN_FIT) scale = (scale * 0.92f).coerceAtLeast(MIN_FIT) },
        modifier = modifier,
    )
}
