package com.smartcampus.door

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import com.smartcampus.door.core.audio.CardTone
import com.smartcampus.door.core.audio.CardTones

/**
 * The card sounds, from the tones every Android phone ships: a short beep when the card is read, a
 * rising pair when it was accepted, a low buzz when it was refused. The system tones keep the sound
 * the same on every terminal and need no audio asset.
 */
class ToneGeneratorCards : CardTones {
    private val main = Handler(Looper.getMainLooper())

    override fun play(tone: CardTone, level: Float) {
        val (type, durationMs) = when (tone) {
            CardTone.READ -> ToneGenerator.TONE_PROP_BEEP to 150
            CardTone.GRANTED -> ToneGenerator.TONE_PROP_ACK to 400
            CardTone.DENIED -> ToneGenerator.TONE_PROP_NACK to 500
        }
        // A terminal whose audio output is busy or missing throws here. A card read must still work
        // without a sound, so the tone is dropped rather than the exception propagated.
        val generator = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, (level * 100).toInt().coerceIn(1, 100)) }.getOrNull() ?: return
        generator.startTone(type, durationMs)
        main.postDelayed({ generator.release() }, durationMs + RELEASE_MARGIN_MS)
    }

    private companion object {
        const val RELEASE_MARGIN_MS = 200L
    }
}
