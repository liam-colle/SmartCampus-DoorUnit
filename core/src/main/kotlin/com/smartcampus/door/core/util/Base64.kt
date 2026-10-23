package com.smartcampus.door.core.util

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Standard Base64 with padding. `java.util.Base64` only exists from Android 8 (API 26) and the app
 * supports Android 6, so the core library uses Kotlin's own implementation, identical on every platform.
 */
@OptIn(ExperimentalEncodingApi::class)
object B64 {
    fun encode(bytes: ByteArray): String = Base64.encode(bytes)

    /** Throws [IllegalArgumentException] on anything that is not valid Base64. */
    fun decode(text: String): ByteArray = Base64.decode(text)
}
