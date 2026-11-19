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
 * Rate the devices and the plots run at. AEC3 does not run here: its output has
 * a step at every 10 ms frame boundary at 48 kHz, which is audible as a 100 Hz
 * buzz over speech, and comes out continuous at its own band rate. See
 * [Aec3Pipeline].
 */
internal const val SAMPLE_RATE = 48_000

/** Samples per AEC3 frame: 10 ms at 48 kHz, the size the buffers are built for. */
internal const val FRAME_SAMPLES = SAMPLE_RATE / 100

/**
 * Rate AEC3 runs at: 16 kHz, the rate its bands work at, where its output is
 * continuous. Both ends of the canceller are resampled to and from this.
 */
internal const val AEC_SAMPLE_RATE = 16_000

/** Samples per AEC3 frame at [AEC_SAMPLE_RATE] (160 = 10 ms). */
internal const val AEC_FRAME_SAMPLES = AEC_SAMPLE_RATE / 100

/**
 * AEC3 frames of played audio kept for the far end of the microphone mode. Has
 * to cover the render delay slider's whole range.
 */
internal const val RENDER_HISTORY_FRAMES = 40

/**
 * Platform specific advice appended when SDL cannot open a video device, or
 * empty when the platform has nothing to add.
 */
internal expect val videoInitHint: String

/**
 * Whether SDL should make the window fullscreen as soon as it is created.
 *
 * On Android the SDL window always covers the whole display, and fullscreen is
 * what makes SDL hide the status and navigation bars, which would otherwise
 * draw on top of the ImGui window (SDLActivity owns that decision, so hiding
 * them from the activity is not enough).
 */
internal expect val windowStartsFullscreen: Boolean

/**
 * UI scale the host reports: the display density on Android, 1.0 everywhere
 * else. The font, the style sizes and the window's fixed sizes are laid out
 * for it, so a 2712 px phone gets touch sized controls instead of 13 px text.
 *
 * Android runs this example as a native library, so it cannot read
 * `DisplayMetrics` itself; the activity reports the density over JNI (see
 * `MainActivity.nativeSetUiScale`) before SDL starts.
 */
internal expect var uiScale: Float

/**
 * Live AEC3 visualization, shared by the JVM, native and browser entry points:
 * a far-end signal is played through the speaker and cancelled from the 48 kHz
 * mono microphone capture by [Aec3Pipeline], the echo-cancelled result is played
 * back, and all three signals are drawn with ImPlot in an SDL window.
 *
 * [pipeline] supplies the scopes and the transport: the native targets pass
 * [Aec3Pipeline], the browser a pipeline without the canceller. The capture
 * scopes are sized for the longest window the UI can show, so the slider can
 * widen it without touching the pipeline.
 *
 * [frames] bounds the run, which is what the automated (headless) runs use.
 * [dpiScale] is the host's display density; [driver] replaces the blocking
 * frame loop (the browser schedules one frame per paint instead).
 *
 * Returns `false` when no window could be opened.
 */
fun runWaveformExample(
    pipeline: WaveformPipeline,
    frames: Int = Int.MAX_VALUE,
    dpiScale: Float = uiScale,
    driver: FrameDriver? = null,
): Boolean {
    println("webrtc-aec3-kmp waveform example (frames=$frames)")
    val window = WaveformWindow(pipeline)
    // Capture, cancellation and playback run on the pipeline's own thread (or,
    // in the browser, are polled from this loop); the loop only draws what the
    // pipeline publishes.
    pipeline.start()
    val rendered = ImGuiSdlApp.run("webrtc-aec3-kmp waveform", frames, dpiScale, driver) {
        window.draw()
    }
    if (driver == null) {
        // What a headless run has to show for itself: how much audio was
        // processed and what AEC3 reported for it. A browser driver returns as
        // soon as the loop is scheduled, so this would be zeros.
        val metrics = pipeline.metrics
        println(
            "processed ${pipeline.processedFrames} frames of ${pipeline.frameSize} samples" +
                if (pipeline.droppedPlaybackFrames > 0L) {
                    ", ${pipeline.droppedPlaybackFrames} playback frames dropped"
                } else {
                    ""
                } +
                if (metrics == null) {
                    " (no metrics: nothing was processed)"
                } else {
                    ": ERL ${fixed(metrics.echoReturnLoss, 1)} dB, " +
                        "ERLE ${fixed(metrics.echoReturnLossEnhancement, 1)} dB, " +
                        "delay ${metrics.delayMs} ms"
                },
        )
        println(if (rendered) "done" else "aborted")
    }
    return rendered
}
