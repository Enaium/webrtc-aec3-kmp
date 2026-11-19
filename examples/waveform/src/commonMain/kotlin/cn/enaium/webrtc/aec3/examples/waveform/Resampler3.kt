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
import kotlin.math.cos
import kotlin.math.sin

/**
 * 3:1 resampler between the 48 kHz the audio devices run at and the 16 kHz AEC3
 * works at.
 *
 * One low-pass prototype serves both directions, and both keep their filter
 * history across frames: a resampler that restarted every 10 ms frame would put
 * a step at every boundary - exactly the buzz this exists to avoid.
 *
 * 48 kHz frame in, [AEC_FRAME_SAMPLES] out on [downsample]; the other way round
 * on [upsample]. The filter is the same 63-tap windowed sinc either way, so the
 * two directions have the same group delay and the render/capture alignment the
 * canceller sees is unaffected.
 */
internal class Resampler3 {

    /** The last [TAPS] - 1 input samples, so a frame can start mid-filter. */
    private val history = FloatArray(TAPS - 1)

    /** History plus the frame being filtered, laid out linearly for indexing. */
    private val work = FloatArray(TAPS - 1 + FRAME)

    /** 48 kHz in, 16 kHz out: every third sample of the filtered signal. */
    fun downsample(input: FloatArray, output: FloatArray) {
        history.copyInto(work, 0, 0, TAPS - 1)
        input.copyInto(work, TAPS - 1, 0, FRAME)
        val base = TAPS - 1
        for (n in output.indices) {
            var acc = 0f
            val last = base + 3 * n
            var k = 0
            while (k < TAPS) {
                acc += H[k] * work[last - k]
                k++
            }
            output[n] = acc
        }
        // Keep the tail of this frame for the next one.
        work.copyInto(history, 0, base + FRAME - (TAPS - 1), base + FRAME)
    }

    /** 16 kHz in, 48 kHz out: zero-stuff then filter with the same prototype, scaled to unity gain. */
    fun upsample(input: FloatArray, output: FloatArray) {
        history.copyInto(work, 0, 0, TAPS - 1)
        input.copyInto(work, TAPS - 1, 0, AEC_FRAME_SAMPLES)
        val base = TAPS - 1
        for (i in output.indices) {
            // Only the phases of the prototype that land on an input sample count.
            var acc = 0f
            val phase = i % 3
            var k = phase
            while (k < TAPS) {
                acc += H[k] * work[base + (i - k) / 3]
                k += 3
            }
            output[i] = 3f * acc
        }
        work.copyInto(history, 0, base + AEC_FRAME_SAMPLES - (TAPS - 1), base + AEC_FRAME_SAMPLES)
    }

    private companion object {
        /** Device rate, and the frame length that goes with it. */
        const val FRAME = 480

        /** Taps of the prototype low-pass; 63 at 48 kHz is a ~1 kHz transition. */
        const val TAPS = 63

        /** Cutoff of the prototype, well under the 8 kHz the 16 kHz rate can carry. */
        const val CUTOFF_HZ = 7_000.0

        /**
         * Hann-windowed sinc, normalised to unit DC gain. The interpolating side
         * multiplies by 3, which is what zero-stuffing takes out.
         */
        val H: FloatArray = run {
            val m0 = (TAPS - 1) / 2.0
            val x = 2.0 * CUTOFF_HZ / SAMPLE_RATE
            val h = FloatArray(TAPS) { k ->
                val m = k - m0
                val sinc = if (m == 0.0) 1.0 else sin(PI * x * m) / (PI * x * m)
                (sinc * (0.5 - 0.5 * cos(2.0 * PI * k / (TAPS - 1)))).toFloat()
            }
            val sum = h.sum()
            FloatArray(TAPS) { h[it] / sum }
        }
    }
}
