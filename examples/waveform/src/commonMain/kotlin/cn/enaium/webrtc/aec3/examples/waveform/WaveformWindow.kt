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

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.ImVec4
import cn.enaium.imgui.extensions.implot.ImPlot
import cn.enaium.imgui.extensions.implot.ImPlotAxisFlags
import cn.enaium.imgui.extensions.implot.ImPlotCond
import cn.enaium.imgui.extensions.implot.ImPlotSpec
import kotlin.math.log10
import kotlin.math.round
import kotlin.math.roundToInt

/** Shortest window the slider allows, in milliseconds. */
internal const val MIN_WINDOW_MILLIS = 100

/** Longest window the slider allows, in milliseconds. */
internal const val MAX_WINDOW_MILLIS = 10_000

/** Window the example starts with, in milliseconds. */
internal const val DEFAULT_WINDOW_MILLIS = 1_000

/** Lowest far-end level the slider allows, in dBFS. */
private const val MIN_REFERENCE_DB = -60f

/** Highest far-end level the slider allows, in dBFS. */
private const val MAX_REFERENCE_DB = 0f

/** Delay slider range, in milliseconds. */
private const val MAX_DELAY_MILLIS = 500

/**
 * The example UI: the far-end signal, the raw microphone capture and the
 * echo-cancelled output drawn as three ImPlot lines over the same sliding
 * window, with the AEC3 metrics of the last processed frames.
 *
 * All plots share one amplitude scale. A microphone running at speech level is
 * roughly 30 dB below full scale, so a fixed `-1..1` axis would draw a flat
 * line; the shared scale keeps the three signals comparable - which is the
 * whole point of showing them side by side - and the level readout keeps the
 * absolute values visible.
 *
 * The window length is a slider: the scopes always hold [MAX_WINDOW_MILLIS] and
 * the plots show the newest part of them.
 */
