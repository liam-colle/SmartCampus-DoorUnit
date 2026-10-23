package com.smartcampus.door.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartcampus.door.core.CardEvent
import com.smartcampus.door.core.OUTCOME_CHECKING
import com.smartcampus.door.core.OUTCOME_OFFLINE
import com.smartcampus.door.core.protocol.CardOutcome

/** How long the confirmation stays before the door, or the maintenance menu, shows again. */
const val CARD_RESULT_SCREEN_MS = 1_500L

private val Granted = Color(0xFF00A86B)
private val Denied = Color(0xFFE5484D)

private class Verdict(val icon: ImageVector, val color: Color, val title: String, val detail: String)

/**
 * The card was read, so say so, and say whether it was accepted. Every outcome has its own wording:
 * a card whose number was read is never reported as "not recognised" (it is simply not registered).
 */
private fun verdictFor(card: CardEvent): Verdict {
    val name = card.name?.takeIf { it.isNotBlank() }
    return when (card.outcome) {
        OUTCOME_CHECKING -> Verdict(Icons.Filled.Nfc, Color(0xFF8A9A92), "Card read", "Checking…")
        CardOutcome.MAINTENANCE -> Verdict(Icons.Filled.Check, Granted, "Access granted", name?.let { "$it, opening maintenance" } ?: "Opening maintenance")
        CardOutcome.RECOGNISED -> Verdict(Icons.Filled.Check, Granted, "Access granted", name?.let { "Welcome, $it" } ?: "Welcome")
        CardOutcome.CAPTURED -> Verdict(Icons.Filled.Check, Granted, "Card read", "Added to the registration")
        CardOutcome.DUPLICATE -> Verdict(Icons.Filled.Close, Denied, "Already read", "This card was already added")
        CardOutcome.ALREADY_REGISTERED -> Verdict(Icons.Filled.Close, Denied, "Already registered", "This card belongs to someone")
        CardOutcome.DISABLED -> Verdict(Icons.Filled.Close, Denied, "Access denied", "This card is disabled")
        CardOutcome.RATE_LIMITED -> Verdict(Icons.Filled.Close, Denied, "Access denied", "Too many cards, wait a moment")
        OUTCOME_OFFLINE -> Verdict(Icons.Filled.Close, Denied, "Card read", "Offline: the card cannot be checked")
        CardOutcome.UNKNOWN -> Verdict(Icons.Filled.Close, Denied, "Access denied", "This card is not registered")
        else -> Verdict(Icons.Filled.Close, Denied, "Card read", "The server gave no answer we understand")
    }
}

/** Full-screen confirmation of a card read. Shown briefly before the maintenance menu opens. */
@Composable
fun CardResultScreen(card: CardEvent) {
    val verdict = verdictFor(card)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(120.dp).clip(CircleShape).background(verdict.color), contentAlignment = Alignment.Center) {
                Icon(verdict.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(72.dp))
            }
            Text(verdict.title, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
            Text(verdict.detail, fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
