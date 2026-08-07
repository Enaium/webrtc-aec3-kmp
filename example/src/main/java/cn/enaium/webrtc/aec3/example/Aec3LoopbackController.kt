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

package cn.enaium.webrtc.aec3.example

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.enaium.webrtc.aec3.Aec3Config
import cn.enaium.webrtc.aec3.Aec3EchoControl
import cn.enaium.webrtc.aec3.Aec3Environment
import cn.enaium.webrtc.aec3.Aec3Factory
import cn.enaium.webrtc.aec3.createAec3AudioBuffer
import cn.enaium.webrtc.aec3.createAec3Config
import cn.enaium.webrtc.aec3.createAec3EchoControl
import cn.enaium.webrtc.aec3.createAec3Environment
import cn.enaium.webrtc.aec3.createAec3FactoryWithConfig
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Owns the real-time AEC3 loopback pipeline and exposes observable Compose
 * state that the UI collects:
 *
 *  - A far-end reference tone (440 Hz) is generated and played out of the
 *    speaker via [AudioTrack]. This is the signal that creates echo.
 *  - The microphone is captured via [AudioRecord] (it picks up the speaker
 *    echo plus any local speech).
 *  - AEC3 is inserted in between: the far-end frames feed
 *    [Aec3EchoControl.analyzeRender], the mic frames feed
 *    [Aec3EchoControl.analyzeCapture] + [Aec3EchoControl.processCapture], and
 *    the echo-cancelled result is played back through a second [AudioTrack].
 *
 *  [delayMs] maps to [Aec3EchoControl.setAudioBufferDelay] (0..200 ms).
 */
class Aec3LoopbackController {

    companion object {
        private const val TAG = "AEC3Example"
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val FRAME_SAMPLES = 160 // 10 ms @ 16 kHz
        private const val REFERENCE_FREQ = 440.0
        private const val REFERENCE_GAIN = 0.4f
    }

    // ---- Compose-observable state ----
    var isRunning by mutableStateOf(false)
        private set

    var aecEnabled by mutableStateOf(true)

    var delayMs by mutableIntStateOf(25)
        private set

    var status by mutableStateOf("Stopped")

    var error by mutableStateOf<String?>(null)
        private set

    // ---- AEC3 object graph ----
    private var config: Aec3Config? = null
    private var environment: Aec3Environment? = null
    private var factory: Aec3Factory? = null
    private var echoControl: Aec3EchoControl? = null

    // ---- Audio ----
    private var audioRecord: AudioRecord? = null
    private var farEndTrack: AudioTrack? = null
    private var outputTrack: AudioTrack? = null

    private var worker: Thread? = null

    fun toggle() {
        if (isRunning) stop() else start()
    }

    fun setDelay(value: Int) {
        delayMs = value
        // Apply live while running
        echoControl?.setAudioBufferDelay(value)
    }

    fun start() {
        try {
            initAec()
            initAudio()
            isRunning = true
            error = null
            status = "Running…"
            worker = thread(start = true) { processingLoop() }
        } catch (t: Throwable) {
            status = "Failed to start: ${t.javaClass.simpleName}: ${t.message}"
            stop()
        }
    }

    fun stop() {
        isRunning = false
        worker?.join(500)
        worker = null

        runCatching { audioRecord?.stop() }
        runCatching { farEndTrack?.stop() }
        runCatching { outputTrack?.stop() }

        audioRecord?.release()
        audioRecord = null
        farEndTrack?.release()
        farEndTrack = null
        outputTrack?.release()
        outputTrack = null

        echoControl?.close()
        factory?.close()
        environment?.close()
        config?.close()
        echoControl = null
        factory = null
        environment = null
        config = null

        if (status == "Running…") status = "Stopped"
    }

    // =========================================================================
    // AEC3
    // =========================================================================

    private fun initAec() {
        val cfg = createAec3Config().apply {
            setDelayDefaultDelay(delayMs)
            setFilterInitialStateSeconds(0.5f)
            setFilterConservativeInitialPhase(false)
        }
        config = cfg

        val env = createAec3Environment()
        environment = env

        val fac = createAec3FactoryWithConfig(cfg)
        factory = fac

        val ec = createAec3EchoControl(fac, env, SAMPLE_RATE, CHANNELS, CHANNELS)
        ec.setAudioBufferDelay(delayMs)
        echoControl = ec
    }

