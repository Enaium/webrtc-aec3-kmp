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
import cn.enaium.webrtc.aec3.Aec3AudioBuffer
import cn.enaium.webrtc.aec3.Aec3Config
import cn.enaium.webrtc.aec3.Aec3EchoControl
import cn.enaium.webrtc.aec3.Aec3Environment
import cn.enaium.webrtc.aec3.Aec3Factory
import cn.enaium.webrtc.aec3.createAec3AudioBuffer
import cn.enaium.webrtc.aec3.createAec3Config
import cn.enaium.webrtc.aec3.createAec3EchoControl
import cn.enaium.webrtc.aec3.createAec3Environment
import cn.enaium.webrtc.aec3.createAec3FactoryWithConfig
import kotlin.concurrent.Volatile

/**
 * Frames the capture device buffers, in AEC3 frames: 10 x 10 ms.
 *
 * Deep enough to ride out a scheduling hiccup - the audio thread is a plain one
 * and the render loop can hold the CPU for over a hundred milliseconds - because
 * an input queue that overflows drops microphone samples, and the canceller then
 * hands chopped audio to the speaker.
 */
private const val BUFFER_FRAMES = 3

/**
 * Frames the playback device buffers, in AEC3 frames: 10 x 10 ms.
 *
 * Deeper than the capture on purpose: the loop hands over whatever the capture
 * collected in one go, and the playback device has to absorb that burst
 * without dropping it.
 */
private const val PLAYBACK_BUFFER_FRAMES = 10

/** Milliseconds between two attempts to open a device. */
private const val RETRY_DELAY_MS = 250

/**
 * AEC3 frames of silence written into the playback queue before the loop takes
 * over: the queue has [PLAYBACK_BUFFER_FRAMES] of room, and leaving a couple of
 * frames unused keeps the priming writes from blocking.
 */
private const val PLAYBACK_PRIME_FRAMES = PLAYBACK_BUFFER_FRAMES - 2

/** AEC3 frames between two metric reads. */
private const val METRICS_FRAMES = 10

/** AEC3 frames between two console status lines: one second of audio. */
private const val REPORT_FRAMES = 100

/**
 * WebRTC's `AudioBuffer` carries samples in the int16 range, not in `-1..1`
 * (`audio-io-kmp` and the plots use `-1..1`), so the frames are scaled on the
 * way in and out. Feeding `-1..1` floats instead makes AEC3 treat the render
 * signal as silence: its level gate keeps the adaptive filter from converging
 * and every metric stays at its initial value.
 */
private const val AEC_SCALE = 32_768f

/**
 * The audio half of the example, on a thread of its own ([start]):
 *
 *  - [ReferenceGenerator] produces the far-end (render) signal, which is fed to
 *    [Aec3EchoControl.analyzeRender] and played through the speaker - the
 *    speaker is what turns it into the acoustic echo AEC3 has to remove.
 *  - The default input device is captured at AEC3's fixed 48 kHz mono format,
 *    every 10 ms frame is fed to [Aec3EchoControl.analyzeCapture] +
 *    [Aec3EchoControl.processCapture], and the echo-cancelled result is played
 *    back through the default output device, so the cancellation is audible and
 *    not just visible.
 *  - All three signals are published into [referenceScope] / [inputScope] /
 *    [outputScope] for the plots.
 *
 * The loop blocks in `AudioInput.read`, so the devices - not the render loop -
 * decide the pace. That keeps the window responsive when a device stalls, and
 * it keeps the render -> capture delay steady, which is what AEC3's delay
 * estimator needs: on a bursty render-loop-driven pipeline it never locks on.
 *
 * The render loop reads scopes, metrics and the control properties, and writes
 * the controls; everything crossing that boundary is either volatile or
 * published through the atomics inside [WaveformScope].
 */
