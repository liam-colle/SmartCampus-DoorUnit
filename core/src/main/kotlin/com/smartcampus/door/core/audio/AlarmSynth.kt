package com.smartcampus.door.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Renders one loop of each emergency alarm from a few oscillators, so the door unit needs no sound
 * files: the alarm works offline, cannot be tampered with in transit and starts instantly.
 *
 * The voice is a clean sine with a touch of FM (a glassy, digital edge) and a short echo that wraps
 * around the loop, so the tail of one cycle leads into the next without a click.
 */
object AlarmSynth {
    const val SAMPLE_RATE = 22_050

    /** Where each alarm is loud enough to be heard on a small speaker: no content below ~100 Hz alone. */
    fun cycle(kind: String, sampleRate: Int = SAMPLE_RATE): ShortArray {
        val track = when (kind) {
            "fire" -> fire(sampleRate)
            "biohazard" -> biohazard(sampleRate)
            "nuclear" -> nuclear(sampleRate)
            "bomb" -> bomb(sampleRate)
            "seismic" -> seismic(sampleRate)
            "power" -> power(sampleRate)
            else -> fire(sampleRate) // an unknown kind from a newer server is still an alarm
        }
        return track.render()
    }

    /** Three bright pulses, a pause: the classic evacuation rhythm in a modern voice. */
    private fun fire(rate: Int) = Track(2.0, rate, echoSeconds = 0.12, feedback = 0.25).apply {
        for (i in 0 until 3) {
            val at = i * 0.4
            tone(at, 0.24, 1100.0, harmonics = listOf(1.0 to 1.0, 2.0 to 0.3, 3.0 to 0.12), fmIndex = 0.6, fmRatio = 2.0)
            tone(at, 0.24, 550.0, gain = 0.4)
        }
    }

    /** A slow, wavering rise and fall: unsettling without being aggressive. */
    private fun biohazard(rate: Int) = Track(3.0, rate, echoSeconds = 0.25, feedback = 0.3).apply {
        val voice = listOf(1.0 to 0.5, 2.0 to 0.2, 3.0 to 0.1)
        tone(0.0, 1.4, 520.0, 880.0, harmonics = voice, attack = 0.12, release = 0.25, tremoloHz = 6.0, tremoloDepth = 0.35, fmIndex = 0.3, fmRatio = 1.5)
        tone(1.5, 1.4, 880.0, 520.0, harmonics = voice, attack = 0.12, release = 0.25, tremoloHz = 6.0, tremoloDepth = 0.35, fmIndex = 0.3, fmRatio = 1.5)
    }

    /** Heavy descending horn with a high ping on top, twice per cycle. */
    private fun nuclear(rate: Int) = Track(3.0, rate, echoSeconds = 0.2, feedback = 0.3).apply {
        val horn = listOf(1.0 to 1.0, 2.0 to 0.5, 3.0 to 0.35, 4.0 to 0.15)
        for (at in listOf(0.0, 1.5)) {
            tone(at, 1.2, 330.0, 165.0, harmonics = horn, attack = 0.05, release = 0.3)
            tone(at, 0.2, 1760.0, gain = 0.3, attack = 0.005, release = 0.15, fmIndex = 0.8, fmRatio = 3.0)
        }
    }

    /** Fast alternating pings like a sonar sweep, then silence: urgent but not panicked. */
    private fun bomb(rate: Int) = Track(2.4, rate, echoSeconds = 0.09, feedback = 0.3).apply {
        for (i in 0 until 6) {
            tone(i * 0.17, 0.12, if (i % 2 == 0) 1320.0 else 990.0, harmonics = listOf(1.0 to 1.0, 2.0 to 0.25), fmIndex = 0.8, fmRatio = 2.0, attack = 0.004, release = 0.05)
        }
    }

    /** Rolling low pulses (rich in harmonics, so a small speaker still carries them) then a rising chirp. */
    private fun seismic(rate: Int) = Track(3.0, rate, echoSeconds = 0.15, feedback = 0.25).apply {
        val rumble = listOf(1.0 to 1.0, 2.0 to 0.8, 3.0 to 0.6, 4.0 to 0.3)
        for (i in 0 until 3) tone(i * 0.5, 0.45, 110.0, 90.0, harmonics = rumble, attack = 0.04, release = 0.15, tremoloHz = 9.0, tremoloDepth = 0.5)
        tone(1.7, 1.2, 440.0, 880.0, gain = 0.7, harmonics = listOf(1.0 to 1.0, 2.0 to 0.2), attack = 0.1, release = 0.3, fmIndex = 0.4, fmRatio = 2.0)
    }

    /** A soft descending three-note chime. Not a siren: nobody is in danger, they need to be informed. */
    private fun power(rate: Int) = Track(6.0, rate, echoSeconds = 0.3, feedback = 0.35).apply {
        for ((i, hz) in listOf(659.0, 523.0, 440.0).withIndex()) {
            tone(i * 0.5, 0.9, hz, gain = 0.8, attack = 0.01, release = 0.5, decay = 3.0, fmIndex = 1.0, fmRatio = 3.5)
        }
    }

    private class Track(val seconds: Double, val rate: Int, val echoSeconds: Double, val feedback: Double) {
        private val samples = DoubleArray((seconds * rate).toInt())

        /**
         * Adds one note. [from]/[to] sweep the pitch linearly; [harmonics] are (multiple, level);
         * [fmIndex]/[fmRatio] add phase modulation; [decay] makes the note die away like a bell.
         */
        fun tone(
            at: Double,
            length: Double,
            from: Double,
            to: Double = from,
            gain: Double = 1.0,
            harmonics: List<Pair<Double, Double>> = listOf(1.0 to 1.0),
            attack: Double = 0.01,
            release: Double = 0.05,
            tremoloHz: Double = 0.0,
            tremoloDepth: Double = 0.0,
            fmIndex: Double = 0.0,
            fmRatio: Double = 1.0,
            decay: Double = 0.0,
        ) {
            val start = (at * rate).toInt()
            val count = (length * rate).toInt()
            val norm = harmonics.sumOf { it.second }
            var phase = 0.0
            for (n in 0 until count) {
                val index = start + n
                if (index >= samples.size) break
                val t = n.toDouble() / rate
                phase += 2 * PI * (from + (to - from) * (n.toDouble() / count)) / rate
                val modulated = phase + fmIndex * sin(fmRatio * phase)
                val voice = harmonics.sumOf { (multiple, level) -> level * sin(multiple * modulated) } / norm
                val envelope = min(1.0, t / attack) * min(1.0, (length - t) / release) * exp(-decay * t)
                val tremolo = 1 - tremoloDepth * (0.5 + 0.5 * sin(2 * PI * tremoloHz * t))
                samples[index] += voice * envelope * tremolo * gain
            }
        }

        fun render(): ShortArray {
            // The echo reads from the same buffer, wrapped: the loop point stays seamless.
            val delay = (echoSeconds * rate).toInt().coerceAtLeast(1)
            val out = samples.copyOf()
            repeat(3) {
                val previous = out.copyOf()
                for (i in out.indices) out[i] = samples[i] + feedback * previous[(i - delay).mod(out.size)]
            }
            val peak = max(1e-9, out.maxOf(::abs))
            return ShortArray(out.size) { (out[it] / peak * PEAK_LEVEL * Short.MAX_VALUE).toInt().toShort() }
        }
    }

    /** Headroom below full scale, so the speaker amplifier does not clip the loudest note. */
    private const val PEAK_LEVEL = 0.9
}
