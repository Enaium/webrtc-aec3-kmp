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

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiStyleVar
import cn.enaium.imgui.ImTextureID
import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.backends.sdl.ImGuiSdlBackend
import cn.enaium.imgui.backends.sdl.ImGuiSdlGpuBackend
import cn.enaium.imgui.backends.sdl.ImGuiSdlRendererBackend
import cn.enaium.imgui.extensions.implot.ImPlot
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLGPU
import cn.enaium.sdl.SDLGPUDevice
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLWindow
import cn.enaium.sdl.SDLWindowEventType
import cn.enaium.sdl.SDLWindowFlags

/** Milliseconds between two frame rate reports. */
private const val FRAME_REPORT_MILLIS = 2000u

/**
 * Drives a frame loop: [step] draws one frame and says whether another one is
 * due, [done] runs once it stopped.
 *
 * Desktop and native entry points leave the driver out and get a blocking
 * loop. The browser one hands the loop to `requestAnimationFrame`: a wasm main
 * thread that spins never lets the page paint, so there each frame has to be
 * scheduled and the call returns.
 */
typealias FrameDriver = (step: () -> Boolean, done: () -> Unit) -> Unit

/**
 * Boots an SDL3 window with the GPU API, the published imgui-kmp SDL backends
 * and an ImPlot context, then draws frames calling [draw].
 */
object ImGuiSdlApp {

    /** Font size the layout is designed around, before the host's UI scale. */
    private const val BASE_FONT_SIZE = 13f

    /** Style variables [pushScaledStyle] pushes per frame, and therefore pops. */
    private const val SCALED_STYLE_VARS = 6

    /**
     * A booted window with its ImGui and ImPlot contexts; [step] renders one
     * frame.
     */
    interface Session {

        /**
         * Polls the window's events and renders one frame; returns `false`
         * once the window was closed or its frame bound was reached.
         */
        fun step(draw: (frame: Int) -> Unit): Boolean

        /** Releases the renderer, both contexts, the window and SDL itself. */
        fun close()
    }

    /**
     * Runs until the window is closed or [frames] frames were drawn; returns
     * `false` when no window could be opened at all.
     *
     * `--frames` is passed through from the entry points to bound the run.
     * [dpiScale] is the host's display density (see [uiScale]); [driver]
     * replaces the blocking loop.
     */
    fun run(
        title: String,
        frames: Int = Int.MAX_VALUE,
        dpiScale: Float = 1f,
        driver: FrameDriver? = null,
        draw: (frame: Int) -> Unit,
    ): Boolean {
        val session = open(title, frames, dpiScale) ?: return false
        if (driver == null) {
            try {
                while (session.step(draw)) {
                    // Frames are counted by the session; the bound stops it.
                }
            } finally {
                session.close()
            }
        } else {
            driver({ session.step(draw) }) { session.close() }
        }
        return true
    }

    /** Opens the window and everything that draws into it, or returns `null`. */
    private fun open(title: String, frames: Int, dpiScale: Float): Session? {
        SDL.setMainReady()
        if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
            // No silent fallback to SDL's dummy driver: rendering into an
            // invisible window looks exactly like a broken app. Headless runs
            // set SDL_VIDEO_DRIVER=dummy, which SDL picks up by itself.
            println("SDL_Init failed: ${SDL.error()}$videoInitHint")
            return null
        }
        val version = SDL.version()
        report("SDL ${version.major}.${version.minor}.${version.micro} (${SDL.revision()})")
        report("video driver: ${SDL.getCurrentVideoDriver()}")

        val window = SDL.createWindow(
            title = title,
            width = 1280,
            height = 800,
            flags = SDLWindowFlags.RESIZABLE or SDLWindowFlags.HIGH_PIXEL_DENSITY,
        )
        // Android: a fullscreen SDL window is what makes SDLActivity hide
        // the status and navigation bars that would draw over the UI.
        if (windowStartsFullscreen) window.fullscreen = true

        // SDL_GPU is what the example renders with: it goes through Vulkan,
        // Metal or D3D12 rather than the 2D renderer's GLES path. Where it
        // is not available - a headless CI run on SDL's dummy driver, or a
        // machine with no usable GPU driver - the reason is logged and the
        // 2D renderer takes over, so the example still runs there.
        val device = if (SDLGPU.isSupported) SDLGPU.createDevice() else null
        val gpuDevice = when {
            device == null -> {
                report("SDL_GPU is not supported on this platform; using the SDL renderer backend")
                null
            }

            device.claimWindow(window) -> {
                report("renderer: SDL_GPU [${SDLGPU.drivers.joinToString()}]")
                device
            }

            else -> {
                report("SDL_GPU cannot drive this window (${SDL.error()}); using the SDL renderer backend")
                device.close()
                null
            }
        }

