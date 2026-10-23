package com.smartcampus.door

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.smartcampus.door.core.audio.AlarmPlayer
import com.smartcampus.door.core.audio.AlarmSynth

/**
 * Loops a synthesised alarm on the alarm stream. The stream is pushed to its maximum and [level]
 * is applied on the track itself, so an installer who left the alarm stream low cannot silence a
 * real emergency and a test can still be quiet.
 */
class AudioTrackAlarm(context: Context) : AlarmPlayer {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var track: AudioTrack? = null

    @Synchronized
    override fun start(kind: String, level: Float) {
        stop()
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }

        val samples = AlarmSynth.cycle(kind)
        val bytes = samples.size * Short.SIZE_BYTES
        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AlarmSynth.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bytes)
            .build()
        created.write(samples, 0, samples.size)
        created.setLoopPoints(0, samples.size, -1)
        created.setVolume(level.coerceIn(0f, 1f))
        created.play()
        track = created
    }

    @Synchronized
    override fun stop() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }
}
