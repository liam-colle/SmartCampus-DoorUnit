package com.smartcampus.door.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartcampus.door.MaintenanceInfo
import com.smartcampus.door.core.ActionOutcome
import com.smartcampus.door.core.MaintenanceSession
import com.smartcampus.door.core.protocol.MaintenanceActions
import kotlinx.coroutines.delay

/** The menu closes by itself so a terminal is never left open to the next person. */
private const val IDLE_CLOSE_MS = 60_000L

private enum class Page { HOME, INFO, STATUS, CONFIRM_REVOKE }

/**
 * Opened by the server's say-so after an administrator's or operator's card is read, see
 * `DoorController.maintenance`. Every button that changes something is an action the server checks
 * against the card that opened the menu and writes to the audit log, so what this screen shows is a
 * convenience, not the permission: a button hidden here is refused there as well.
 */
@Composable
fun MaintenanceScreen(
    session: MaintenanceSession,
    info: MaintenanceInfo,
    online: Boolean,
    currentStatus: String?,
    outcome: ActionOutcome?,
    onAction: (action: String, status: String?) -> Unit,
    onClose: () -> Unit,
) {
    // Any touch counts as activity and restarts the countdown.
    var touches by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf(Page.HOME) }
    LaunchedEffect(touches) {
        delay(IDLE_CLOSE_MS)
        onClose()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        touches++
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Maintenance", fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            if (session.name.isNotBlank()) Text("Signed in by card as ${session.name}", color = MaterialTheme.colorScheme.onSurfaceVariant)

            when (page) {
                Page.HOME -> Home(session, onGo = { page = it }, onRestart = { onAction(MaintenanceActions.RESTART, null) }, onClose = onClose)
                Page.INFO -> Info(info, online, onBack = { page = Page.HOME })
                Page.STATUS -> StatusList(session, currentStatus, onPick = { onAction(MaintenanceActions.SET_STATUS, it) }, onBack = { page = Page.HOME })
                Page.CONFIRM_REVOKE -> ConfirmRevoke(onConfirm = { onAction(MaintenanceActions.REVOKE, null) }, onCancel = { page = Page.HOME })
            }
            outcome?.let { Outcome(it) }
        }
    }
}

@Composable
private fun Home(session: MaintenanceSession, onGo: (Page) -> Unit, onRestart: () -> Unit, onClose: () -> Unit) {
    MenuButton("Information") { onGo(Page.INFO) }
    MenuButton("Set room status", enabled = session.menu.statuses.isNotEmpty()) { onGo(Page.STATUS) }
    MenuButton("Restart app", onClick = onRestart)
    // Only offered to administrators, and refused by the server for anyone else.
    if (session.menu.canRevoke) MenuButton("Revoke this terminal", danger = true) { onGo(Page.CONFIRM_REVOKE) }
    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
}

@Composable
private fun MenuButton(label: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
    ) { Text(label, fontSize = 18.sp, modifier = Modifier.padding(vertical = 6.dp)) }
}

@Composable
private fun Info(info: MaintenanceInfo, online: Boolean, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InfoRow("Terminal", info.name.ifBlank { "unnamed" })
        InfoRow("Device id", info.deviceId, mono = true)
        InfoRow("Server", info.serverUrl, mono = true)
        InfoRow("Connection", if (online) "online" else "offline")
        InfoRow("Key fingerprint", info.fingerprint, mono = true)
        InfoRow("App version", info.appVersion)
    }
    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
}

@Composable
private fun StatusList(session: MaintenanceSession, current: String?, onPick: (String) -> Unit, onBack: () -> Unit) {
    Text("Status of this room", color = MaterialTheme.colorScheme.onSurfaceVariant)
    session.menu.statuses.forEach { status ->
        val selected = status.key == current
        OutlinedButton(onClick = { onPick(status.key) }, enabled = !selected, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(parseColor(status.color)))
                Text(if (selected) "${status.label} (current)" else status.label, fontSize = 18.sp)
            }
        }
    }
    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
}

@Composable
private fun ConfirmRevoke(onConfirm: () -> Unit, onCancel: () -> Unit) {
    Text(
        "This takes the terminal out of service: it erases its key and settings and must be enrolled and approved again in the console.",
        color = MaterialTheme.colorScheme.error,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Revoke") }
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

/** What the server answered to the last button: confirmation, or the reason it refused. */
@Composable
private fun Outcome(outcome: ActionOutcome) {
    val text = if (outcome.ok) "Done" else outcome.error ?: "Refused"
    Text(text, color = if (outcome.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "–" }, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, fontSize = if (mono) 13.sp else 16.sp)
    }
}
