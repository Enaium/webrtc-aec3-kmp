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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.webrtc.aec3

import kotlinx.cinterop.*
import webrtc_aec3.*

// =========================================================================
// Native (cinterop) actual implementations
// =========================================================================

class NativeAec3Config(internal val ptr: CPointer<webrtc_aec3_config_t>) : Aec3Config {
    override fun close() {
        webrtc_aec3_config_destroy(ptr)
    }

    override fun setDelayDefaultDelay(value: Int) {
        webrtc_aec3_config_set_delay_default_delay(ptr, value.convert())
    }

    override fun setFilterInitialStateSeconds(value: Float) {
        webrtc_aec3_config_set_filter_initial_state_seconds(ptr, value)
    }

    override fun setFilterConservativeInitialPhase(value: Boolean) {
        webrtc_aec3_config_set_filter_conservative_initial_phase(ptr, value)
    }

    override fun validate(): Boolean =
        webrtc_aec3_config_validate(ptr)

    override fun toJson(): String {
        val jsonPtr = webrtc_aec3_config_to_json(ptr)
        val result = jsonPtr?.toKString() ?: ""
        webrtc_aec3_free_string(jsonPtr)
        return result
    }
}

class NativeAec3Environment(internal val ptr: CPointer<webrtc_aec3_environment_t>) : Aec3Environment {
    override fun close() {
        webrtc_aec3_environment_destroy(ptr)
    }
}

class NativeAec3Factory(internal val ptr: CPointer<webrtc_aec3_factory_t>) : Aec3Factory {
    override fun close() {
        webrtc_aec3_factory_destroy(ptr)
    }
}

class NativeAec3EchoControl(internal val ptr: CPointer<webrtc_aec3_echo_control_t>) : Aec3EchoControl {
    override fun close() {
        webrtc_aec3_echo_control_destroy(ptr)
    }

    override fun analyzeRender(render: Aec3AudioBuffer) {
        webrtc_aec3_echo_control_analyze_render(ptr, (render as NativeAec3AudioBuffer).ptr)
    }

    override fun analyzeCapture(capture: Aec3AudioBuffer) {
        webrtc_aec3_echo_control_analyze_capture(ptr, (capture as NativeAec3AudioBuffer).ptr)
    }

    override fun processCapture(capture: Aec3AudioBuffer, levelChange: Boolean) {
        webrtc_aec3_echo_control_process_capture(ptr, (capture as NativeAec3AudioBuffer).ptr, levelChange)
    }

    override fun getMetrics(): Aec3Metrics {
        val metrics = webrtc_aec3_echo_control_get_metrics(ptr)
        return metrics.useContents {
            Aec3Metrics(
                echoReturnLoss = echo_return_loss,
                echoReturnLossEnhancement = echo_return_loss_enhancement,
                delayMs = delay_ms
            )
        }
    }

    override fun setAudioBufferDelay(delayMs: Int) {
        webrtc_aec3_echo_control_set_audio_buffer_delay(ptr, delayMs)
    }
}

class NativeAec3AudioBuffer(internal val ptr: CPointer<webrtc_aec3_audio_buffer_t>) : Aec3AudioBuffer {
    override fun close() {
        webrtc_aec3_audio_buffer_destroy(ptr)
    }

    override val numChannels: Int
        get() = webrtc_aec3_audio_buffer_num_channels(ptr)

    override val samplesPerChannel: Int
        get() = webrtc_aec3_audio_buffer_samples_per_channel(ptr)

    override fun writeChannel(channel: Int, data: FloatArray) {
        val channelPtr = webrtc_aec3_audio_buffer_get_channel_data(ptr, channel) ?: return
        for (i in data.indices) {
            channelPtr[i] = data[i]
        }
    }

    override fun readChannel(channel: Int): FloatArray {
        val channelPtr = webrtc_aec3_audio_buffer_get_channel_data_const(ptr, channel) ?: return FloatArray(0)
        val samples = samplesPerChannel
        return FloatArray(samples) { i -> channelPtr[i] }
    }
}

// =========================================================================
// actual factory functions
// =========================================================================

actual fun createAec3Config(): Aec3Config {
    val ptr = webrtc_aec3_config_create_default()
        ?: error("webrtc_aec3_config_create_default returned null")
    return NativeAec3Config(ptr)
}

actual fun createAec3ConfigFromJson(json: String): Aec3Config? {
    return memScoped {
        val parsingSuccessful = alloc<BooleanVar>()
        val ptr = webrtc_aec3_config_from_json(json, parsingSuccessful.ptr)
        if (parsingSuccessful.value) {
            NativeAec3Config(ptr ?: return@memScoped null)
        } else {
            null
        }
    }
}

actual fun createAec3Environment(): Aec3Environment {
    val ptr = webrtc_aec3_environment_create()
        ?: error("webrtc_aec3_environment_create returned null")
    return NativeAec3Environment(ptr)
}

actual fun createAec3Factory(): Aec3Factory {
    val ptr = webrtc_aec3_factory_create()
        ?: error("webrtc_aec3_factory_create returned null")
    return NativeAec3Factory(ptr)
}

actual fun createAec3FactoryWithConfig(config: Aec3Config): Aec3Factory {
    val ptr = webrtc_aec3_factory_create_with_config((config as NativeAec3Config).ptr)
        ?: error("webrtc_aec3_factory_create_with_config returned null")
    return NativeAec3Factory(ptr)
}

actual fun createAec3AudioBuffer(sampleRate: Int, channels: Int): Aec3AudioBuffer {
    val ptr = webrtc_aec3_audio_buffer_create(sampleRate, channels)
        ?: error("webrtc_aec3_audio_buffer_create returned null")
    return NativeAec3AudioBuffer(ptr)
}

actual fun createAec3EchoControl(
    factory: Aec3Factory,
    env: Aec3Environment,
    sampleRate: Int,
    renderChannels: Int,
    captureChannels: Int
): Aec3EchoControl {
    val ptr = webrtc_aec3_echo_control_create(
        (factory as NativeAec3Factory).ptr,
        (env as NativeAec3Environment).ptr,
        sampleRate,
        renderChannels,
        captureChannels
    ) ?: error("webrtc_aec3_echo_control_create returned null")
    return NativeAec3EchoControl(ptr)
}