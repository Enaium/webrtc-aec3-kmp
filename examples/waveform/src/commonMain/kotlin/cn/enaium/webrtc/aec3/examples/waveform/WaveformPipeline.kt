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

import cn.enaium.audio.AudioFormat

/**
 * What AEC3 reported for the last frames it processed.
 *
 * The window only needs these three numbers, so the interface below can live in
 * `commonMain`: `Aec3Metrics` comes from `:aec3`, which the browser build does
 * not link, and this is the shape both pipelines publish.
 */
data class WaveformMetrics(
    /** Echo return loss of the last processed frames, in dB. */
    val echoReturnLoss: Double,

    /** Echo return loss enhancement of the last processed frames, in dB. */
    val echoReturnLossEnhancement: Double,

    /** Render -> capture delay the canceller locked on, in milliseconds. */
    val delayMs: Int,
)

/**
 * What [WaveformWindow] draws and drives.
 *
 * The window is the example's whole UI and lives in `commonMain`, but AEC3 -
 * and with it [Aec3Pipeline] - only exists for the targets that can link the
 * native library. The interface is what lets a backend without AEC3 (the
 * wasm build) reuse the same UI: it supplies the scopes and the transport, and
 * the plots cannot tell the difference.
 */
interface WaveformPipeline : AutoCloseable {

    /** Far-end (render) samples, as played and as fed to AEC3. */
    val referenceScope: WaveformScope

    /** Capture (microphone) samples, echo included. */
    val inputScope: WaveformScope

    /** Echo-cancelled samples, aligned with [inputScope]. */
    val outputScope: WaveformScope

    /** Format requested from the devices. */
    val format: AudioFormat

    /** Samples per processed frame. */
    val frameSize: Int

    /** Backend serving the devices, e.g. `"Core Audio"`. */
    val systemName: String

    /** Whether the capture goes through AEC3; off plays the raw microphone back. */
    var aecEnabled: Boolean

    /** Whether the echo-cancelled capture is mixed into the playback. */
    var playbackEnabled: Boolean

    /** Far-end source played and cancelled. */
    var farEndSource: FarEndSource

    /** Level of the far-end source in dBFS. */
    var farEndLevelDb: Float

    /** Level AEC3's frames are driven with, in dBFS. */
    var aecInputDb: Float

    /** Render -> capture delay reported to AEC3, in milliseconds. */
    val delayMs: Int

    /** How far behind the newest handed-over frame the far end of the loopback mode is taken from. */
    var renderDelayFrames: Int

    /** Human readable name of the capture endpoint, once one is open. */
    val captureDeviceName: String

    /** Human readable name of the playback endpoint, once one is open. */
    val playbackDeviceName: String

    /** Why no capture stream is open, or `null` while one is. */
    val captureError: String?

    /** Why no playback stream is open, or `null` while one is. */
    val playbackError: String?

    /** Latest AEC3 metrics, or `null` before the first frame was processed. */
    val metrics: WaveformMetrics?

    /** Frames processed since the pipeline was created. */
    val processedFrames: Long

    /** Playback frames the output device could not take in time. */
    val droppedPlaybackFrames: Long

    /** Runs of silence in the played signal: what an underrun or a dropped frame sounds like. */
    val playbackGaps: Long

    /** Starts processing. */
    fun start()

    /** Sets the render -> capture delay AEC3 is told about, in milliseconds. */
    fun setDelay(value: Int)
}