        return try {
            val session = SessionImpl(window, gpuDevice, frames, dpiScale)
            session.init()
            session
        } catch (t: Throwable) {
            gpuDevice?.releaseDrawable(window)
            gpuDevice?.close()
            window.close()
            SDL.quit()
            throw t
        }
    }

    /** Scales the style sizes [SCALED_STYLE_VARS] of a touch UI to [scale]. */
    private fun pushScaledStyle(scale: Float) {
        ImGui.pushStyleVarVec2(ImGuiStyleVar.WINDOW_PADDING, ImVec2(8f * scale, 8f * scale))
        ImGui.pushStyleVarVec2(ImGuiStyleVar.FRAME_PADDING, ImVec2(4f * scale, 3f * scale))
        ImGui.pushStyleVarVec2(ImGuiStyleVar.ITEM_SPACING, ImVec2(8f * scale, 4f * scale))
        ImGui.pushStyleVarVec2(ImGuiStyleVar.ITEM_INNER_SPACING, ImVec2(4f * scale, 4f * scale))
        ImGui.pushStyleVarFloat(ImGuiStyleVar.SCROLLBAR_SIZE, 14f * scale)
        ImGui.pushStyleVarFloat(ImGuiStyleVar.GRAB_MIN_SIZE, 10f * scale)
    }

    /** The session [open] hands out: window plus contexts plus frame loop. */
    private class SessionImpl(
        private val window: SDLWindow,
        private val gpuDevice: SDLGPUDevice?,
        private val frames: Int,
        private val dpiScale: Float,
    ) : Session {

        private var destroyImGuiContext: (() -> Unit)? = null
        private var destroyPlotContext: (() -> Unit)? = null
        private var frameBackend: FrameBackend? = null
        private var platform: ImGuiSdlBackend? = null

        private var running = true
        private var frame = 0
        private var lastReport = 0uL
        private var framesAtReport = 0
        private var drawMillis = 0.0
        private var renderMillis = 0.0
        private var buildMillis = 0.0

        fun init() {
            val context = ImGui.createContext()
            destroyImGuiContext = { ImGui.destroyContext(context) }
            val platform = ImGuiSdlBackend(window)
            this.platform = platform
            platform.init()
            report(
                "ui scale: $dpiScale, window ${window.sizeInPixels.x.toInt()}x" +
                    "${window.sizeInPixels.y.toInt()} px",
            )
            val backend = if (gpuDevice != null) {
                GpuFrameBackend(gpuDevice, window, platform)
            } else {
                RendererFrameBackend(window)
            }
            frameBackend = backend
            // Bake the font at the host's UI scale - the display density on
            // Android, none on desktop - and at the display's pixel density
            // on top of that, so a 2712 px phone gets touch sized text and a
            // Retina screen crisp glyphs.
            val fonts = ImGui.getIO().fonts
            val density = maxOf(platform.framebufferScale.x, platform.framebufferScale.y, 1f)
            fonts.addFontDefault(
                ImFontConfig(
                    sizePixels = BASE_FONT_SIZE * dpiScale * density,
                    rasterizerDensity = density,
                ),
            )
            check(fonts.build()) { "font atlas build failed" }
            val texData = fonts.getTexDataAsRGBA32()
            fonts.setTexID(backend.uploadFontTexture(texData.pixels, texData.width, texData.height))

            // ImPlot keeps its own context, bound to the imgui one.
            val plot = ImPlot.createContext()
            destroyPlotContext = { ImPlot.destroyContext(plot) }
            ImPlot.setImGuiContext(ImGui.getCurrentContext() ?: error("no imgui context"))

            lastReport = SDL.getTicks()
        }

        override fun step(draw: (frame: Int) -> Unit): Boolean {
            if (!running) return false
            val platform = platform ?: return false
            val frameBackend = frameBackend ?: return false

            while (true) {
                val event = SDL.pollEvent() ?: break
                when (event) {
                    is SDLEvent.Quit -> running = false
                    is SDLEvent.Window ->
                        if (event.type == SDLWindowEventType.CLOSE_REQUESTED) running = false

                    else -> platform.processEvent(event)
                }
            }
            if (!running) return false

            val t0 = SDL.getTicks()
            platform.newFrame()
            // The style sizes (padding, spacing, scrollbars) are in the same
            // units as the font, so they grow with it; the binding has no
            // Style.ScaleAllSizes, so the ones a touch UI cares about are
            // pushed for this frame.
            if (dpiScale != 1f) pushScaledStyle(dpiScale)
            draw(frame)
            if (dpiScale != 1f) ImGui.popStyleVar(SCALED_STYLE_VARS)
            val t1 = SDL.getTicks()
            ImGui.render()
            val t2 = SDL.getTicks()
            frameBackend.render()
            val t3 = SDL.getTicks()
            frame++
            drawMillis += (t1 - t0).toDouble()
            buildMillis += (t2 - t1).toDouble()
            renderMillis += (t3 - t2).toDouble()

            // The frame rate is what tells whether the render loop is
            // still doing anything but drawing.
            val now = SDL.getTicks()
            if (now - lastReport >= FRAME_REPORT_MILLIS) {
                val elapsed = (now - lastReport).toDouble() / 1000.0
                val count = (frame - framesAtReport).toDouble()
                report(
                    "render: ${fixed(count / elapsed, 1)} fps, draw ${fixed(drawMillis / count, 1)} ms, " +
                        "imgui ${fixed(buildMillis / count, 1)} ms, present ${fixed(renderMillis / count, 1)} ms",
                )
                framesAtReport = frame
                drawMillis = 0.0
                renderMillis = 0.0
                buildMillis = 0.0
                lastReport = now
            }
            return frame < frames
        }

        override fun close() {
            if (frameBackend == null && destroyImGuiContext == null) return
            destroyPlotContext?.invoke()
            destroyPlotContext = null
            frameBackend?.close()
            frameBackend = null
            platform = null
            destroyImGuiContext?.invoke()
            destroyImGuiContext = null
            gpuDevice?.releaseDrawable(window)
            gpuDevice?.close()
            window.close()
            SDL.quit()
        }
    }

    /** Presents ImGui frames with one of SDL's rendering paths. */
    private interface FrameBackend {

        /** Uploads the baked font atlas and returns the texture to draw it with. */
        fun uploadFontTexture(pixels: ByteArray, width: Int, height: Int): ImTextureID

        /** Draws and presents the frame the render loop just produced. */
        fun render()

        /** Releases everything the backend owns. */
        fun close()
    }

    /**
     * SDL_GPU: one command buffer per frame, drawing into the window's
     * swapchain texture with the imgui-kmp SDL GPU backend.
     */
    private class GpuFrameBackend(
        private val device: SDLGPUDevice,
        private val window: SDLWindow,
        private val platform: ImGuiSdlBackend,
    ) : FrameBackend {

        private val backend = ImGuiSdlGpuBackend(device, window)

        override fun uploadFontTexture(pixels: ByteArray, width: Int, height: Int): ImTextureID =
            backend.uploadFontTexture(pixels, width, height)

        override fun render() {
            val commandBuffer = device.beginCommandBuffer()
            if (commandBuffer == null) {
                println("SDL_AcquireGPUCommandBuffer failed: ${SDL.error()}")
                return
            }
            val swapchain = device.acquireSwapchainTexture(commandBuffer, window)
            val target = swapchain?.texture
            if (target != null) {
                val size = platform.sizeInPixels
                backend.renderFrame(commandBuffer, target, size.x.toInt(), size.y.toInt())
            }
            // A missing swapchain texture (hidden window, reconfiguring
            // swapchain) still needs the command buffer to be submitted.
            device.submit(commandBuffer)
        }

        override fun close() {
            backend.close()
        }
    }

    /** SDL's 2D renderer, used where SDL_GPU is not available. */
    private class RendererFrameBackend(window: SDLWindow) : FrameBackend {

        private val renderer = SDL.createRenderer(window)

        private val backend = ImGuiSdlRendererBackend(renderer)

        override fun uploadFontTexture(pixels: ByteArray, width: Int, height: Int): ImTextureID =
            backend.uploadFontTexture(pixels, width, height)

        override fun render() {
            renderer.drawColor = SDLColor(18, 18, 24)
            renderer.clear()
            backend.renderDrawData(ImGui.getDrawData())
            renderer.present()
        }

        override fun close() {
            backend.close()
            renderer.close()
        }
    }
}
