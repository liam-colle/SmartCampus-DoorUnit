package com.smartcampus.door

import android.app.Application
import com.smartcampus.door.core.DoorController
import com.smartcampus.door.core.audio.AssetDownloader
import com.smartcampus.door.core.crypto.DeviceIdentity
import com.smartcampus.door.core.crypto.fingerprintOf
import com.smartcampus.door.core.net.DeviceLink
import com.smartcampus.door.core.net.EnrollPhase
import com.smartcampus.door.core.net.EnrollmentClient
import com.smartcampus.door.core.net.LinkEvent
import com.smartcampus.door.core.net.enrollmentFlow
import com.smartcampus.door.core.storage.DeviceConfig
import com.smartcampus.door.core.storage.DeviceConfigStore
import com.smartcampus.door.core.storage.FileStorage
import com.smartcampus.door.core.crypto.formatFingerprint
import com.smartcampus.door.core.crypto.parseServerKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.Base64

/** What the screen is doing: asking for settings, waiting for an admin, or showing the door. */
sealed interface AppPhase {
    data object Loading : AppPhase
    data class Provisioning(val error: String? = null) : AppPhase
    data class Enrolling(val phase: EnrollPhase, val fingerprint: String) : AppPhase
    data class Running(val controller: DoorController) : AppPhase
}

/**
 * Process wide owner of the door unit: provisioning, enrollment, then the connection and controller.
 * It lives in the Application so the connection survives the activity being recreated.
 */
class DoorApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var storage: FileStorage
    private lateinit var configStore: DeviceConfigStore
    private var work: Job? = null

    private val _phase = MutableStateFlow<AppPhase>(AppPhase.Loading)
    val phase: StateFlow<AppPhase> = _phase.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        storage = FileStorage(File(filesDir, "door"))
        configStore = DeviceConfigStore(storage)
        resume()
    }

    /** Continues from wherever the device was before it was restarted. */
    private fun resume() {
        val config = configStore.load()
        when {
            config == null -> _phase.value = AppPhase.Provisioning()
            config.enrolled -> startRunning(config)
            else -> startEnrollment(config)
        }
    }

    /** Saves the installer's settings and asks the server to connect. */
    fun provision(serverUrl: String, serverPublicKey: String, name: String) {
        val url = serverUrl.trim().trimEnd('/')
        val key = serverPublicKey.filterNot(Char::isWhitespace)
        val problem = when {
            url.toHttpUrlOrNull() == null -> "The server address must look like https://campus.example.org"
            runCatching { parseServerKey(Base64.getDecoder().decode(key)) }.isFailure ->
                "The server key is not valid. Paste the whole base64 public key shown by the server"
            else -> null
        }
        if (problem != null) {
            _phase.value = AppPhase.Provisioning(problem)
            return
        }
        val config = DeviceConfig(serverUrl = url, serverPublicKey = key, name = name.trim())
        configStore.save(config)
        startEnrollment(config)
    }

    private fun startEnrollment(config: DeviceConfig) {
        work?.cancel()
        work = scope.launch {
            val identity = AndroidKeystoreIdentity.loadOrCreate()
            val fingerprint = formatFingerprint(fingerprintOf(identity.publicKeySpki))
            enrollmentFlow(EnrollmentClient(config.serverUrl), identity, "door", config.name, config.deviceId) { ticket ->
                configStore.save(config.copy(deviceId = ticket.deviceId))
            }.collect { phase ->
                when (phase) {
                    is EnrollPhase.Approved -> {
                        val enrolled = (configStore.load() ?: config).copy(deviceId = phase.deviceId, enrolled = true)
                        configStore.save(enrolled)
                        startRunning(enrolled)
                    }
                    else -> _phase.value = AppPhase.Enrolling(phase, fingerprint)
                }
            }
        }
    }

    private fun startRunning(config: DeviceConfig) {
        work?.cancel()
        val identity: DeviceIdentity = AndroidKeystoreIdentity.loadOrCreate()
        val deviceId = checkNotNull(config.deviceId) { "an enrolled device has an id" }
        val assets = AssetDownloader(config.serverUrl, deviceId, identity, File(cacheDir, "audio"))
        val controller = DoorController(storage, assets, MediaPlayerAudio(), scope)
        val serverKey = parseServerKey(Base64.getDecoder().decode(config.serverPublicKey))
        _phase.value = AppPhase.Running(controller)

        work = scope.launch {
            DeviceLink(config.serverUrl, deviceId, identity, serverKey).run { event ->
                controller.onEvent(event)
                if (event is LinkEvent.Disconnected && event.revoked) reset()
            }
        }
    }

    /** Forgets everything, including the key: a revoked or moved terminal enrolls again from scratch. */
    fun reset() {
        work?.cancel()
        runCatching { AndroidKeystoreIdentity.delete() }
        listOf("device.json", "state.json", "seen-ids.json").forEach(storage::delete)
        File(cacheDir, "audio").deleteRecursively()
        _phase.value = AppPhase.Provisioning()
    }
}
