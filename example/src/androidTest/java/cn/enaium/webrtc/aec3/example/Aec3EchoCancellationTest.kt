package cn.enaium.webrtc.aec3.example

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.enaium.webrtc.aec3.Aec3Metrics
import cn.enaium.webrtc.aec3.createAec3AudioBuffer
import cn.enaium.webrtc.aec3.createAec3Config
import cn.enaium.webrtc.aec3.createAec3EchoControl
import cn.enaium.webrtc.aec3.createAec3Environment
import cn.enaium.webrtc.aec3.createAec3FactoryWithConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Aec3EchoCancellationTest {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val FRAME_SAMPLES = 160
    }

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().context

    private fun readAsset(name: String): ByteArray {
        return context().assets.open(name).use { it.readBytes() }
    }

    /** Minimal PCM16 WAV reader. */
    private fun readWavSamples(bytes: ByteArray): ShortArray {
        var off = 12
        var dataOffset = 0
        var dataSize = 0
        while (off + 8 <= bytes.size) {
            val id = bytes.copyOfRange(off, off + 4).decodeToString()
            val size = ByteBuffer.wrap(bytes, off + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                dataOffset = off + 8
                dataSize = size
                break
            }
            off += 8 + size
        }
        require(dataSize > 0 && dataOffset + dataSize <= bytes.size) { "No data chunk" }
        val samples = ShortArray(dataSize / 2)
        ByteBuffer.wrap(bytes, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        return samples
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

    private fun computeRmsDb(data: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) {
            val s = data[i].toDouble()
            sum += s * s
        }
        val rms = Math.sqrt(sum / n).coerceAtLeast(1e-10)
        return 20.0 * Math.log10(rms / 32768.0)
    }

    @Test
    fun echoCancellationReducesEcho() {
        val farInput = readWavSamples(readAsset("far.wav"))
        val mixInput = readWavSamples(readAsset("mix.wav"))

        val totalSamples = (minOf(farInput.size, mixInput.size) / FRAME_SAMPLES) * FRAME_SAMPLES
        val numFrames = totalSamples / FRAME_SAMPLES

        val config = createAec3Config()
        config.setDelayDefaultDelay(25)
        val env = createAec3Environment()
        val factory = createAec3FactoryWithConfig(config)
        val echoControl = createAec3EchoControl(factory, env, SAMPLE_RATE, CHANNELS, CHANNELS)

        val renderBuffer = createAec3AudioBuffer(SAMPLE_RATE, CHANNELS)
        val captureBuffer = createAec3AudioBuffer(SAMPLE_RATE, CHANNELS)

        val output = ShortArray(totalSamples)
        var lastMetrics: Aec3Metrics? = null

        for (f in 0 until numFrames) {
            val offset = f * FRAME_SAMPLES
            renderBuffer.writeChannel(0, FloatArray(FRAME_SAMPLES) { i -> farInput[offset + i].toFloat() })
            echoControl.analyzeRender(renderBuffer)

            captureBuffer.writeChannel(0, FloatArray(FRAME_SAMPLES) { i -> mixInput[offset + i].toFloat() })
            echoControl.analyzeCapture(captureBuffer)
            echoControl.processCapture(captureBuffer, false)

            val processed = captureBuffer.readChannel(0)
            for (i in 0 until FRAME_SAMPLES) {
                var s = processed[i]
                if (s > 32767.0f) s = 32767.0f
                if (s < -32768.0f) s = -32768.0f
                output[offset + i] = s.toInt().toShort()
            }
            if (f == numFrames - 1) {
                lastMetrics = echoControl.getMetrics()
            }
        }

        renderBuffer.close()
        captureBuffer.close()
        echoControl.close()
        factory.close()
        env.close()
        config.close()

        val erle = computeErle(mixInput, output, totalSamples)
        val mixRms = computeRmsDb(mixInput, totalSamples)
        val outRms = computeRmsDb(output, totalSamples)

        println("Android AEC3 test: frames=$numFrames ERLE=${erle}dB mixRms=${mixRms}dB outRms=${outRms}dB metrics=$lastMetrics")

        assertTrue("ERLE should be positive, was $erle", erle > 0)
        assertTrue("Output should be quieter than mix input", outRms < mixRms)
        assertTrue("Metrics should be returned after processing", lastMetrics != null)
    }
}
