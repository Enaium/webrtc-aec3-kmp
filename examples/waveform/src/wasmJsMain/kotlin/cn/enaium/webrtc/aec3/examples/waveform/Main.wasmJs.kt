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

/**
 * Browser entry point.
 *
 * A wasm main thread that spins never lets the page paint, so the frame loop
 * is handed to `requestAnimationFrame`: one frame per browser paint. The host
 * page (index.html) initializes the Emscripten SDL3 and imgui modules before
 * this module is imported.
 *
 * The pipeline is [WasmWaveformPipeline]: AEC3 has no wasm target, so the
 * browser build plays the capture through and draws it as the output. Web
 * Audio starts on a user gesture, so the page starts silent until the canvas
 * is clicked.
 */
fun main() {
    val pipeline = WasmWaveformPipeline(windowSamples(MAX_WINDOW_MILLIS))
    val driver: FrameDriver = { step, done ->
        fun tick() {
            // Web Audio cannot block: the capture ring is polled from the
            // frame loop instead of a device thread.
            pipeline.pump()
            if (step()) {
                requestAnimationFrame { tick() }
            } else {
                done()
                pipeline.close()
            }
        }
        requestAnimationFrame { tick() }
    }
    runWaveformExample(pipeline, driver = driver)
}

private external fun requestAnimationFrame(callback: () -> Unit): Int
