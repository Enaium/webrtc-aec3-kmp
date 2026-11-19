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

import cn.enaium.audio.AudioBuffer
import cn.enaium.audio.AudioException
import cn.enaium.audio.AudioFormat
import cn.enaium.audio.AudioInput
import cn.enaium.audio.AudioOutput
import cn.enaium.audio.AudioSystem
import cn.enaium.audio.audioSystem
import cn.enaium.sdl.SDL

/** Milliseconds of audio one generated frame covers. */
private const val WASM_FRAME_MILLIS = 10

/** Frames a single pump generates at most, so a stalled window cannot burst. */
private const val MAX_GENERATED_FRAMES = 25

/** Capture frames the device buffer holds, giving one slow UI frame some slack. */
private const val CAPTURE_BUFFER_FRAMES = 4

/** Frames the playback device is asked to buffer. */
private const val PLAYBACK_BUFFER_FRAMES = 4

/** Level of the far-end source the browser build starts with, in dBFS. */
private const val DEFAULT_FAR_END_DB = -6f

/**
 * Headroom AEC3's frames are driven with, in dBFS. The browser build has no
 * canceller to apply it to; the control stays in the UI for parity.
 */
private const val DEFAULT_AEC_INPUT_DB = -12f

/** Render -> capture delay the UI starts at, in milliseconds. */
private const val DEFAULT_DELAY_MS = 25

/** Render delay the loopback mode starts at, in 10 ms frames. */
private const val DEFAULT_RENDER_DELAY_FRAMES = 10

/**
 * The waveform pipeline as a browser can run it.
 *
 * AEC3 is a C++ library linked through a native cinterop klib, which the
 * browser build does not link, so this pipeline plays the capture through
 * instead: the microphone is copied to the output without a canceller and the
 * plots show exactly that, while the synthesised far-end sources are still
 * generated and played. The scopes and the transport work as on the native
 * targets, and audio-io-kmp's WebAudio backend does the capturing and playing.
 *
 * Web Audio cannot block, so nothing runs on a thread here: the page's frame
 * loop calls [pump], which polls the capture ring and paces the far end off
 * `SDL_GetTicks` instead of a device read.
 *
 * The default far end is [FarEndSource.NOISE] rather than the microphone: with
 * no canceller between them, looping the capture back through the speaker would
 * feed the microphone into itself.
 */
