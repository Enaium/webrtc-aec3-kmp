@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.webrtc.aec3

import kotlinx.cinterop.*
import platform.posix.*
import kotlin.test.*

class Aec3NativeTest {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val FRAME_SAMPLES = 160

        private val FAR_PATH = "webrtc-aec3/tests/far.wav"
        private val NEAR_PATH = "webrtc-aec3/tests/near.wav"
        private val MIX_PATH = "webrtc-aec3/tests/mix.wav"
        private val OUTPUT_PATH = "build/cancelled-native.wav"
    }

    // -----------------------------------------------------------------------
    // POSIX file I/O
    // -----------------------------------------------------------------------

    private fun readFileBytes(path: String): ByteArray = memScoped {
        val file = fopen(path, "rb") ?: error("Cannot open file: $path")
        try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file)
            fseek(file, 0, SEEK_SET)
            val buffer = allocArray<ByteVar>(size)
            fread(buffer, 1.toULong(), size.toULong(), file)
            ByteArray(size.toInt()) { buffer[it] }
        } finally {
            fclose(file)
        }
    }

    private fun writeFileBytes(path: String, data: ByteArray) = memScoped {
        val file = fopen(path, "wb") ?: error("Cannot open file: $path")
        try {
            val buffer = allocArray<ByteVar>(data.size.toLong())
            for (i in data.indices) buffer[i] = data[i]
            fwrite(buffer, 1.toULong(), data.size.toULong(), file)
        } finally {
            fclose(file)
        }
    }

    // -----------------------------------------------------------------------
    // WAV format helpers (pure Kotlin, no Java dependencies)
    // -----------------------------------------------------------------------

    private data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val samples: ShortArray
    )

    private fun readWav(path: String): WavInfo {
        val bytes = readFileBytes(path)
        var off = 0

        // RIFF header
        assertTrue(bytes[off] == 'R'.code.toByte() && bytes[off + 1] == 'I'.code.toByte() &&
                bytes[off + 2] == 'F'.code.toByte() && bytes[off + 3] == 'F'.code.toByte(), "Not a RIFF file")
        off += 4
        // File size (skip)
        off += 4
        assertTrue(bytes[off] == 'W'.code.toByte() && bytes[off + 1] == 'A'.code.toByte() &&
                bytes[off + 2] == 'V'.code.toByte() && bytes[off + 3] == 'E'.code.toByte(), "Not a WAVE file")
        off += 4

        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var data: ShortArray = shortArrayOf()

        while (off + 8 <= bytes.size) {
            val chunkId = bytes.sliceArray(off until off + 4).decodeToString()
            off += 4
            val chunkSize = readLe32(bytes, off)
            off += 4

            when (chunkId) {
                "fmt " -> {
                    val audioFormat = readLe16(bytes, off)
                    assertEquals(1, audioFormat, "Only PCM supported")
                    channels = readLe16(bytes, off + 2)
                    sampleRate = readLe32(bytes, off + 4)
                    bitsPerSample = readLe16(bytes, off + 14)
                }
                "data" -> {
                    val totalSamples = chunkSize / (bitsPerSample / 8)
                    data = ShortArray(totalSamples)
                    for (i in 0 until totalSamples) {
                        when (bitsPerSample) {
                            16 -> data[i] = readLe16S(bytes, off + i * 2)
                            8 -> data[i] = (bytes[off + i].toInt() and 0xFF).toShort()
                            else -> error("Unsupported bitsPerSample: $bitsPerSample")
                        }
                    }
                }
            }
            off += chunkSize
        }

        assertTrue(sampleRate > 0, "No fmt chunk found")
        assertTrue(data.isNotEmpty(), "No data chunk found")

        return WavInfo(sampleRate, channels, bitsPerSample, data)
    }

    private fun writeWav(path: String, samples: ShortArray, sampleRate: Int, channels: Int) {
        val bitsPerSample = 16
        val bytesPerSample = bitsPerSample / 8
        val dataSize = samples.size * bytesPerSample
        val fileSize = 36 + dataSize

        val buf = ByteArray(44 + dataSize)
        var off = 0

        // RIFF header
        buf[off++] = 'R'.code.toByte(); buf[off++] = 'I'.code.toByte()
        buf[off++] = 'F'.code.toByte(); buf[off++] = 'F'.code.toByte()
        writeLe32(buf, off, fileSize); off += 4
        buf[off++] = 'W'.code.toByte(); buf[off++] = 'A'.code.toByte()
        buf[off++] = 'V'.code.toByte(); buf[off++] = 'E'.code.toByte()

        // fmt chunk
        buf[off++] = 'f'.code.toByte(); buf[off++] = 'm'.code.toByte()
        buf[off++] = 't'.code.toByte(); buf[off++] = ' '.code.toByte()
        writeLe32(buf, off, 16); off += 4
        writeLe16(buf, off, 1); off += 2          // PCM
        writeLe16(buf, off, channels); off += 2
        writeLe32(buf, off, sampleRate); off += 4
        writeLe32(buf, off, sampleRate * channels * bytesPerSample); off += 4
        writeLe16(buf, off, (channels * bytesPerSample)); off += 2
        writeLe16(buf, off, bitsPerSample); off += 2

        // data chunk
        buf[off++] = 'd'.code.toByte(); buf[off++] = 'a'.code.toByte()
        buf[off++] = 't'.code.toByte(); buf[off++] = 'a'.code.toByte()
        writeLe32(buf, off, dataSize); off += 4

        for (i in samples.indices) {
            writeLe16(buf, off, samples[i].toInt()); off += 2
        }

        writeFileBytes(path, buf)
    }

    private fun readLe16(bytes: ByteArray, off: Int): Int {
        return (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun readLe16S(bytes: ByteArray, off: Int): Short {
        val v = readLe16(bytes, off)
        return if (v >= 32768) (v - 65536).toShort() else v.toShort()
    }

    private fun readLe32(bytes: ByteArray, off: Int): Int {
        return (bytes[off].toInt() and 0xFF) or
                ((bytes[off + 1].toInt() and 0xFF) shl 8) or
                ((bytes[off + 2].toInt() and 0xFF) shl 16) or
                ((bytes[off + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeLe16(buf: ByteArray, off: Int, value: Int) {
        buf[off] = (value and 0xFF).toByte()
        buf[off + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeLe32(buf: ByteArray, off: Int, value: Int) {
        buf[off] = (value and 0xFF).toByte()
        buf[off + 1] = ((value shr 8) and 0xFF).toByte()
        buf[off + 2] = ((value shr 16) and 0xFF).toByte()
        buf[off + 3] = ((value shr 24) and 0xFF).toByte()
    }

    // -----------------------------------------------------------------------
    // Formatting helpers (Kotlin/Native doesn't have String.format)
    // -----------------------------------------------------------------------

    private fun fmt1(value: Double): String {
        val rounded = (value * 10).let { if (it >= 0) (it + 0.5).toInt() else (it - 0.5).toInt() }
        val intPart = rounded / 10
        val fracPart = kotlin.math.abs(rounded % 10)
        return "$intPart.$fracPart"
    }

    // -----------------------------------------------------------------------
    // Signal processing utilities
    // -----------------------------------------------------------------------

    private fun alignToFrames(samples: Int): Int = (samples / FRAME_SAMPLES) * FRAME_SAMPLES

    private fun computeRmsDb(data: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) {
            val s = data[i].toDouble()
            sum += s * s
        }
        val rms = kotlin.math.sqrt(sum / n).coerceAtLeast(1e-10)
        return 20.0 * kotlin.math.log10(rms / 32768.0)
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
        return 10.0 * kotlin.math.log10(mixPower / outPower)
    }

    private fun copyRange(src: ShortArray, offset: Int, length: Int): ShortArray {
        return src.copyOfRange(offset, offset + length)
    }

    // -----------------------------------------------------------------------
    // Test
    // -----------------------------------------------------------------------

    @Test
    fun testEchoCancellation() {
        val farWav = readWav(FAR_PATH)
        val nearWav = readWav(NEAR_PATH)
        val mixWav = readWav(MIX_PATH)

        assertEquals(SAMPLE_RATE, farWav.sampleRate, "Far sample rate mismatch")
        assertEquals(SAMPLE_RATE, nearWav.sampleRate, "Near sample rate mismatch")
        assertEquals(SAMPLE_RATE, mixWav.sampleRate, "Mix sample rate mismatch")

        val farInput = farWav.samples
        val nearInput = nearWav.samples
        val mixInput = mixWav.samples

        val totalSamples = alignToFrames(
            minOf(farInput.size, nearInput.size, mixInput.size)
        )

        println("=== AEC3 Echo Cancellation Test (Native) ===")
        println("  Far  : $FAR_PATH")
        println("  Near : $NEAR_PATH")
        println("  Mix  : $MIX_PATH")
        println("  Out  : $OUTPUT_PATH")
        println("  Sample rate : ${SAMPLE_RATE} Hz")
        println("  Channels    : ${CHANNELS}")
        println("  Total samples : ${totalSamples} (${totalSamples * 1000 / SAMPLE_RATE} ms)")
        println()

        val farRms = computeRmsDb(farInput, totalSamples)
        val nearRms = computeRmsDb(nearInput, totalSamples)
        val mixRms = computeRmsDb(mixInput, totalSamples)

        println("  Signal levels (RMS dB):")
        println("    Far  : ${fmt1(farRms)} dB")
        println("    Near : ${fmt1(nearRms)} dB")
        println("    Mix  : ${fmt1(mixRms)} dB")
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

        writeWav(OUTPUT_PATH, output, SAMPLE_RATE, CHANNELS)
        println("  Output written to: $OUTPUT_PATH")
        println()

        val outputRms = computeRmsDb(output, totalSamples)
        val erle = computeErle(mixInput, output, totalSamples)

        println("  === Results ===")
        println("  Output RMS (dB)    : ${fmt1(outputRms)} dB")
        println("  ERLE (dB)          : ${fmt1(erle)} dB")

        val half = totalSamples / 2
        val erleFirst = computeErle(mixInput, output, half)
        val erleSecond = computeErle(
            copyRange(mixInput, half, totalSamples - half),
            copyRange(output, half, totalSamples - half),
            totalSamples - half
        )
        println("  ERLE (first half)  : ${fmt1(erleFirst)} dB")
        println("  ERLE (second half) : ${fmt1(erleSecond)} dB")
        println()

        assertTrue(erle > 0, "ERLE should be positive (echo was cancelled)")
        assertTrue(erleSecond > erleFirst * 0.5,
            "Second half ERLE should converge (adaptation improves over time)")
        assertTrue(outputRms < mixRms, "Output should be quieter than mix input")

        println("  Echo cancellation test completed (Native).")
    }
}