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

/*
 * Emscripten shim for the one part of the AEC3 C API the wasm ABI cannot
 * expose directly.
 *
 * `webrtc_aec3_echo_control_get_metrics` returns a struct by value, which the
 * wasm ABI returns through a hidden sret pointer that a JS export cannot name.
 * This copies the three fields into plain out-parameters instead; the glue
 * turns those into a Float32Array the Kotlin side reads.
 */

#include "webrtc_aec3_c.h"

extern "C" {

void aec3_wasm_get_metrics(
    webrtc_aec3_echo_control_t* ec,
    int* delay_ms,
    double* echo_return_loss,
    double* echo_return_loss_enhancement) {
    const webrtc_aec3_metrics_t metrics = webrtc_aec3_echo_control_get_metrics(ec);
    if (delay_ms != nullptr) {
        *delay_ms = metrics.delay_ms;
    }
    if (echo_return_loss != nullptr) {
        *echo_return_loss = metrics.echo_return_loss;
    }
    if (echo_return_loss_enhancement != nullptr) {
        *echo_return_loss_enhancement = metrics.echo_return_loss_enhancement;
    }
}

}  // extern "C"
