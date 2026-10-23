package com.smartcampus.door.core

import java.security.SecureRandom

private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford base32
private val random = SecureRandom()

/** 26 character ULID: 48 bit millisecond timestamp, then 80 random bits. Used as the envelope id. */
fun newUlid(nowMs: Long = System.currentTimeMillis()): String {
    val time = StringBuilder()
    var t = nowMs
    repeat(10) {
        time.append(ALPHABET[(t % 32).toInt()])
        t /= 32
    }
    val tail = StringBuilder()
    repeat(16) { tail.append(ALPHABET[random.nextInt(32)]) }
    return time.reverse().toString() + tail
}

val ULID_REGEX = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")