class WaveformWindow(
    private val pipeline: WaveformPipeline,
    initialWindowMillis: Int = DEFAULT_WINDOW_MILLIS,
) {

    private val sliderValue = IntArray(1) { initialWindowMillis.coerceIn(MIN_WINDOW_MILLIS, MAX_WINDOW_MILLIS) }

    private val aecEnabled = BooleanArray(1) { pipeline.aecEnabled }

    private val playbackEnabled = BooleanArray(1) { pipeline.playbackEnabled }

    private val sourceIndex = IntArray(1) { pipeline.farEndSource.ordinal }

    private val farEndLevelDb = FloatArray(1) { pipeline.farEndLevelDb }

    private val delayMillis = IntArray(1) { pipeline.delayMs }

    private val renderDelay = IntArray(1) { pipeline.renderDelayFrames }

    private val aecInputDb = FloatArray(1) { pipeline.aecInputDb }

    private var windowMillis = sliderValue[0]

    /**
     * Min/max envelope of the far end, the capture and the output: one pair per
     * plot column, written by the scopes and drawn as they are. Sized by the
     * plot width rather than the window, so a ten second window costs no more
     * memory than a short one.
     */
    private var referenceEnvelope = FloatArray(0)

    private var captureEnvelope = FloatArray(0)

    private var outputEnvelope = FloatArray(0)

    /** Amplitude axis of the far-end plot, in `-1..1`. */
    private var referenceScale = MIN_SCALE

    /** Amplitude axis of the capture plot, in `-1..1`. */
    private var captureScale = MIN_SCALE

    /**
     * Amplitude axis of the output plot, in `-1..1`.
     *
     * The echo-cancelled output runs 20 dB or more below the capture, so on the
     * shared axis it was squeezed into a couple of pixels and read as a
     * staircase rather than as a signal; each plot now follows its own level and
     * the dBFS line above keeps the absolute values comparable.
     */
    private var outputScale = MIN_SCALE

    /** One frame of the UI. Call between [ImGui.newFrame] and [ImGui.render]. */
    fun draw() {
        // One sweep per scope, and only over what is drawn: the scopes hand back
        // the min/max pairs the plots need, so nothing is copied out and walked
        // a second time on the render thread.
        // The plots are min/max pairs, and ImGui indexes its vertices with 16
        // bits: an anti-aliased polyline costs a handful of vertices per point,
        // so a full-width envelope on a 2712 px display (3 x 5424 points here)
        // runs past the 65535 vertex limit and the last drawn line is wrapped
        // into nonsense. A cap of a few hundred columns also keeps the frame
        // cost bounded on devices where the GPU backend rebuilds its buffers per
        // draw list every frame.
        val columns = minOf(maxOf(ImGui.getIO().displaySize.x.toInt(), MIN_COLUMNS), MAX_COLUMNS)
        if (referenceEnvelope.size != columns * 2) {
            referenceEnvelope = FloatArray(columns * 2)
            captureEnvelope = FloatArray(columns * 2)
            outputEnvelope = FloatArray(columns * 2)
        }
        val samples = windowSamples(windowMillis)
        val step = maxOf((samples + columns - 1) / columns, 1)
        val referencePeak = pipeline.referenceScope.scan(step, columns, referenceEnvelope)
        val capturePeak = pipeline.inputScope.scan(step, columns, captureEnvelope)
        val outputPeak = pipeline.outputScope.scan(step, columns, outputEnvelope)
        // The synthesised far end has a known level, so its axis follows that
        // level; the looped capture moves with the microphone, so its axis
        // follows the signal like the capture pair does.
        referenceScale = if (pipeline.farEndSource.isGenerated) {
            maxOf(referencePeak, dbfsToAmplitude(pipeline.farEndLevelDb), MIN_SCALE) * HEADROOM
        } else {
            maxOf(referencePeak * HEADROOM, referenceScale * RELEASE, MIN_SCALE)
        }
        // The microphone side runs 30 dB or more below the playback signal, so
        // every plot follows its own level: a shared axis keeps the levels
        // comparable but flattens whatever is quieter, and the output of a
        // working canceller is always the quietest of the three. Fast attack,
        // slow release: the axis follows a louder passage at once and shrinks
        // back over roughly a second.
        captureScale = maxOf(capturePeak * HEADROOM, captureScale * RELEASE, MIN_SCALE)
        outputScale = maxOf(outputPeak * HEADROOM, outputScale * RELEASE, MIN_SCALE)

        val displaySize = ImGui.getIO().displaySize
        // The window owns the whole viewport: pinned to the top-left corner,
        // resized to the display and stripped of decorations, so it cannot be
        // dragged, resized or collapsed and always fills the SDL window.
        ImGui.setNextWindowPos(ImVec2(0f, 0f), ImGuiCond.ALWAYS)
        ImGui.setNextWindowSize(displaySize, ImGuiCond.ALWAYS)
        if (ImGui.begin("webrtc-aec3-kmp waveform", null, WINDOW_FLAGS)) {
            // The control row: no labels in the window, every control carries
            // its explanation as a tooltip and the widgets themselves say what
            // they are (a checkbox, a slider with its unit). They flow like
            // words in a paragraph, so however narrow the window is, the row
            // wraps instead of clipping.
            newRow()
            val loopback = pipeline.farEndSource == FarEndSource.MICROPHONE

            // The canceller itself. Turning it off passes the raw microphone
            // through to the playback, which is what makes the cancellation
            // audible.
            placeCheck("AEC", aecEnabled) { pipeline.aecEnabled = aecEnabled[0] }
            ImGui.setItemTooltip(
                "Echo canceller. Switched off, the raw microphone is played back instead - " +
                    "that is what makes the cancellation audible.",
            )

            placeCheck("playback", playbackEnabled) { pipeline.playbackEnabled = playbackEnabled[0] }
            ImGui.setItemTooltip(
                if (loopback) {
                    "Play the echo-cancelled capture. The loop stays closed while AEC is off, " +
                        "so this is the switch that mutes the loopback."
                } else {
                    "Play the echo-cancelled capture together with the far end."
                },
            )

            placeSlider(SLIDER_WIDTH) {
                if (ImGui.sliderInt("##delay", delayMillis, 0, MAX_DELAY_MILLIS, "%d ms")) {
                    pipeline.setDelay(delayMillis[0])
                }
            }
            ImGui.setItemTooltip(
                "Render -> capture delay told to AEC3. It is the delay the audio buffers add - " +
                    "the playback queue plus the capture queue - not the acoustic one; a number " +
                    "far below the real one misaligns its delay search.",
            )

            placeSlider(SLIDER_WIDTH) {
                if (ImGui.sliderInt("##renderdelay", renderDelay, 0, MAX_RENDER_DELAY_FRAMES, "%d frames")) {
                    pipeline.renderDelayFrames = renderDelay[0]
                }
            }
            ImGui.setItemTooltip(
                "How far behind the newest handed-over frame the far end is taken from, in 10 ms " +
                    "frames. The output queue holds about ten of them, so that is what the speaker " +
                    "is playing now: a reference that leads its own echo pins the delay estimator " +
                    "at 8 ms and leaves the echo in the capture.",
            )

            placeSlider(SLIDER_WIDTH) {
                if (ImGui.sliderFloat("##aecinput", aecInputDb, MIN_AEC_INPUT_DB, 0f, "%.0f dBFS")) {
                    pipeline.aecInputDb = aecInputDb[0]
                }
            }
            ImGui.setItemTooltip(
                "Headroom AEC3's frames are driven with. Its band processing runs on the int16 " +
                    "range, so a phone microphone near full scale runs it hot and leaves wideband " +
                    "debris on speech. The factor comes back out, so the level is unchanged.",
            )

            // The far-end signal: what the speaker plays, and therefore what
            // AEC3 has to remove from the capture. "Microphone (loopback)" makes
            // the capture itself the far end: it is played back through the
            // speaker and cancelled from the microphone again.
            placeCombo(COMBO_WIDTH) {
                if (ImGui.combo("##source", sourceIndex, SOURCE_LABELS)) {
                    pipeline.farEndSource = FarEndSource.entries[sourceIndex[0]]
                }
            }
            ImGui.setItemTooltip(
                "What AEC3 has to remove from the capture. Microphone (loopback) makes the capture " +
                    "its own far end: it is played back through the speaker and cancelled from the " +
                    "microphone again.",
            )

            placeSlider(SLIDER_WIDTH) {
                if (ImGui.sliderFloat("##level", farEndLevelDb, MIN_REFERENCE_DB, MAX_REFERENCE_DB, "%.0f dBFS")) {
                    pipeline.farEndLevelDb = farEndLevelDb[0]
                }
            }
            ImGui.setItemTooltip(
                if (loopback) "Loop gain: how loud the cancelled capture is played back."
                else "Level of the synthesised far end.",
            )

            placeSlider(SLIDER_WIDTH) {
                if (ImGui.sliderInt("##window", sliderValue, MIN_WINDOW_MILLIS, MAX_WINDOW_MILLIS, "%d ms")) {
                    resizeWindow(sliderValue[0])
                }
            }
            ImGui.setItemTooltip("Length of the plotted window.")

            // Everything that is not a control folds away below them: what stays
            // on screen is the controls, and a click on the header shows the
            // devices, the format and the canceller's metrics.
            if (ImGui.collapsingHeader("status")) status(referencePeak, capturePeak, outputPeak)
            ImGui.separator()

            // The window is fullscreen, so the three plots split whatever height
            // is left instead of leaving the lower part empty.
            val available = ImGui.getContentRegionAvail()
            val plotHeight = maxOf((available.y - PLOT_GAP * 2f) / 3f, MIN_PLOT_HEIGHT)
            plot("far end (render)", referenceEnvelope, step, REFERENCE_COLOR, plotHeight, referenceScale)
            plot("capture (microphone)", captureEnvelope, step, CAPTURE_COLOR, plotHeight, captureScale)
            plot("output (echo-cancelled)", outputEnvelope, step, OUTPUT_COLOR, plotHeight, outputScale)
        }
        ImGui.end()
    }

    /**
     * Devices, format and canceller metrics: everything the window showed above
     * the controls before they were folded away, kept one click behind the
     * status header.
     */
    private fun status(referencePeak: Float, capturePeak: Float, outputPeak: Float) {
        val captureError = pipeline.captureError
        if (captureError == null) {
            ImGui.text("capture: ${pipeline.captureDeviceName} via ${pipeline.systemName}")
        } else {
            // Android asks for the microphone permission while the app is
            // already running, so the device can appear a moment later.
            ImGui.text("no capture yet, retrying: $captureError")
        }

        val playbackError = pipeline.playbackError
        if (playbackError == null) {
            val dropped = pipeline.droppedPlaybackFrames
            val gaps = pipeline.playbackGaps
            ImGui.text(
                "playback: ${pipeline.playbackDeviceName} via ${pipeline.systemName}" +
                    // A device that stops consuming is worth showing: the
                    // window keeps running, but nothing can be heard.
                    if (dropped > 0L || gaps > 0L) " (dropped $dropped frames, $gaps gaps)" else "",
            )
        } else {
            ImGui.text("no playback yet, retrying: $playbackError")
        }

        ImGui.text("format: ${pipeline.format}, aec3 frame: ${pipeline.frameSize} samples (10 ms)")
        ImGui.text("processed ${pipeline.processedFrames} frames")

        val metrics = pipeline.metrics
        if (metrics == null) {
            ImGui.text("metrics: waiting for the first frames")
        } else {
            ImGui.text(
                "ERL ${fixed(metrics.echoReturnLoss, 1)} dB | " +
                    "ERLE ${fixed(metrics.echoReturnLossEnhancement, 1)} dB | " +
                    "delay ${metrics.delayMs} ms (set ${pipeline.delayMs} ms)",
            )
        }

        ImGui.text(
            "peak: reference ${dbfs(referencePeak)} dBFS, " +
                "capture ${dbfs(capturePeak)} dBFS, " +
                "output ${dbfs(outputPeak)} dBFS" +
                " | axis: far end +-${fixed(referenceScale.toDouble(), 4)}, " +
                "microphone +-${fixed(captureScale.toDouble(), 4)}, " +
                "output +-${fixed(outputScale.toDouble(), 4)}",
        )
    }

    // =========================================================================
    // Layout
    //
    // A row of controls does not fit on a phone (or in a narrow window), and a
    // widget that is cut off at the edge cannot be operated, so the controls go
    // into rows that wrap: every helper below measures what it is about to draw
    // and starts a new row when the rest of the current one is too small. The
    // width of a widget is the frame ImGui draws plus, for a checkbox, the label
    // inside it.
    // =========================================================================

    /** Room the controls already placed on the current row take up. */
    private var rowWidth = 0f

    /** Starts a new control row. */
    private fun newRow() {
        rowWidth = 0f
    }

    /** Reserves [width] on the current row, wrapping onto a new one when it does not fit. */
    private fun reserve(width: Float) {
        val available = ImGui.getContentRegionAvail().x
        val needed = if (rowWidth > 0f) ITEM_GAP + width else width
        if (rowWidth > 0f && rowWidth + needed > available) {
            // The cursor is already on the next line, an item leaves it there.
            rowWidth = width
        } else {
            if (rowWidth > 0f) {
                ImGui.sameLine()
                rowWidth += ITEM_GAP
            }
            rowWidth += width
        }
    }

    /** A checkbox: the check square plus the label it carries. */
    private fun placeCheck(label: String, value: BooleanArray, onChange: () -> Unit) {
        reserve(ImGui.getFrameHeight() + LABEL_GAP + ImGui.calcTextSize(label).x)
        if (ImGui.checkbox(label, value)) onChange()
    }

    /** A slider [itemWidth] wide; what it does is in its tooltip. */
    private fun placeSlider(itemWidth: Float, block: () -> Unit) {
        reserve(itemWidth)
        ImGui.setNextItemWidth(itemWidth)
        block()
    }

    /** A dropdown [itemWidth] wide. */
    private fun placeCombo(itemWidth: Float, block: () -> Unit) {
        reserve(itemWidth)
        ImGui.setNextItemWidth(itemWidth)
        block()
    }

    private fun resizeWindow(millis: Int) {
        windowMillis = millis
    }

    /**
     * Draws one envelope. Every pair of points covers [step] samples, which is
     * what the x scale is derived from, and the cost of the line is bounded by
     * the plot width instead of the window length.
     */
    private fun plot(title: String, envelope: FloatArray, step: Int, color: ImVec4, height: Float, scale: Float) {
        if (!ImPlot.beginPlot(title, ImVec2(-1f, height))) return
        ImPlot.setupAxes("milliseconds", "amplitude", ImPlotAxisFlags.NONE, ImPlotAxisFlags.NONE)
        // The window scrolls, so the x axis is pinned to the full sweep.
        ImPlot.setupAxesLimits(
            0.0,
            windowMillis.toDouble(),
            -scale.toDouble(),
            scale.toDouble(),
            ImPlotCond.ALWAYS,
        )
        ImPlot.plotLine(
            "##$title",
            envelope,
            xScale = step / 2.0 * MILLIS_PER_SAMPLE,
            spec = ImPlotSpec(lineColor = color, lineWeight = 1f),
        )
        ImPlot.endPlot()
    }

    private fun dbfs(value: Float): String =
        if (value <= 0f) "-inf" else fixed(20.0 * log10(value.toDouble()), 1)

    private companion object {
        /** Fullscreen host window: no title bar, nothing to drag or resize. */
        const val WINDOW_FLAGS = ImGuiWindowFlags.NO_TITLE_BAR or
            ImGuiWindowFlags.NO_RESIZE or
            ImGuiWindowFlags.NO_MOVE or
            ImGuiWindowFlags.NO_SCROLLBAR or
            ImGuiWindowFlags.NO_COLLAPSE or
            ImGuiWindowFlags.NO_SAVED_SETTINGS or
            ImGuiWindowFlags.NO_BRING_TO_FRONT_ON_FOCUS or
            ImGuiWindowFlags.NO_NAV_FOCUS

        /** Space kept between two plots. */
        val PLOT_GAP: Float get() = ImGui.getFontSize() * 0.5f

        val SOURCE_LABELS = FarEndSource.entries.map { it.label }.toTypedArray()

        /** Floor for very small windows. */
        val MIN_PLOT_HEIGHT: Float get() = ImGui.getFontSize() * 4f

        /** Guarantees the envelope has at least one column to fill. */
        const val MIN_COLUMNS = 1

        /**
         * Upper bound on the envelope width. 1024 columns is 2048 points per
         * plot - a few thousand vertices even with anti-aliasing, so three
         * plots stay well inside ImGui's 16 bit index range - and still
         * resolves a 2.6 px wide column on a 2712 px display.
         */
        const val MAX_COLUMNS = 1024

        /**
         * Widths the flow reserves for a control, and the gap kept between two.
         * Relative to the font, which is baked at the host's UI scale: a slider
         * that fits "384 ms" is the same fraction of a line either way.
         */
        val SLIDER_WIDTH: Float get() = ImGui.getFontSize() * 8f

        val COMBO_WIDTH: Float get() = ImGui.getFontSize() * 11f

        /**
         * Space between two controls of a row and between a checkbox's square and
         * its label: the style's own spacing, which [pushScaledStyle] scales.
         */
        val ITEM_GAP: Float get() = ImGui.getFontSize() * 8f / 13f

        val LABEL_GAP: Float get() = ImGui.getFontSize() * 4f / 13f

        /** Render delay slider range, in 10 ms frames. */
        const val MAX_RENDER_DELAY_FRAMES = RENDER_HISTORY_FRAMES - 1

        /** AEC3 input slider range, in dBFS. */
        const val MIN_AEC_INPUT_DB = -24f

        /** Keeps the peaks below the top of the axis. */
        const val HEADROOM = 1.15f

        /** Per-frame release factor of the amplitude scale. */
        const val RELEASE = 0.97f

        const val MIN_SCALE = 1e-4f

        val REFERENCE_COLOR = ImVec4(0.45f, 0.65f, 0.95f, 1f)

        val CAPTURE_COLOR = ImVec4(0.95f, 0.45f, 0.35f, 1f)

        val OUTPUT_COLOR = ImVec4(0.35f, 0.85f, 0.5f, 1f)
    }
}

/** Samples a window of [millis] holds at AEC3's fixed 48 kHz. */
internal fun windowSamples(millis: Int): Int = millis * SAMPLE_RATE / 1000

/** One sample expressed in milliseconds, for the time axis. */
private const val MILLIS_PER_SAMPLE = 1000.0 / SAMPLE_RATE

/**
 * Formats [value] with [decimals] digits after the point. `String.format` is
 * JVM only, and the example is shared with the native targets.
 */
internal fun fixed(value: Double, decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = round(value * factor).toLong()
    val magnitude = if (scaled < 0) -scaled else scaled
    val whole = magnitude / factor
    val fraction = (magnitude % factor).toString().padStart(decimals, '0')
    return "${if (scaled < 0) "-" else ""}$whole.$fraction"
}