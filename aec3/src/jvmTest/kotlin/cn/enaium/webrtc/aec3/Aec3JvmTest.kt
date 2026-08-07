package cn.enaium.webrtc.aec3

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFileFormat

class Aec3Test {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val FRAME_SAMPLES = 160

        private val FAR_PATH = File("webrtc-aec3/tests/far.wav")
        private val NEAR_PATH = File("webrtc-aec3/tests/near.wav")
        private val MIX_PATH = File("webrtc-aec3/tests/mix.wav")
        private val OUTPUT_PATH = File("build/cancelled.wav")
    }

    private fun readWav(path: File): ShortArray {
        val ais = AudioSystem.getAudioInputStream(path)
        val fmt = ais.format
        assertEquals(SAMPLE_RATE.toFloat(), fmt.sampleRate, "Sample rate mismatch")
        assertEquals(CHANNELS, fmt.channels, "Channels mismatch")
        assertEquals(16, fmt.sampleSizeInBits, "Not 16-bit PCM")

        val bytes = ais.readAllBytes()
        val samples = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        return samples
    }

    private fun writeWav(path: File, samples: ShortArray) {
        path.parentFile.mkdirs()
        val fmt = AudioFormat(SAMPLE_RATE.toFloat(), 16, CHANNELS, true, false)
        val bytes = ByteArray(samples.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        val ais = AudioInputStream(ByteArrayInputStream(bytes), fmt, samples.size.toLong())
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, path)
    }

    private fun alignToFrames(samples: Int): Int = (samples / FRAME_SAMPLES) * FRAME_SAMPLES

    private fun computeRmsDb(data: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) {
            val s = data[i].toDouble()
            sum += s * s
        }
        val rms = Math.sqrt(sum / n).coerceAtLeast(1e-10)
        return 20.0 * Math.log10(rms / 32768.0)
    }

    private fun computeErle(mix: ShortArray, output: ShortArray, n: Int): Double {
        var mixPower = 0.0
        var outPower = 0.0
        for (i in 0 until n) {
            val m = mix[i].toDouble()
            val o = output[i].toDouble()
            mixPower += m * m
            outPower += o * o
        }
        mixPower = mixPower.coerceAtLeast(1e-10)
        outPower = outPower.coerceAtLeast(1e-10)
        return 10.0 * Math.log10(mixPower / outPower)
    }

    private fun copyRange(src: ShortArray, offset: Int, length: Int): ShortArray {
        return src.copyOfRange(offset, offset + length)
    }

    @Test
    fun testEchoCancellation() {
        val farInput = readWav(FAR_PATH)
        val nearInput = readWav(NEAR_PATH)
        val mixInput = readWav(MIX_PATH)

        val totalSamples = alignToFrames(
            minOf(farInput.size, nearInput.size, mixInput.size)
        )

        println("=== AEC3 Echo Cancellation Test ===")
        println("  Far  : ${FAR_PATH.name}")
        println("  Near : ${NEAR_PATH.name}")
        println("  Mix  : ${MIX_PATH.name}")
        println("  Out  : ${OUTPUT_PATH.name}")
        println("  Sample rate : ${SAMPLE_RATE} Hz")
        println("  Channels    : ${CHANNELS}")
        println("  Total samples : ${totalSamples} (${totalSamples * 1000 / SAMPLE_RATE} ms)")
        println()

        val farRms = computeRmsDb(farInput, totalSamples)
        val nearRms = computeRmsDb(nearInput, totalSamples)
        val mixRms = computeRmsDb(mixInput, totalSamples)

        println("  Signal levels (RMS dB):")
        println("    Far  : %.1f dB".format(farRms))
        println("    Near : %.1f dB".format(nearRms))
        println("    Mix  : %.1f dB".format(mixRms))
        println()

        // Create AEC3 objects using the common API
        val config = createAec3Config().apply {
            setDelayDefaultDelay(25)
            setFilterInitialStateSeconds(0.5f)
            setFilterConservativeInitialPhase(false)
        }
        val env = createAec3Environment()
        val factory = createAec3FactoryWithConfig(config)
        val echoControl = createAec3EchoControl(factory, env, SAMPLE_RATE, CHANNELS, CHANNELS)

        // Create audio buffers
        val renderBuffer = createAec3AudioBuffer(SAMPLE_RATE, CHANNELS)
        val captureBuffer = createAec3AudioBuffer(SAMPLE_RATE, CHANNELS)

        val output = ShortArray(totalSamples)
        val numFrames = totalSamples / FRAME_SAMPLES

        println("  Processing ${numFrames} frames (10 ms each)...")

        for (f in 0 until numFrames) {
            val offset = f * FRAME_SAMPLES

            // Convert short array to float array for render buffer
            val renderData = FloatArray(FRAME_SAMPLES) { i ->
                farInput[offset + i].toFloat()
            }
            renderBuffer.writeChannel(0, renderData)
            echoControl.analyzeRender(renderBuffer)

            // Convert short array to float array for capture buffer
            val captureData = FloatArray(FRAME_SAMPLES) { i ->
                mixInput[offset + i].toFloat()
            }
            captureBuffer.writeChannel(0, captureData)
            echoControl.analyzeCapture(captureBuffer)
            echoControl.processCapture(captureBuffer, false)

            // Read processed data back
            val processedData = captureBuffer.readChannel(0)
            for (i in 0 until FRAME_SAMPLES) {
                var s = processedData[i]
                if (s > 32767.0f) s = 32767.0f
                if (s < -32768.0f) s = -32768.0f
                output[offset + i] = s.toInt().toShort()
            }
        }

        // Cleanup
        renderBuffer.close()
        captureBuffer.close()
        echoControl.close()
        factory.close()
        env.close()
        config.close()

        writeWav(OUTPUT_PATH, output)
        println("  Output written to: ${OUTPUT_PATH.absolutePath}")
        println()

        val outputRms = computeRmsDb(output, totalSamples)
        val erle = computeErle(mixInput, output, totalSamples)

        println("  === Results ===")
        println("  Output RMS (dB)    : %.1f dB".format(outputRms))
        println("  ERLE (dB)          : %.1f dB".format(erle))

        val half = totalSamples / 2
        val erleFirst = computeErle(mixInput, output, half)
        val erleSecond = computeErle(
            copyRange(mixInput, half, totalSamples - half),
            copyRange(output, half, totalSamples - half),
            totalSamples - half
        )
        println("  ERLE (first half)  : %.1f dB".format(erleFirst))
        println("  ERLE (second half) : %.1f dB".format(erleSecond))
        println()

        assertTrue(erle > 0, "ERLE should be positive (echo was cancelled)")
        assertTrue(erleSecond > erleFirst * 0.5,
            "Second half ERLE should converge (adaptation improves over time)")
        assertTrue(outputRms < mixRms, "Output should be quieter than mix input")

        println("  Echo cancellation test completed.")
    }
}