    // =========================================================================
    // Audio I/O
    // =========================================================================

    private fun initAudio() {
        val minRecordBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minRecordBuf, FRAME_SAMPLES * 2),
        )

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val minTrackBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val trackBufSize = maxOf(minTrackBuf, FRAME_SAMPLES * 8)

        farEndTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(trackBufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        outputTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(trackBufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    // =========================================================================
    // Processing loop
    // =========================================================================

    private fun processingLoop() {
        val ec = echoControl ?: return
        val record = audioRecord ?: return
        val farTrack = farEndTrack ?: return
        val outTrack = outputTrack ?: return

        val renderBuffer = createAec3AudioBuffer(SAMPLE_RATE, CHANNELS)
        val captureBuffer = createAec3AudioBuffer(SAMPLE_RATE, CHANNELS)

        val micFrame = ShortArray(FRAME_SAMPLES)
        val renderFloat = FloatArray(FRAME_SAMPLES)
        val captureFloat = FloatArray(FRAME_SAMPLES)
        val outShort = ShortArray(FRAME_SAMPLES)
        val renderShort = ShortArray(FRAME_SAMPLES)

        var phase = 0.0
        var frameCount = 0

        try {
            record.startRecording()
            farTrack.play()
            outTrack.play()

            while (isRunning) {
                val read = record.read(micFrame, 0, FRAME_SAMPLES)
                if (read <= 0) continue
                val n = minOf(read, FRAME_SAMPLES)

                // 1. Far-end reference: 440 Hz sine wave. It is always played
                //    out of the speaker (creating the echo); it is fed to AEC
                //    as the render signal only while AEC is enabled.
                for (i in 0 until n) {
                    val sample = sin(2.0 * PI * REFERENCE_FREQ * phase / SAMPLE_RATE)
                    phase += 1.0
                    if (phase >= SAMPLE_RATE) phase = 0.0
                    renderFloat[i] = (sample * REFERENCE_GAIN).toFloat()
                    renderShort[i] = (renderFloat[i] * 32767).toInt().toShort()
                }
                renderBuffer.writeChannel(0, renderFloat)
                if (aecEnabled) ec.analyzeRender(renderBuffer)
                farTrack.write(renderShort, 0, n)

                // 2. Capture: microphone frame.
                for (i in 0 until n) {
                    captureFloat[i] = micFrame[i] / 32768.0f
                }
                captureBuffer.writeChannel(0, captureFloat)

                // 3. AEC: cancel the far-end echo from the capture. When the
                //    switch is off, bypass AEC and play the raw mic signal so
                //    the echo (tone) remains audible.
                val processed: FloatArray
                if (aecEnabled) {
                    ec.analyzeRender(renderBuffer)
                    ec.analyzeCapture(captureBuffer)
                    ec.processCapture(captureBuffer, false)
                    processed = captureBuffer.readChannel(0)
                } else {
                    processed = captureFloat
                }

                // 4. Play back the echo-cancelled (or raw) result.
                for (i in 0 until n) {
                    var s = processed[i] * 32767
                    if (s > 32767f) s = 32767f
                    if (s < -32768f) s = -32768f
                    outShort[i] = s.toInt().toShort()
                }
                outTrack.write(outShort, 0, n)

                frameCount++
                if (frameCount % 50 == 0) {
                    val metrics = ec.getMetrics()
                    status = buildString {
                        appendLine("ERL  : %.1f dB".format(metrics.echoReturnLoss))
                        appendLine("ERLE : %.1f dB".format(metrics.echoReturnLossEnhancement))
                        appendLine("Delay: ${metrics.delayMs} ms (set $delayMs ms)")
                        appendLine("Frames: $frameCount")
                    }
                    Log.i(
                        TAG,
                        "AEC3 status: ERL=${"%.1f".format(metrics.echoReturnLoss)} " +
                            "ERLE=${"%.1f".format(metrics.echoReturnLossEnhancement)} " +
                            "delay=${metrics.delayMs} frames=$frameCount",
                    )
                }
            }
        } catch (t: Throwable) {
            error = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "AEC3 processing error", t)
        } finally {
            runCatching { renderBuffer.close() }
            runCatching { captureBuffer.close() }
        }
    }
}
