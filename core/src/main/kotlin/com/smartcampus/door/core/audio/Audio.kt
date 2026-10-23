package com.smartcampus.door.core.audio

import com.smartcampus.door.core.crypto.DeviceIdentity
import com.smartcampus.door.core.crypto.signRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Plays a cached sound. Implemented on Android with MediaPlayer. */
interface AudioPlayer {
    suspend fun play(file: File, volumePercent: Int)
}

/** Where sounds come from. A seam so the controller can be tested without a server. */
interface AssetSource {
    suspend fun fetch(assetId: String, sha256: String, size: Long): File
}

class AssetException(message: String) : IOException(message)

/** A broadcast is a short message; a file far above the announced size is hostile or broken. */
private const val MAX_ASSET_BYTES = 25L * 1024 * 1024

/**
 * Downloads sounds over HTTPS with a request signed by the device key, checks the SHA-256 announced in
 * `audio.play` and caches the file by asset id. A cached file is checked again before it is reused.
 */
class AssetDownloader(
    private val baseUrl: String,
    private val deviceId: String,
    private val identity: DeviceIdentity,
    private val cacheDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val client: OkHttpClient = OkHttpClient(),
) : AssetSource {
    init {
        cacheDir.mkdirs()
    }

    override suspend fun fetch(assetId: String, sha256: String, size: Long): File = withContext(Dispatchers.IO) {
        require(assetId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "invalid asset id" }
        val cached = File(cacheDir, assetId)
        if (cached.isFile && hashOf(cached) == sha256) return@withContext cached

        val path = "/api/assets/$assetId/download"
        val signed = signRequest(identity, deviceId, "GET", path, clock())
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("X-Device-Id", signed.deviceId)
            .header("X-Timestamp", signed.timestamp)
            .header("X-Signature", signed.signature)
            .build()

        val partial = File(cacheDir, "$assetId.part")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AssetException("download refused (${response.code})")
            val body = response.body ?: throw AssetException("empty response")
            var received = 0L
            partial.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        received += n
                        if (received > size.coerceAtMost(MAX_ASSET_BYTES)) throw AssetException("file is larger than announced")
                        out.write(buffer, 0, n)
                    }
                }
            }
        }
        if (!MessageDigest.isEqual(hashOf(partial).toByteArray(), sha256.toByteArray())) {
            partial.delete()
            throw AssetException("hash mismatch")
        }
        check(partial.renameTo(cached) || (cached.delete() && partial.renameTo(cached))) { "could not cache the file" }
        cached
    }

    private fun hashOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
