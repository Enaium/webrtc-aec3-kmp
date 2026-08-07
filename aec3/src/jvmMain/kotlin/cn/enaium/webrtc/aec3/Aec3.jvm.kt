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

package cn.enaium.webrtc.aec3

// =========================================================================
// JNI bridge – loads the native library and provides external declarations
// =========================================================================

internal object Jni {
    init {
        NativeLoader.load()
    }

    // ---- Config ----
    external fun configCreateDefault(): Long
    external fun configDestroy(ptr: Long)
    external fun configSetDelayDefaultDelay(ptr: Long, value: Int)
    external fun configSetFilterInitialStateSeconds(ptr: Long, value: Float)
    external fun configSetFilterConservativeInitialPhase(ptr: Long, value: Boolean)
    external fun configValidate(ptr: Long): Boolean
    external fun configToJson(ptr: Long): String
    external fun configFromJson(json: String): Long

    // ---- Environment ----
    external fun environmentCreate(): Long
    external fun environmentDestroy(ptr: Long)

    // ---- Factory ----
    external fun factoryCreate(): Long
    external fun factoryCreateWithConfig(configPtr: Long): Long
    external fun factoryDestroy(ptr: Long)

    // ---- EchoControl ----
    external fun echoControlCreate(
        factoryPtr: Long,
        envPtr: Long,
        sampleRate: Int,
        renderChannels: Int,
        captureChannels: Int
    ): Long

    external fun echoControlDestroy(ptr: Long)
    external fun echoControlAnalyzeRender(ecPtr: Long, bufferPtr: Long)
    external fun echoControlAnalyzeCapture(ecPtr: Long, bufferPtr: Long)
    external fun echoControlProcessCapture(ecPtr: Long, bufferPtr: Long, levelChange: Boolean)
    external fun echoControlGetMetrics(ecPtr: Long): DoubleArray
    external fun echoControlSetAudioBufferDelay(ecPtr: Long, delayMs: Int)

    // ---- AudioBuffer ----
    external fun audioBufferCreate(sampleRate: Int, channels: Int): Long
    external fun audioBufferDestroy(ptr: Long)
    external fun audioBufferWriteChannel(ptr: Long, channel: Int, data: FloatArray)
    external fun audioBufferReadChannel(ptr: Long, channel: Int): FloatArray
    external fun audioBufferNumChannels(ptr: Long): Int
    external fun audioBufferSamplesPerChannel(ptr: Long): Int

    // ---- Version ----
    external fun version(): String
}

// =========================================================================
// JVM/Android actual implementations
// =========================================================================

class JvmAec3Config(internal val ptr: Long) : Aec3Config {
    override fun close() {
        Jni.configDestroy(ptr)
    }

    override fun setDelayDefaultDelay(value: Int) {
        Jni.configSetDelayDefaultDelay(ptr, value)
    }

    override fun setFilterInitialStateSeconds(value: Float) {
        Jni.configSetFilterInitialStateSeconds(ptr, value)
    }

    override fun setFilterConservativeInitialPhase(value: Boolean) {
        Jni.configSetFilterConservativeInitialPhase(ptr, value)
    }

    override fun validate(): Boolean = Jni.configValidate(ptr)

    override fun toJson(): String = Jni.configToJson(ptr)
}

class JvmAec3Environment(internal val ptr: Long) : Aec3Environment {
    override fun close() {
        Jni.environmentDestroy(ptr)
    }
}

class JvmAec3Factory(internal val ptr: Long) : Aec3Factory {
    override fun close() {
        Jni.factoryDestroy(ptr)
    }
}

class JvmAec3EchoControl(internal val ptr: Long) : Aec3EchoControl {
    override fun close() {
        Jni.echoControlDestroy(ptr)
    }

    override fun analyzeRender(render: Aec3AudioBuffer) {
        Jni.echoControlAnalyzeRender(ptr, (render as JvmAec3AudioBuffer).ptr)
    }

    override fun analyzeCapture(capture: Aec3AudioBuffer) {
        Jni.echoControlAnalyzeCapture(ptr, (capture as JvmAec3AudioBuffer).ptr)
    }

    override fun processCapture(capture: Aec3AudioBuffer, levelChange: Boolean) {
        Jni.echoControlProcessCapture(ptr, (capture as JvmAec3AudioBuffer).ptr, levelChange)
    }

    override fun getMetrics(): Aec3Metrics {
        val raw = Jni.echoControlGetMetrics(ptr)
        return Aec3Metrics(
            echoReturnLoss = raw[0],
            echoReturnLossEnhancement = raw[1],
            delayMs = raw[2].toInt()
        )
    }

    override fun setAudioBufferDelay(delayMs: Int) {
        Jni.echoControlSetAudioBufferDelay(ptr, delayMs)
    }
}

class JvmAec3AudioBuffer(internal val ptr: Long) : Aec3AudioBuffer {
    override fun close() {
        Jni.audioBufferDestroy(ptr)
    }

    override val numChannels: Int
        get() = Jni.audioBufferNumChannels(ptr)

    override val samplesPerChannel: Int
        get() = Jni.audioBufferSamplesPerChannel(ptr)

    override fun writeChannel(channel: Int, data: FloatArray) {
        Jni.audioBufferWriteChannel(ptr, channel, data)
    }

    override fun readChannel(channel: Int): FloatArray =
        Jni.audioBufferReadChannel(ptr, channel)
}

// =========================================================================
// actual factory functions
// =========================================================================

actual fun createAec3Config(): Aec3Config =
    JvmAec3Config(Jni.configCreateDefault())

actual fun createAec3ConfigFromJson(json: String): Aec3Config? {
    val ptr = Jni.configFromJson(json)
    return if (ptr != 0L) JvmAec3Config(ptr) else null
}

actual fun createAec3Environment(): Aec3Environment =
    JvmAec3Environment(Jni.environmentCreate())

actual fun createAec3Factory(): Aec3Factory =
    JvmAec3Factory(Jni.factoryCreate())

actual fun createAec3FactoryWithConfig(config: Aec3Config): Aec3Factory =
    JvmAec3Factory(Jni.factoryCreateWithConfig((config as JvmAec3Config).ptr))

actual fun createAec3AudioBuffer(sampleRate: Int, channels: Int): Aec3AudioBuffer =
    JvmAec3AudioBuffer(Jni.audioBufferCreate(sampleRate, channels))

actual fun createAec3EchoControl(
    factory: Aec3Factory,
    env: Aec3Environment,
    sampleRate: Int,
    renderChannels: Int,
    captureChannels: Int
): Aec3EchoControl =
    JvmAec3EchoControl(
        Jni.echoControlCreate(
            (factory as JvmAec3Factory).ptr,
            (env as JvmAec3Environment).ptr,
            sampleRate,
            renderChannels,
            captureChannels
        )
    )