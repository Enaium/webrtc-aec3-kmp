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

import org.khronos.webgl.Float32Array
import org.khronos.webgl.toFloat32Array
import org.khronos.webgl.toFloatArray

/*
 * Wasm implementation of the AEC3 bindings.
 *
 * Kotlin/Wasm has no cinterop, so the C API is compiled to an Emscripten module
 * (aec3_wasm.js/.wasm) and driven through a JS glue layer
 * (jni/wasm/aec3_kmp_glue.js) that exposes the C functions as globals. The host
 * page loads the glue and awaits initAec3Kmp() before the Kotlin module runs.
 *
 * Handles are wasm32 pointers carried as Int; C `bool` crosses as 0/1.
 */

private external fun webrtc_aec3_config_create_default(): Int

private external fun webrtc_aec3_config_create_default_multichannel(): Int

private external fun webrtc_aec3_config_destroy(config: Int)

private external fun webrtc_aec3_config_validate(config: Int): Int

private external fun webrtc_aec3_config_from_json(json: String): Int

private external fun webrtc_aec3_config_to_json(config: Int): String?

private external fun webrtc_aec3_config_set_delay_default_delay(config: Int, value: Int)

private external fun webrtc_aec3_config_set_filter_initial_state_seconds(config: Int, value: Float)

private external fun webrtc_aec3_config_set_filter_conservative_initial_phase(config: Int, value: Int)

private external fun webrtc_aec3_environment_create(): Int

private external fun webrtc_aec3_environment_destroy(env: Int)

private external fun webrtc_aec3_audio_buffer_create(sampleRate: Int, channels: Int): Int

private external fun webrtc_aec3_audio_buffer_destroy(buffer: Int)

private external fun webrtc_aec3_audio_buffer_num_channels(buffer: Int): Int

private external fun webrtc_aec3_audio_buffer_samples_per_channel(buffer: Int): Int

private external fun webrtc_aec3_factory_create(): Int

private external fun webrtc_aec3_factory_create_with_config(config: Int): Int

private external fun webrtc_aec3_factory_destroy(factory: Int)

private external fun webrtc_aec3_echo_control_create(
    factory: Int,
    env: Int,
    sampleRate: Int,
    renderChannels: Int,
    captureChannels: Int,
): Int

private external fun webrtc_aec3_echo_control_destroy(control: Int)

private external fun webrtc_aec3_echo_control_analyze_render(control: Int, render: Int)

private external fun webrtc_aec3_echo_control_analyze_capture(control: Int, capture: Int)

private external fun webrtc_aec3_echo_control_process_capture(control: Int, capture: Int, levelChange: Int)

private external fun webrtc_aec3_echo_control_set_audio_buffer_delay(control: Int, delayMs: Int)

/** Metrics from the shim: [delayMs, echoReturnLoss, echoReturnLossEnhancement]. */
private external fun aec3KmpGetMetrics(control: Int): Float32Array

/**
 * Element access into a [Float32Array]: wasm JS interop has no operator `get`,
 * so the glue exposes the read (the sdl-kmp glue does the same).
 */
private external fun aec3KmpFloat32Get(array: Float32Array, index: Int): Float

private external fun aec3KmpWriteChannel(buffer: Int, channel: Int, data: Float32Array)

private external fun aec3KmpReadChannel(buffer: Int, channel: Int): Float32Array

class WasmAec3Config(internal val ptr: Int) : Aec3Config {
    override fun close() {
        webrtc_aec3_config_destroy(ptr)
    }

    override fun setDelayDefaultDelay(value: Int) {
        webrtc_aec3_config_set_delay_default_delay(ptr, value)
    }

    override fun setFilterInitialStateSeconds(value: Float) {
        webrtc_aec3_config_set_filter_initial_state_seconds(ptr, value)
    }

    override fun setFilterConservativeInitialPhase(value: Boolean) {
        webrtc_aec3_config_set_filter_conservative_initial_phase(ptr, if (value) 1 else 0)
    }

    override fun validate(): Boolean = webrtc_aec3_config_validate(ptr) != 0

