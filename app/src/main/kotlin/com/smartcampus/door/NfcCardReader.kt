package com.smartcampus.door

import android.app.Activity
import android.nfc.NfcAdapter
import com.smartcampus.door.core.cardUidHex
import com.smartcampus.door.core.isPlausibleCardUid

/**
 * Reads the serial number of every card held to the antenna while the activity is in front.
 *
 * Reader mode rather than the intent dispatch: no other app can grab the tag, no system sound plays
 * on a kiosk, and the card is never opened (SKIP_NDEF_CHECK), only its serial number is read.
 * The callback runs on a binder thread, so [onCard] must not touch the UI.
 */
class NfcCardReader(private val activity: Activity, private val onCard: (String) -> Unit) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    /** False on a terminal without NFC, or with NFC switched off in the settings. */
    val available: Boolean get() = adapter?.isEnabled == true

    fun start() {
        adapter?.enableReaderMode(
            activity,
            { tag -> tag.id?.takeIf(::isPlausibleCardUid)?.let { onCard(cardUidHex(it)) } },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
    }

    fun stop() {
        adapter?.disableReaderMode(activity)
    }
}
