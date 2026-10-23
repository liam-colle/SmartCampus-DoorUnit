package com.smartcampus.door.ui

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartcampus.door.AppPhase
import com.smartcampus.door.DoorApp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.smartcampus.door.core.crypto.fingerprintOf
import com.smartcampus.door.core.crypto.formatFingerprint
import com.smartcampus.door.core.net.EnrollPhase
import com.smartcampus.door.core.provisioning.ServerKeyClient
import com.smartcampus.door.core.provisioning.ServerKeyInfo
import com.smartcampus.door.core.provisioning.parseProvisioningLink
import kotlinx.coroutines.launch
import java.util.Base64

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

/** What the "fetch the key from the server" step is doing. */
private sealed interface KeyFetch {
    data object Idle : KeyFetch
    data object Loading : KeyFetch
    data class Failed(val message: String) : KeyFetch
    data class Fetched(val info: ServerKeyInfo) : KeyFetch
}

/**
 * First boot: the installer tells the terminal which server to trust. Three ways, easiest first: scan
 * the QR code from the console (the key travels in the code), fetch the key from the server and
 * confirm its fingerprint, or paste it. Whatever the way, the terminal pins the key afterwards.
 */
@Composable
fun ProvisioningScreen(error: String?, onSubmit: (url: String, serverKey: String, name: String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var fetch by remember { mutableStateOf<KeyFetch>(KeyFetch.Idle) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var keyFrom by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult // cancelled
        runCatching { parseProvisioningLink(text) }
            .onSuccess {
                url = it.serverUrl
                key = it.serverPublicKey
                keyFrom = "the QR code"
                scanError = null
                fetch = KeyFetch.Idle
            }
            .onFailure { scanError = it.message }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Smart Campus", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Brand)
            Text("Connect this door unit", fontSize = 20.sp)
            Text(
                "Scan the QR code shown in the console (Devices, Provision a device), or enter the server address and fetch its key. The terminal only talks to a server that proves it owns that key.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the code shown in the console").setBeepEnabled(false).setOrientationLocked(false)) }) {
                Text("Scan the QR code")
            }
            if (scanError != null) Text(scanError!!, color = MaterialTheme.colorScheme.error)

            OutlinedTextField(
                url,
                { url = it; if (keyFrom == "the server") { key = ""; keyFrom = null }; fetch = KeyFetch.Idle },
                Modifier.fillMaxWidth(),
                label = { Text("Server address") },
                singleLine = true,
                placeholder = { Text("https://campus.example.org") },
            )
            if (keyFrom == null) {
                OutlinedButton(
                    onClick = {
                        fetch = KeyFetch.Loading
                        scope.launch {
                            fetch = runCatching { ServerKeyClient().fetch(url) }
                                .fold({ KeyFetch.Fetched(it) }, { KeyFetch.Failed(it.message ?: "Could not fetch the key") })
                        }
                    },
                    enabled = url.isNotBlank() && fetch != KeyFetch.Loading,
                ) { Text("Fetch the key from the server") }
            }
            when (val state = fetch) {
                KeyFetch.Idle -> Unit
                KeyFetch.Loading -> CircularProgressIndicator()
                is KeyFetch.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
                is KeyFetch.Fetched -> FingerprintConfirmation(state.info) {
                    key = state.info.publicKey
                    keyFrom = "the server"
                    fetch = KeyFetch.Idle
                }
            }
            if (keyFrom != null) {
                Text("Server key received from $keyFrom", color = Brand)
                Text(
                    formatFingerprint(fingerprintOf(Base64.getDecoder().decode(key.filterNot(Char::isWhitespace)))),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Or paste the server public key (base64)") }, minLines = 3, textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
            }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name shown to the admin (optional)") }, singleLine = true)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            Button(onClick = { onSubmit(url, key, name) }, enabled = url.isNotBlank() && key.isNotBlank()) { Text("Ask to connect") }
        }
    }
}

/** Trust on first use: the installer compares this fingerprint with the console and accepts it once. */
@Composable
private fun FingerprintConfirmation(info: ServerKeyInfo, onTrust: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("The server presented this key. Check that its fingerprint matches the one in the console, then trust it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatFingerprint(info.fingerprint), fontFamily = FontFamily.Monospace, fontSize = 16.sp)
        Button(onClick = onTrust) { Text("Trust this server") }
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
