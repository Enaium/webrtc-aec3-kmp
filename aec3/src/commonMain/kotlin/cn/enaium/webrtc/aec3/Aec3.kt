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

/**
 * AEC3 metrics returned by [Aec3EchoControl.getMetrics].
 */
data class Aec3Metrics(
    val echoReturnLoss: Double,
    val echoReturnLossEnhancement: Double,
    val delayMs: Int
)

// =========================================================================
// Top-level expect factory functions
// =========================================================================

expect fun createAec3Config(): Aec3Config
expect fun createAec3ConfigFromJson(json: String): Aec3Config?

expect fun createAec3Environment(): Aec3Environment

expect fun createAec3Factory(): Aec3Factory

expect fun createAec3FactoryWithConfig(config: Aec3Config): Aec3Factory

expect fun createAec3AudioBuffer(sampleRate: Int, channels: Int): Aec3AudioBuffer

expect fun createAec3EchoControl(
    factory: Aec3Factory,
    env: Aec3Environment,
    sampleRate: Int,
    renderChannels: Int,
    captureChannels: Int
): Aec3EchoControl

// =========================================================================
// Common interfaces
// =========================================================================

/** Configuration for the AEC3 echo canceller. */
interface Aec3Config : AutoCloseable {
    fun setDelayDefaultDelay(value: Int)
    fun setFilterInitialStateSeconds(value: Float)
    fun setFilterConservativeInitialPhase(value: Boolean)
    fun validate(): Boolean
    fun toJson(): String
}

/** Environment in which the echo canceller operates. */
interface Aec3Environment : AutoCloseable

/** Factory for creating [Aec3EchoControl] instances. */
interface Aec3Factory : AutoCloseable

/** Multi-channel audio buffer used for render/capture data. */
interface Aec3AudioBuffer : AutoCloseable {
    val numChannels: Int
    val samplesPerChannel: Int

    fun writeChannel(channel: Int, data: FloatArray)
    fun readChannel(channel: Int): FloatArray
}

/** Main echo control interface for the AEC3 algorithm. */
interface Aec3EchoControl : AutoCloseable {
    fun analyzeRender(render: Aec3AudioBuffer)
    fun analyzeCapture(capture: Aec3AudioBuffer)
    fun processCapture(capture: Aec3AudioBuffer, levelChange: Boolean)
    fun getMetrics(): Aec3Metrics
    fun setAudioBufferDelay(delayMs: Int)
}