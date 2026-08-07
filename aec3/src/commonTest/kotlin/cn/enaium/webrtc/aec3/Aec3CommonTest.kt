package cn.enaium.webrtc.aec3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Aec3CommonTest {

    @Test
    fun testCreateConfig() {
        createAec3Config().use { config ->
            assertTrue(config.validate())
        }
    }

    @Test
    fun testCreateEnvironment() {
        createAec3Environment().use { }
    }

    @Test
    fun testCreateFactory() {
        createAec3Factory().use { }
    }

    @Test
    fun testCreateAudioBuffer() {
        createAec3AudioBuffer(16000, 1).use { buffer ->
            assertEquals(1, buffer.numChannels)
            assertEquals(160, buffer.samplesPerChannel)
        }
    }

    @Test
    fun testAudioBufferWriteReadRoundTrip() {
        createAec3AudioBuffer(16000, 1).use { buffer ->
            val data = FloatArray(160) { i -> i * 0.001f }
            buffer.writeChannel(0, data)
            val read = buffer.readChannel(0)
            assertEquals(160, read.size)
            for (i in data.indices) {
                assertTrue(kotlin.math.abs(data[i] - read[i]) < 1e-4f)
            }
        }
    }

    @Test
    fun testConfigJsonRoundTrip() {
        val config = createAec3Config()
        config.setDelayDefaultDelay(25)
        val json = config.toJson()
        val parsed = createAec3ConfigFromJson(json)
        assertNotNull(parsed)
        parsed.close()
        config.close()
    }
}
