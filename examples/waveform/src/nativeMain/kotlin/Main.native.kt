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

import cn.enaium.webrtc.aec3.examples.waveform.Aec3Pipeline
import cn.enaium.webrtc.aec3.examples.waveform.MAX_WINDOW_MILLIS
import cn.enaium.webrtc.aec3.examples.waveform.runWaveformExample
import cn.enaium.webrtc.aec3.examples.waveform.windowSamples
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.system.exitProcess

/**
 * Native entry point (Kotlin/Native executables take their entry in the
 * default package). Runs the waveform visualization until the window closes;
 * `AEC3_KMP_FRAMES` bounds the run, which is what automated runs use.
 *
 * ```bash
 * ./examples/waveform/build/bin/macosArm64/debugExecutable/waveform.kexe
 * AEC3_KMP_FRAMES=300 ./examples/waveform/build/bin/linuxX64/releaseExecutable/waveform.kexe
 * ```
 */
fun main() {
    val frames = getenv("AEC3_KMP_FRAMES")?.toKString()?.toIntOrNull() ?: Int.MAX_VALUE
    val displayed = Aec3Pipeline(windowSamples(MAX_WINDOW_MILLIS)).use {
        runWaveformExample(it, frames)
    }
    if (!displayed) {
        exitProcess(1)
    }
}