class Aec3Pipeline(
    /**
     * Samples covered by one waveform window; the example sizes it for the
     * longest window its slider allows and lets the UI show a shorter part.
     */
    windowSamples: Int,
) : WaveformPipeline {

    /** Far-end (render) samples, as played and as fed to AEC3. */
    override val referenceScope = WaveformScope(windowSamples)

    /** Capture (microphone) samples, echo included. */
    override val inputScope = WaveformScope(windowSamples)

    /** Echo-cancelled samples, aligned with [inputScope]. */
    override val outputScope = WaveformScope(windowSamples)

    /** Format requested from the devices. */
    override val format: AudioFormat = AudioFormat.SPEECH

    /** Samples per AEC3 frame (480 = 10 ms at 48 kHz). */
    override val frameSize: Int = FRAME_SAMPLES

    /** Backend serving the devices, e.g. `"Core Audio"`. */
    override val systemName: String

    /** Whether the capture goes through AEC3; off plays the raw microphone back. */
    @Volatile
    override var aecEnabled: Boolean = true

    /** Whether the echo-cancelled capture is mixed into the playback. */
    @Volatile
    override var playbackEnabled: Boolean = true

    /**
     * Far-end source played and cancelled. White noise by default: a broadband
     * signal is what lets the adaptive filter and the delay estimator converge
     * (the tone only excites one bin), so the cancellation is visible right
     * away.
     *
     * The microphone is the default: the loop through the real capture is what
     * the example is for, and the synthesised sources are one combo entry away
     * when a known-good far end is wanted (they converge faster).
     */
    @Volatile
    override var farEndSource: FarEndSource = FarEndSource.MICROPHONE

    /**
     * Level of the far-end source in dBFS: the level of the synthesised signal,
     * or the loop gain the capture is played back with in
     * [FarEndSource.MICROPHONE] mode.
     */
    @Volatile
    override var farEndLevelDb: Float = DEFAULT_FAR_END_DB

    /** Level AEC3's frames are driven with, in dBFS (see [DEFAULT_AEC_INPUT_DB]). */
    @Volatile
    override var aecInputDb: Float = DEFAULT_AEC_INPUT_DB

    /** Delay reported to AEC3 as the render -> capture delay, in milliseconds. */
    @Volatile
    override var delayMs: Int = DEFAULT_DELAY_MS
        private set

    /**
     * How far behind the newest handed-over frame the far end of the microphone
     * mode is taken from, in 10 ms frames. The output queue holds about
     * [PLAYBACK_BUFFER_FRAMES] frames, so taking the far end from there is what
     * the speaker is playing now; the window's slider moves it around that.
     */
    @Volatile
    override var renderDelayFrames: Int = PLAYBACK_BUFFER_FRAMES

    /** Human readable name of the capture endpoint, once one is open. */
    @Volatile
    override var captureDeviceName: String = "system default"
        private set

    /** Human readable name of the playback endpoint, once one is open. */
    @Volatile
    override var playbackDeviceName: String = "system default"
        private set

    /** Why no capture stream is open, or `null` while one is. */
    @Volatile
    override var captureError: String? = null
        private set

    /** Why no playback stream is open, or `null` while one is. */
    @Volatile
    override var playbackError: String? = null
        private set

    /** Latest AEC3 metrics, or `null` before the first frame was processed. */
    @Volatile
    override var metrics: WaveformMetrics? = null
        private set

    /** AEC3 frames processed since the pipeline was created. */
    @Volatile
    override var processedFrames: Long = 0L
        private set

    /** Playback frames the output device could not take in time. */
    @Volatile
    override var droppedPlaybackFrames: Long = 0L
        private set

    /**
     * Runs of silence longer than [GAP_SAMPLES] in the played signal: what an
     * underrun or a dropped frame sounds like.
     */
    @Volatile
    override var playbackGaps: Long = 0L
        private set

    /**
     * Runs of silence in the captured signal: a microphone stream that drops
     * samples leaves gaps that the canceller then hands to the speaker. A live
     * but quiet microphone also reads as silence between samples, so only runs
     * longer than a couple of frames count.
     */
    @Volatile
    var captureGaps: Long = 0L
        private set

    private val system: AudioSystem = audioSystem()

    private val config: Aec3Config

    private val environment: Aec3Environment

    private val factory: Aec3Factory

    private val echoControl: Aec3EchoControl

    @Volatile
    private var input: AudioInput? = null

    @Volatile
    private var output: AudioOutput? = null

    private val generator = ReferenceGenerator()

    /** Amplitude of [farEndLevelDb], derived on the audio thread when it changes. */
    private var farEndAmplitude = dbfsToAmplitude(DEFAULT_FAR_END_DB)

    private var aecInputAmplitude = dbfsToAmplitude(DEFAULT_AEC_INPUT_DB)

    private var appliedAecInputDb = DEFAULT_AEC_INPUT_DB

    // Scratch for one device read. It must fit whatever the device hands back
    // in a single call, otherwise the conversion would silently clip the rest.
    private val readBuffer: AudioBuffer

    private val readFloats: FloatArray

    /** Playback for one device read: [burstFloats] filled by the frames, written once. */
    private val burst: AudioBuffer

    private val burstFloats: FloatArray

    private var burstFrames = 0


    private val renderBuffer: Aec3AudioBuffer

    private val captureBuffer: Aec3AudioBuffer

    private val reference = FloatArray(FRAME_SAMPLES)

    /**
     * The frames handed to the output device, indexed by [renderHistoryIndex].
     * The far end in the microphone mode is the frame the speaker is playing
     * now - written [renderDelayFrames] frames ago - not the one handed over
     * just now: that one is still queued behind the whole output buffer, and a
     * reference that leads its own echo drives the delay estimator to its floor
     * (it stops at 8 ms) and leaves the echo in the processed capture.
     */
    private val renderHistory = Array(RENDER_HISTORY_FRAMES) { FloatArray(FRAME_SAMPLES) }
    private var renderHistoryIndex = 0

    private val microphone = FloatArray(FRAME_SAMPLES)

    /** Echo-cancelled frame in `-1..1`, aligned with [microphone]. */
    private val capture = FloatArray(FRAME_SAMPLES)

    /** The three resamplers: one state each for the render, the capture and the output. */
    private val renderDecimator = Resampler3()

    private val captureDecimator = Resampler3()

    private val captureInterpolator = Resampler3()

    /** Far end and capture at [AEC_SAMPLE_RATE], before the int16 scaling. */
    private val resampledRender = FloatArray(AEC_FRAME_SAMPLES)

    private val resampledCapture = FloatArray(AEC_FRAME_SAMPLES)

    /** Far-end frame in the int16 range AEC3 expects. */
    private val aecRender = FloatArray(AEC_FRAME_SAMPLES)

    /** Capture frame in the int16 range AEC3 expects. */
    private val aecCapture = FloatArray(AEC_FRAME_SAMPLES)

    private val playback = FloatArray(FRAME_SAMPLES)

    // Carry-over for the partial frame between two reads; always shorter than
    // one AEC3 frame.
    private val carry = FloatArray(FRAME_SAMPLES)

    private var carryLength = 0

    /** Level and delay the audio thread has already applied. It owns the canceller. */
    private var clipPlay = 0L

    private var clipAec = 0L

    private var appliedLevelDb = Float.NaN

    private var appliedDelayMs = Int.MIN_VALUE

    @Volatile
    private var closed = false

    private var worker: AudioThread? = null

    init {
        // The environment and the factory are kept alive for as long as the
        // echo control: it is created from them and reads their clock and task
        // queue on every frame.
        // Default configuration; the audio buffer delay is reported live through
        // setAudioBufferDelay. Measured with a simulated echo, the residual here
        // is around 43 dB below the echo, thirty times better than the same
        // test at 48 kHz.
        val config = createAec3Config()
        val environment = createAec3Environment()
        val factory = createAec3FactoryWithConfig(config)
        val control = createAec3EchoControl(factory, environment, AEC_SAMPLE_RATE, CHANNELS, CHANNELS)
        control.setAudioBufferDelay(DEFAULT_DELAY_MS)

        this.config = config
        this.environment = environment
        this.factory = factory
        echoControl = control

        systemName = system.name
        readBuffer = AudioBuffer(format, FRAME_SAMPLES * BUFFER_FRAMES)
        readFloats = FloatArray(FRAME_SAMPLES * BUFFER_FRAMES)
        burst = AudioBuffer(format, FRAME_SAMPLES * BUFFER_FRAMES)
        burstFloats = FloatArray(FRAME_SAMPLES * BUFFER_FRAMES)
        // Both are 10 ms buffers at the capture format, which is the frame size
        // AEC3 is fed (the AEC3 audio buffer sizes itself as rate / 100).
        renderBuffer = createAec3AudioBuffer(AEC_SAMPLE_RATE, CHANNELS)
        captureBuffer = createAec3AudioBuffer(AEC_SAMPLE_RATE, CHANNELS)
    }

    /** Starts the audio thread. */
    override fun start() {
        if (worker != null || closed) return
        worker = AudioThread("aec3-waveform-audio") { audioLoop() }.also { it.start() }
    }

    /** Sets the render -> capture delay AEC3 is told about, in milliseconds. */
    override fun setDelay(value: Int) {
        delayMs = value
    }

    /**
     * Runs until [close], paced by the capture device: the blocking read returns
     * as soon as the device has audio, and the frames it returns are processed
     * and played back in the same order.
     */
    private fun audioLoop() {
        while (!closed) {
            val capture = input ?: openCapture()
            if (capture == null) {
                SDL.delay(RETRY_DELAY_MS)
                continue
            }
            input = capture

            // Playback is optional: without an output device the example still
            // captures and cancels, it just cannot be heard.
            val playbackStream = output ?: openPlayback()?.also { output = it }

            val frames = capture.read(readBuffer)
            // A stopped or closed stream unblocks the wait with -1; that is how
            // close() gets this thread out of the read.
            if (frames < 0) return
            if (frames == 0) continue

            applyControls()
            process(frames, playbackStream)
        }
    }

    /** Applies the control changes the render loop made, on the audio thread. */
    private fun applyControls() {
        if (farEndLevelDb != appliedLevelDb) {
            appliedLevelDb = farEndLevelDb
            farEndAmplitude = dbfsToAmplitude(farEndLevelDb)
        }
        if (aecInputDb != appliedAecInputDb) {
            appliedAecInputDb = aecInputDb
            aecInputAmplitude = dbfsToAmplitude(aecInputDb)
        }
        if (delayMs != appliedDelayMs) {
            appliedDelayMs = delayMs
            echoControl.setAudioBufferDelay(delayMs)
        }
    }

    private fun openCapture(): AudioInput? = try {
        // bufferFrames: the device hands over a few AEC3 frames at a time, which
        // is enough slack for one scheduling hiccup and small enough to stay
        // live.
        val stream = system.openInput(format, bufferFrames = FRAME_SAMPLES * BUFFER_FRAMES)
        check(stream.format == format) {
            "AEC3 needs 48 kHz mono, but the device opened as ${stream.format}"
        }
        stream.start()
        captureDeviceName = stream.device?.name ?: system.defaultInputDevice()?.name ?: "system default"
        captureError = null
        report("capturing $captureDeviceName [$systemName] $format")
        stream
    } catch (e: AudioException) {
        if (captureError == null) {
            val available = system.inputDevices().joinToString { it.name }
            captureError = "${e.message} - inputs: [$available]"
            report("capture unavailable: $captureError; retrying")
        }
        null
    }

    private fun openPlayback(): AudioOutput? = try {
        // Same depth as the capture: the loop drains whatever the capture
        // buffered in one go and writes it back, so a shallower playback buffer
        // would only overflow on every burst.
        val stream = system.openOutput(format, bufferFrames = FRAME_SAMPLES * PLAYBACK_BUFFER_FRAMES)
        check(stream.format == format) {
            "AEC3 needs 48 kHz mono, but the device opened as ${stream.format}"
        }
        stream.start()

        // Prime the queue. The loop feeds the device just in time - one AEC3
        // frame per captured frame - which leaves the queue almost empty, and a
        // device that runs dry plays silence for the missing frames: that is the
        // stutter this hears as broken playback, and no counter on this side can
        // see it because the gap is inserted after the write. Filling the queue
        // up front gives jitter something to eat into; the equal in and out
        // rates keep the level where it started.
        val silence = AudioBuffer(format, FRAME_SAMPLES)
        silence.putFloats(FloatArray(FRAME_SAMPLES), FRAME_SAMPLES)
        repeat(PLAYBACK_PRIME_FRAMES) { stream.write(silence) }

        playbackDeviceName = stream.device?.name ?: system.defaultOutputDevice()?.name ?: "system default"
        playbackError = null
        report("playing to $playbackDeviceName [$systemName] $format")
        stream
    } catch (e: AudioException) {
        if (playbackError == null) {
            val available = system.outputDevices().joinToString { it.name }
            playbackError = "${e.message} - outputs: [$available]"
            report("playback unavailable: $playbackError; retrying")
        }
        null
    }

    /** Cancels every complete frame in the first [frames] captured frames. */
    private fun process(frames: Int, playbackStream: AudioOutput?): Int {
        val count = readBuffer.toFloats(readFloats, frames)
        var offset = 0
        var processed = 0

        // Complete the partial frame left over from the previous read first.
        if (carryLength > 0) {
            val needed = FRAME_SAMPLES - carryLength
            if (count < needed) {
                readFloats.copyInto(carry, carryLength, 0, count)
                carryLength += count
                return 0
            }
            readFloats.copyInto(carry, carryLength, 0, needed)
            processFrame(carry, playbackStream)
            processed += FRAME_SAMPLES
            offset = needed
            carryLength = 0
        }

        while (offset + FRAME_SAMPLES <= count) {
            readFloats.copyInto(microphone, 0, offset, offset + FRAME_SAMPLES)
            processFrame(microphone, playbackStream)
            processed += FRAME_SAMPLES
            offset += FRAME_SAMPLES
        }

        val remaining = count - offset
        if (remaining > 0) {
            readFloats.copyInto(carry, 0, offset, count)
            carryLength = remaining
        }
        flushPlayback(playbackStream)
        return processed
    }

    /**
     * Hands every frame collected since the last read to the device in a single
     * write: one per read instead of one per 10 ms frame.
     */
    private fun flushPlayback(stream: AudioOutput?) {
        val frames = burstFrames
        burstFrames = 0
        if (stream == null || frames == 0) return
        burst.putFloats(burstFloats, frames * FRAME_SAMPLES)
        // Blocking: dropping the tail leaves a step in the signal, and a device
        // that stops consuming parks this thread instead of the window - close()
        // unblocks it by closing the stream.
        val written = stream.write(burst)
        if (written < frames) droppedPlaybackFrames += (frames - written).toLong()
    }

    /**
     * One 10 ms frame end to end: the far-end frame (synthesised, or what the
     * previous frame played back in the microphone mode) goes to AEC3, the echo
     * is cancelled from the microphone frame, all three signals are published
     * and the far end plus the echo-cancelled capture is played back.
     */
    private fun processFrame(microphone: FloatArray, playbackStream: AudioOutput?) {
        if (farEndSource.isGenerated) {
            generator.generate(reference, FRAME_SAMPLES, farEndSource, farEndAmplitude)
        } else {
            // Microphone mode: the far end is what the speaker is playing now,
            // i.e. the frame handed to the device renderDelayFrames frames ago
            // (0 is the frame handed over last frame).
            val delay = renderDelayFrames.coerceIn(0, RENDER_HISTORY_FRAMES - 1)
            val index = (renderHistoryIndex - 1 - delay + RENDER_HISTORY_FRAMES) % RENDER_HISTORY_FRAMES
            renderHistory[index].copyInto(reference, 0, 0, FRAME_SAMPLES)
        }

        if (aecEnabled) {
            // Scaled into the int16 range AEC3 works in, less the headroom:
            // the same factor goes back out, so the cancelled capture keeps the
            // microphone's own level whatever the slider says.
            // AEC3 runs at its own band rate, so both ends are decimated on the
            // way in and the cancelled capture is interpolated back for the
            // speaker. The resamplers keep their state across frames: restarting
            // them per frame would put a step at every boundary, the very buzz
            // this arrangement avoids.
            val scale = AEC_SCALE * aecInputAmplitude
            renderDecimator.downsample(reference, resampledRender)
            captureDecimator.downsample(microphone, resampledCapture)
            for (i in 0 until AEC_FRAME_SAMPLES) aecRender[i] = resampledRender[i] * scale
            for (i in 0 until AEC_FRAME_SAMPLES) aecCapture[i] = resampledCapture[i] * scale
            renderBuffer.writeChannel(0, aecRender)
            captureBuffer.writeChannel(0, aecCapture)
            echoControl.analyzeRender(renderBuffer)
            echoControl.analyzeCapture(captureBuffer)
            echoControl.processCapture(captureBuffer, false)
            val processed = captureBuffer.readChannel(0)
            for (i in 0 until AEC_FRAME_SAMPLES) resampledCapture[i] = processed[i] / scale
                captureInterpolator.upsample(resampledCapture, capture)
        } else {
            // Bypassed: what the microphone heard is what gets played back.
            microphone.copyInto(capture, 0, 0, FRAME_SAMPLES)
        }

        referenceScope.append(reference, FRAME_SAMPLES)
        inputScope.append(microphone, FRAME_SAMPLES)
        outputScope.append(capture, FRAME_SAMPLES)

        if (playbackStream != null) {
            // The synthesised far end is at its own level already; the looped
            // capture is scaled by the level slider, which in the microphone
            // mode is the loop gain. The loop stays closed whatever the AEC
            // switch says: bypassing the canceller has to leave the capture
            // audible, otherwise the switch would just mute the example.
            val gain = if (farEndSource.isGenerated) 1f else farEndAmplitude
            for (i in 0 until FRAME_SAMPLES) {
                playback[i] = (if (farEndSource.isGenerated) reference[i] else 0f) +
                    (if (playbackEnabled) capture[i] * gain else 0f)
            }
            // The frame goes into the burst buffer rather than straight to the
            // device: a whole read's worth of audio is handed over in one write,
            // which is far fewer chances for the output queue to run dry between
            // two writes. A device that runs dry fills the gap with silence,
            // which is what stuttering playback sounds like, and no counter on
            // this side sees it because the silence is inserted after the write.
            var i = 0
            while (i < FRAME_SAMPLES) { if (playback[i] > 1f || playback[i] < -1f) clipPlay++; if (capture[i] > 1f || capture[i] < -1f) clipAec++; i++ }
            playback.copyInto(burstFloats, burstFrames * FRAME_SAMPLES, 0, FRAME_SAMPLES)
            burstFrames++
            playback.copyInto(renderHistory[renderHistoryIndex], 0, 0, FRAME_SAMPLES)
            renderHistoryIndex = (renderHistoryIndex + 1) % RENDER_HISTORY_FRAMES
            playbackGaps += countGaps(playback)
        }
        captureGaps += countGaps(microphone)

        processedFrames++
        if (processedFrames % METRICS_FRAMES == 0L) {
            val latest = echoControl.getMetrics()
            metrics = WaveformMetrics(
                echoReturnLoss = latest.echoReturnLoss,
                echoReturnLossEnhancement = latest.echoReturnLossEnhancement,
                delayMs = latest.delayMs,
            )
            // A line per second of audio keeps a headless run readable and
            // shows whether the filter is converging.
            if (processedFrames % REPORT_FRAMES == 0L) {
                report(
                    "${processedFrames} frames: ERL ${fixed(latest.echoReturnLoss, 1)} dB, " +
                        "ERLE ${fixed(latest.echoReturnLossEnhancement, 1)} dB, " +
                        "delay ${latest.delayMs} ms, rd $renderDelayFrames, drops $droppedPlaybackFrames, gaps $playbackGaps, " +
                        "input $captureGaps, clipPlay $clipPlay, clipAec $clipAec",
                )
                clipPlay = 0
                clipAec = 0
            }
        }
    }

    /** Stops the audio thread and releases the devices and AEC3. */
    override fun close() {
        if (closed) return
        closed = true
        // Closing the streams unblocks a thread parked in read(); join() then
        // guarantees it is out before anything it uses is destroyed.
        input?.close()
        output?.close()
        worker?.join()
        worker = null
        input = null
        output = null
        renderBuffer.close()
        captureBuffer.close()
        echoControl.close()
        factory.close()
        environment.close()
        config.close()
        system.close()
    }

    private companion object {
        const val CHANNELS = 1

        /** Far-end level the slider starts at. */
        const val DEFAULT_FAR_END_DB = -6f

        /**
         * Headroom AEC3's frames are driven with, in dBFS. Its band processing
         * runs on the int16 range, so a capture that is anywhere near full
         * scale - a phone microphone held close is -3 dBFS and up - runs its
         * spectral stages hot and leaves wideband debris on speech, while
         * narrowband low-frequency content stays clean. -12 dBFS leaves a
         * factor of four.
         */
        const val DEFAULT_AEC_INPUT_DB = -12f

        /**
         * Render -> capture delay AEC3 is told about when the pipeline opens, in
         * milliseconds. It is the delay the *audio buffers* add - the playback
         * queue plus the capture queue - not the acoustic one: telling AEC3 a
         * number far below the real one misaligns its delay search, which shows
         * up as artefacts in the cancelled output. The slider tunes it live.
         */
        const val DEFAULT_DELAY_MS = 25

        /**
         * Silence longer than this reads as a gap rather than as a quiet moment.
         * 50 ms: anything shorter is just a quiet signal, which quantises to a
         * few consecutive zero samples and would otherwise count as a stall.
         */
        const val GAP_SAMPLES = SAMPLE_RATE / 20

        /**
         * Counts the runs of silence in a frame: an output device that misses a
         * buffer plays exactly zeros, so a run of them is the signature of the
         * glitches that are audible as crackle.
         */
        fun countGaps(values: FloatArray): Long {
            var gaps = 0L
            var run = 0
            for (value in values) {
                if (value == 0f) {
                    run++
                } else {
                    if (run >= GAP_SAMPLES) gaps++
                    run = 0
                }
            }
            if (run >= GAP_SAMPLES) gaps++
            return gaps
        }
    }
}
