package com.smartcampus.door

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Process
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
import com.smartcampus.door.core.util.B64
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

/** What the screen is doing: asking for settings, waiting for an admin, or showing the door. */
sealed interface AppPhase {
    data object Loading : AppPhase
    data class Provisioning(val error: String? = null) : AppPhase
    data class Enrolling(val phase: EnrollPhase, val fingerprint: String) : AppPhase
    data class Running(val controller: DoorController) : AppPhase
}

data class MaintenanceInfo(val name: String, val deviceId: String, val serverUrl: String, val fingerprint: String, val appVersion: String)

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
            runCatching { parseServerKey(B64.decode(key)) }.isFailure ->
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
        val controller = DoorController(storage, assets, MediaPlayerAudio(), scope, AudioTrackAlarm(this), tones = ToneGeneratorCards())
        val serverKey = parseServerKey(B64.decode(config.serverPublicKey))
        _phase.value = AppPhase.Running(controller)

        work = scope.launch {
            DeviceLink(config.serverUrl, deviceId, identity, serverKey).run { event ->
                controller.onEvent(event)
                if (event is LinkEvent.Disconnected && event.revoked) reset()
            }
        }
    }

    /** Called from the NFC thread with the serial number of a card held to the antenna. */
    fun onCardRead(cardUid: String) {
        val controller = (_phase.value as? AppPhase.Running)?.controller ?: return
        scope.launch { controller.onCardRead(cardUid) }
    }

    /** What the maintenance menu shows about this terminal. Nothing secret: no key, no session data. */
    fun maintenanceInfo(): MaintenanceInfo {
        val config = configStore.load()
        val identity = runCatching { AndroidKeystoreIdentity.loadOrCreate() }.getOrNull()
        return MaintenanceInfo(
            name = config?.name.orEmpty(),
            deviceId = config?.deviceId.orEmpty(),
            serverUrl = config?.serverUrl.orEmpty(),
            fingerprint = identity?.let { formatFingerprint(fingerprintOf(it.publicKeySpki)) }.orEmpty(),
            appVersion = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty(),
        )
    }

    /**
     * Starts the app again from scratch. The kiosk is the home app, so Android brings it back by itself when
     * the process dies; the alarm covers a terminal where it is not. This restarts the app, not the
     * hardware: a real reboot needs system privileges that an ordinary app does not have.
     */
    fun restartApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) ?: return
        val pending = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        (getSystemService(ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC, System.currentTimeMillis() + RESTART_DELAY_MS, pending)
        Process.killProcess(Process.myPid())
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

private const val RESTART_DELAY_MS = 1_000L
