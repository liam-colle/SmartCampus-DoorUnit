package com.smartcampus.door.core

/**
 * Serial number of a card as the server expects it: upper-case hex, no separators ("04A22B1C").
 * Android hands the UID over as raw bytes, in the order the card reports them.
 */
fun cardUidHex(uid: ByteArray): String = uid.joinToString("") { "%02X".format(it) }

/**
 * ISO 14443 serial numbers are 4, 7 or 10 bytes. Anything else is not a card we can identify, and is
 * dropped before it reaches the network.
 */
fun isPlausibleCardUid(uid: ByteArray): Boolean = uid.size == 4 || uid.size == 7 || uid.size == 10
