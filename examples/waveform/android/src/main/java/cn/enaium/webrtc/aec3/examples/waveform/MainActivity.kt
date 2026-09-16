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

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import org.libsdl.app.SDLActivity

/**
 * Launcher activity hosting the waveform example.
 *
 * SDLActivity loads the app's shared libraries and calls the exported `SDL_main`
 * symbol of `libmain.so` on a dedicated SDL thread. That library links SDL3,
 * ImGui/ImPlot, WebRTC AEC3 and the audio backend statically.
 *
 * The microphone permission is requested before SDL starts; the native side
 * opens the capture device with a retry, so granting it a moment later still
 * ends up in a running pipeline. The system bars are hidden by SDL itself once
 * the example puts its window in fullscreen.
 */
class MainActivity : SDLActivity() {

    /**
     * `libmain.so` references the C++ runtime without declaring it as a
     * dependency of its own, so `libc++_shared` is loaded first: with only
     * `main` the dynamic linker cannot resolve those symbols and SDL reports
     * `dlopen failed: cannot locate symbol _ZTTNSt6__ndk1...`.
     */
    override fun getLibraries(): Array<String> = arrayOf("c++_shared", "main")

    /**
     * The UI scale the example lays its font, style and fixed sizes out for: the
     * display density, which is what makes the controls touch sized on a phone.
     *
     * Implemented in `libmain.so`; see `Main.androidNative.kt`.
     */
    private external fun nativeSetUiScale(scale: Float)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The scale has to be reported before SDLActivity starts the SDL thread,
        // which bakes the font at it, so the libraries are loaded here first -
        // the same two, in the same order, as getLibraries asks SDL for.
        System.loadLibrary("c++_shared")
        System.loadLibrary("main")
        nativeSetUiScale(resources.displayMetrics.density)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST)
        }
        super.onCreate(savedInstanceState)
    }

    /**
     * The waveform wants the wider axis.
     *
     * SDL derives the requested orientation from the window size and the
     * (absent) `SDL_ORIENTATIONS` hint, which for a resizable window leaves it
     * at "full user" - i.e. whatever the device currently is. Both landscape
     * directions are allowed so the device can still be flipped.
     */
    override fun setOrientationBis(w: Int, h: Int, resizable: Boolean, hint: String?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private companion object {
        const val RECORD_AUDIO_REQUEST = 1
    }
}