    override fun toJson(): String = webrtc_aec3_config_to_json(ptr) ?: ""
}

class WasmAec3Environment(internal val ptr: Int) : Aec3Environment {
    override fun close() {
        webrtc_aec3_environment_destroy(ptr)
    }
}

class WasmAec3Factory(internal val ptr: Int) : Aec3Factory {
    override fun close() {
        webrtc_aec3_factory_destroy(ptr)
    }
}

class WasmAec3EchoControl(internal val ptr: Int) : Aec3EchoControl {
    override fun close() {
        webrtc_aec3_echo_control_destroy(ptr)
    }

    override fun analyzeRender(render: Aec3AudioBuffer) {
        webrtc_aec3_echo_control_analyze_render(ptr, (render as WasmAec3AudioBuffer).ptr)
    }

    override fun analyzeCapture(capture: Aec3AudioBuffer) {
        webrtc_aec3_echo_control_analyze_capture(ptr, (capture as WasmAec3AudioBuffer).ptr)
    }

    override fun processCapture(capture: Aec3AudioBuffer, levelChange: Boolean) {
        webrtc_aec3_echo_control_process_capture(ptr, (capture as WasmAec3AudioBuffer).ptr, if (levelChange) 1 else 0)
    }

    override fun getMetrics(): Aec3Metrics {
        val raw = aec3KmpGetMetrics(ptr)
        return Aec3Metrics(
            echoReturnLoss = aec3KmpFloat32Get(raw, 1).toDouble(),
            echoReturnLossEnhancement = aec3KmpFloat32Get(raw, 2).toDouble(),
            delayMs = aec3KmpFloat32Get(raw, 0).toInt(),
        )
    }

    override fun setAudioBufferDelay(delayMs: Int) {
        webrtc_aec3_echo_control_set_audio_buffer_delay(ptr, delayMs)
    }
}

class WasmAec3AudioBuffer(internal val ptr: Int) : Aec3AudioBuffer {
    override fun close() {
        webrtc_aec3_audio_buffer_destroy(ptr)
    }

    override val numChannels: Int
        get() = webrtc_aec3_audio_buffer_num_channels(ptr)

    override val samplesPerChannel: Int
        get() = webrtc_aec3_audio_buffer_samples_per_channel(ptr)

    override fun writeChannel(channel: Int, data: FloatArray) {
        aec3KmpWriteChannel(ptr, channel, data.toFloat32Array())
    }

    override fun readChannel(channel: Int): FloatArray = aec3KmpReadChannel(ptr, channel).toFloatArray()
}

actual fun createAec3Config(): Aec3Config =
    WasmAec3Config(webrtc_aec3_config_create_default())

actual fun createAec3ConfigFromJson(json: String): Aec3Config? {
    val ptr = webrtc_aec3_config_from_json(json)
    return if (ptr != 0) WasmAec3Config(ptr) else null
}

actual fun createAec3Environment(): Aec3Environment =
    WasmAec3Environment(webrtc_aec3_environment_create())

actual fun createAec3Factory(): Aec3Factory =
    WasmAec3Factory(webrtc_aec3_factory_create())

actual fun createAec3FactoryWithConfig(config: Aec3Config): Aec3Factory =
    WasmAec3Factory(webrtc_aec3_factory_create_with_config((config as WasmAec3Config).ptr))

actual fun createAec3AudioBuffer(sampleRate: Int, channels: Int): Aec3AudioBuffer =
    WasmAec3AudioBuffer(webrtc_aec3_audio_buffer_create(sampleRate, channels))

actual fun createAec3EchoControl(
    factory: Aec3Factory,
    env: Aec3Environment,
    sampleRate: Int,
    renderChannels: Int,
    captureChannels: Int,
): Aec3EchoControl = WasmAec3EchoControl(
    webrtc_aec3_echo_control_create(
        (factory as WasmAec3Factory).ptr,
        (env as WasmAec3Environment).ptr,
        sampleRate,
        renderChannels,
        captureChannels,
    ),
)