class WasmWaveformPipeline(
    windowSamples: Int,
) : WaveformPipeline {

    override val referenceScope = WaveformScope(windowSamples)

    override val inputScope = WaveformScope(windowSamples)

    override val outputScope = WaveformScope(windowSamples)

    override val format: AudioFormat = AudioFormat.SPEECH

    override val frameSize: Int = FRAME_SAMPLES

    private val system: AudioSystem = audioSystem()

    override val systemName: String = system.name

    /** The switch is kept for the UI; there is no canceller to bypass here. */
    override var aecEnabled: Boolean = true

    override var playbackEnabled: Boolean = true

    override var farEndSource: FarEndSource = FarEndSource.NOISE
        set(value) {
            if (field == value) return
            field = value
            nextGeneratedFrame = 0uL
        }

    override var farEndLevelDb: Float = DEFAULT_FAR_END_DB

    override var aecInputDb: Float = DEFAULT_AEC_INPUT_DB

    override var delayMs: Int = DEFAULT_DELAY_MS
        private set

    override var renderDelayFrames: Int = DEFAULT_RENDER_DELAY_FRAMES

    override var captureDeviceName: String = "system default"
        private set

    override var playbackDeviceName: String = "system default"
        private set

    override var captureError: String? = null
        private set

    override var playbackError: String? = null
        private set

    /**
     * AEC3 never runs here, so there are no metrics to report: the UI says it
     * is waiting for the first frames rather than making numbers up.
     */
    override val metrics: WaveformMetrics? get() = null

    override var processedFrames: Long = 0L
        private set

    override var droppedPlaybackFrames: Long = 0L
        private set

    /** The Web Audio ring reports what it accepted, not the silence it inserts. */
    override val playbackGaps: Long get() = 0L

    private val generator = ReferenceGenerator()

    private val reference = FloatArray(FRAME_SAMPLES)

    private val microphone = FloatArray(FRAME_SAMPLES)

    private val playback = FloatArray(FRAME_SAMPLES)

    private val captured = FloatArray(FRAME_SAMPLES * CAPTURE_BUFFER_FRAMES)

    private val captureBuffer = AudioBuffer(format, FRAME_SAMPLES * CAPTURE_BUFFER_FRAMES)

    private val playbackBuffer = AudioBuffer(format, FRAME_SAMPLES)

    /** Captured frames waiting for a 10 ms slot; single threaded, so a plain queue. */
    private val captureQueue = FloatArray(FRAME_SAMPLES * CAPTURE_BUFFER_FRAMES)

    private var captureQueued = 0

    /** The frames this pipeline played, for the far end of the loopback mode. */
    private val renderHistory = Array(RENDER_HISTORY_FRAMES) { FloatArray(FRAME_SAMPLES) }

    private var renderHistoryIndex = 0

    private var input: AudioInput? = null

    private var output: AudioOutput? = null

    private var recording = false

    private var closed = false

    private var nextGeneratedFrame = 0uL

    /** Starts capturing: what the native pipeline does when its audio thread starts. */
    override fun start() {
        if (closed || recording) return
        recording = true
        report("recording $farEndSource via $systemName")
    }

    override fun setDelay(value: Int) {
        delayMs = value
    }

    /**
     * Moves audio: called once per rendered frame. Polls the capture ring,
     * generates whatever far-end frames are due and plays the mix.
     */
    fun pump() {
        if (closed || !recording) return
        val stream = input ?: openCaptureStream()?.also { input = it }
        if (stream != null) drainCapture(stream)
        generate()
    }

    override fun close() {
        if (closed) return
        closed = true
        recording = false
        input?.close()
        input = null
        output?.close()
        output = null
        system.close()
    }

    /** Processes one 10 ms frame for every slot the wall clock has passed. */
    private fun generate() {
        val now = SDL.getTicks()
        if (nextGeneratedFrame == 0uL) nextGeneratedFrame = now

        var budget = MAX_GENERATED_FRAMES
        while (budget-- > 0 && now >= nextGeneratedFrame) {
            if (captureQueued >= FRAME_SAMPLES) {
                captureQueue.copyInto(microphone, 0, 0, FRAME_SAMPLES)
                captureQueue.copyInto(captureQueue, 0, FRAME_SAMPLES, captureQueued)
                captureQueued -= FRAME_SAMPLES
            } else {
                // No capture yet (permission prompt, a silent moment): the
                // frame is silence, but the far end and the clock keep going.
                microphone.fill(0f)
            }
            processFrame()
            nextGeneratedFrame += WASM_FRAME_MILLIS.toULong()
        }
        // A window that was not drawing for a while must not turn its backlog
        // into a burst of audio.
        if (nextGeneratedFrame + WASM_FRAME_MILLIS.toULong() < now) nextGeneratedFrame = now
    }

    private fun drainCapture(stream: AudioInput) {
        while (true) {
            val frames = stream.readNonBlocking(captureBuffer)
            if (frames <= 0) break
            val samples = captureBuffer.toFloats(captured, frames)
            var offset = 0
            while (offset + FRAME_SAMPLES <= samples) {
                if (captureQueued + FRAME_SAMPLES > captureQueue.size) break
                captured.copyInto(captureQueue, captureQueued, offset, offset + FRAME_SAMPLES)
                captureQueued += FRAME_SAMPLES
                offset += FRAME_SAMPLES
            }
        }
    }

    private fun processFrame() {
        if (farEndSource.isGenerated) {
            generator.generate(reference, FRAME_SAMPLES, farEndSource, dbfsToAmplitude(farEndLevelDb))
        } else {
            val delay = renderDelayFrames.coerceIn(0, RENDER_HISTORY_FRAMES - 1)
            val index = (renderHistoryIndex - 1 - delay + RENDER_HISTORY_FRAMES) % RENDER_HISTORY_FRAMES
            renderHistory[index].copyInto(reference, 0, 0, FRAME_SAMPLES)
        }

        referenceScope.append(reference, FRAME_SAMPLES)
        inputScope.append(microphone, FRAME_SAMPLES)
        // No canceller: what the microphone captured is what the output plot
        // shows, whichever way the AEC switch is set.
        outputScope.append(microphone, FRAME_SAMPLES)

        val stream = output ?: openPlaybackStream()?.also { output = it }
        if (stream != null) {
            // The same mix Aec3Pipeline plays, minus the cancelled capture.
            val gain = if (farEndSource.isGenerated) 1f else dbfsToAmplitude(farEndLevelDb)
            for (i in 0 until FRAME_SAMPLES) {
                playback[i] = (if (farEndSource.isGenerated) reference[i] else 0f) +
                    (if (playbackEnabled) microphone[i] * gain else 0f)
            }
            playbackBuffer.putFloats(playback, FRAME_SAMPLES)
            if (stream.writeNonBlocking(playbackBuffer) < 0) droppedPlaybackFrames++
            playback.copyInto(renderHistory[renderHistoryIndex], 0, 0, FRAME_SAMPLES)
            renderHistoryIndex = (renderHistoryIndex + 1) % RENDER_HISTORY_FRAMES
        }

        processedFrames++
    }

    private fun openCaptureStream(): AudioInput? = try {
        val stream = system.openInput(format, bufferFrames = FRAME_SAMPLES * CAPTURE_BUFFER_FRAMES)
        if (stream.format != format) {
            stream.close()
            captureError = "device opened as ${stream.format}, expected $format"
            null
        } else {
            stream.start()
            captureDeviceName = stream.device?.name ?: "system default"
            captureError = null
            report("capturing $captureDeviceName [$systemName] $format")
            stream
        }
    } catch (e: AudioException) {
        captureError = e.message ?: "capture unavailable"
        println("capture unavailable: $captureError")
        null
    }

    private fun openPlaybackStream(): AudioOutput? = try {
        val stream = system.openOutput(format, bufferFrames = FRAME_SAMPLES * PLAYBACK_BUFFER_FRAMES)
        if (stream.format != format) {
            stream.close()
            playbackError = "device opened as ${stream.format}, expected $format"
            null
        } else {
            stream.start()
            playbackDeviceName = stream.device?.name ?: "system default"
            playbackError = null
            report("playing $playbackDeviceName [$systemName] $format")
            stream
        }
    } catch (e: AudioException) {
        playbackError = e.message ?: "playback unavailable"
        println("playback unavailable: $playbackError")
        null
    }
}
