package com.smartcampus.door.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartcampus.door.AppPhase
import com.smartcampus.door.DoorApp
import com.smartcampus.door.core.net.EnrollPhase

@Composable
fun AppRoot(phase: AppPhase, app: DoorApp) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (phase) {
            AppPhase.Loading -> Centered { CircularProgressIndicator() }
            is AppPhase.Provisioning -> ProvisioningScreen(phase.error, app::provision)
            is AppPhase.Enrolling -> EnrollmentScreen(phase, onReset = app::reset)
            is AppPhase.Running -> DoorScreen(phase.controller)
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { content() }
}

/** First boot: the installer tells the terminal which server to trust. */
@Composable
fun ProvisioningScreen(error: String?, onSubmit: (url: String, serverKey: String, name: String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Smart Campus", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Brand)
            Text("Connect this door unit", fontSize = 20.sp)
            Text(
                "Enter the server address and the server public key. The terminal only talks to a server that proves it owns that key.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Server address") }, singleLine = true, placeholder = { Text("https://campus.example.org") })
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Server public key (base64)") }, minLines = 3, textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name shown to the admin (optional)") }, singleLine = true)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            Button(onClick = { onSubmit(url, key, name) }, enabled = url.isNotBlank() && key.isNotBlank()) { Text("Ask to connect") }
        }
    }
}

/** The admin recognises this request by the fingerprint, so it is the biggest thing on the screen. */
@Composable
fun EnrollmentScreen(state: AppPhase.Enrolling, onReset: () -> Unit) {
    Centered {
        Text("Smart Campus", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Brand)
        Spacer(Modifier.height(16.dp))
        when (val phase = state.phase) {
            EnrollPhase.Requesting -> Text("Sending the connection request…", fontSize = 20.sp)
            is EnrollPhase.Waiting -> Text("Waiting for an administrator to approve this device", fontSize = 20.sp)
            is EnrollPhase.Failed -> Text("Cannot reach the server: ${phase.message}", fontSize = 18.sp, color = MaterialTheme.colorScheme.error)
            EnrollPhase.Denied -> Text("The request was denied. Ask an administrator, then reset this terminal to try again.", fontSize = 18.sp, color = MaterialTheme.colorScheme.error)
            EnrollPhase.Revoked -> Text("This device was revoked. Reset it to enroll again.", fontSize = 18.sp, color = MaterialTheme.colorScheme.error)
            is EnrollPhase.Approved -> Text("Approved")
        }
        Spacer(Modifier.height(24.dp))
        Text("Key fingerprint", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(state.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 15.sp, modifier = Modifier.widthIn(max = 640.dp))
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.phase is EnrollPhase.Waiting || state.phase is EnrollPhase.Requesting) CircularProgressIndicator(Modifier.padding(end = 4.dp))
            OutlinedButton(onClick = onReset) { Text("Start over") }
        }
    }
}
