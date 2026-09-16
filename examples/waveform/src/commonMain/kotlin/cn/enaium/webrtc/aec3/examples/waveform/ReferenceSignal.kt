/*
 * Copyright (c) 2026 Enaium
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package cn.enaium.webrtc.aec3.examples.waveform

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Where the far-end (render) signal comes from: what the speaker plays and what
 * AEC3 is told to cancel from the capture.
 */
enum class FarEndSource(val label: String) {
    /**
     * The 440 Hz tone the basic example plays. AEC3 cancels it, but a single
     * tone only excites one bin of the adaptive filter.
     */
    TONE("440 Hz tone"),

    /**
     * Flat spectrum: excites the whole band, so the filter converges the way it
     * does on real far-end audio and the cancellation is visible everywhere.
     */
    NOISE("White noise"),

    /**
     * The capture itself: the microphone is read, run through AEC3 and played
     * back through the speaker, which is what puts it back into the microphone.
     * AEC3 gets the played samples as its render reference, so what it removes
     * is the loudspeaker->microphone part of the loop - the difference between
     * an open microphone and one that howls.
     */
    MICROPHONE("Microphone (loopback)");

    /** Whether the far end is synthesised rather than played back from the capture. */
    val isGenerated: Boolean get() = this != MICROPHONE
}

/**
 * Generates the far-end (render) signal of the two synthesised [FarEndSource]s.
 *
 * The generator is seeded, so a given signal and level always produce the same
 * samples - which makes screenshots and measurements comparable.
 */
class ReferenceGenerator(seed: Int = 0x5EED) {

    private val random = Random(seed)

    private var phase = 0.0

    /** Fills the first [count] samples of [destination] with [source] at [amplitude]. */
    fun generate(destination: FloatArray, count: Int, source: FarEndSource, amplitude: Float) {
        when (source) {
            FarEndSource.TONE -> {
                for (i in 0 until count) {
                    destination[i] = (sin(2.0 * PI * TONE_HZ * phase / SAMPLE_RATE) * amplitude).toFloat()
                    phase += 1.0
                    if (phase >= SAMPLE_RATE) phase -= SAMPLE_RATE.toDouble()
                }
            }

            FarEndSource.NOISE -> {
                for (i in 0 until count) {
                    destination[i] = (random.nextFloat() * 2f - 1f) * amplitude
                }
            }

            // The microphone is looped back from the playback, not generated.
            FarEndSource.MICROPHONE -> destination.fill(0f, 0, count)
        }
    }

    private companion object {
        const val TONE_HZ = 440.0
    }
}

/** The linear amplitude of [db] dBFS. */
fun dbfsToAmplitude(db: Float): Float = 10f.pow(db / 20f)