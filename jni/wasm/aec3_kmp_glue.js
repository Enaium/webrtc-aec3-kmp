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
 * Glue between the Emscripten-compiled AEC3 module (aec3_wasm.js) and
 * Kotlin/Wasm. Instantiates the module and exposes the functions the wasmJs
 * actuals call as globals, the way the sdl-kmp glue does.
 *
 * Conventions:
 *   - handles are plain numbers (wasm32 pointers);
 *   - strings are allocated with malloc and freed here;
 *   - float arrays are copied through the module heap, in and out;
 *   - metrics come back as a Float32Array, because the C struct cannot be
 *     returned by value across the JS boundary (see aec3_wasm_shim.cpp).
 *
 * The host page loads this module and awaits initAec3Kmp() BEFORE the Kotlin
 * module runs, so these globals always exist.
 */

export async function initAec3Kmp() {
    const mod = await import('./aec3_wasm.js');
    const load = typeof mod.default === 'function' ? mod.default : mod;
    const Module = await load({});

    const UTF8 = Module.UTF8ToString;
    const malloc = Module._malloc;
    const free = Module._free;

    const allocStr = (s) => {
        const bytes = Module.lengthBytesUTF8(s) + 1;
        const p = malloc(bytes);
        Module.stringToUTF8(s, p, bytes);
        return p;
    };
    const readCString = (p) => (p ? UTF8(p) : null);

    /* Scratch slots for the scalar out-parameters of the C API. */
    const flag = malloc(4);
    const delayMs = malloc(4);
    const echoReturnLoss = malloc(8);
    const echoReturnLossEnhancement = malloc(8);
    const METRICS = new Float32Array(3);

    /* Direct exports: handles and scalars pass straight through. */
    const ret = (name) => { globalThis[name] = Module['_' + name]; };
    ret('webrtc_aec3_config_create_default');
    ret('webrtc_aec3_config_create_default_multichannel');
    ret('webrtc_aec3_config_clone');
    ret('webrtc_aec3_config_destroy');
    ret('webrtc_aec3_config_validate');
    ret('webrtc_aec3_config_set_delay_default_delay');
    ret('webrtc_aec3_config_set_filter_initial_state_seconds');
    ret('webrtc_aec3_config_set_filter_conservative_initial_phase');
    ret('webrtc_aec3_environment_create');
    ret('webrtc_aec3_environment_destroy');
    ret('webrtc_aec3_audio_buffer_create');
    ret('webrtc_aec3_audio_buffer_destroy');
    ret('webrtc_aec3_audio_buffer_get_channel_data');
    ret('webrtc_aec3_audio_buffer_num_channels');
    ret('webrtc_aec3_audio_buffer_samples_per_channel');
    ret('webrtc_aec3_factory_create');
    ret('webrtc_aec3_factory_create_with_config');
    ret('webrtc_aec3_factory_destroy');
    ret('webrtc_aec3_echo_control_create');
    ret('webrtc_aec3_echo_control_destroy');
    ret('webrtc_aec3_echo_control_analyze_render');
    ret('webrtc_aec3_echo_control_analyze_capture');
    ret('webrtc_aec3_echo_control_process_capture');
    ret('webrtc_aec3_echo_control_set_audio_buffer_delay');

    /* ---- version / strings ---- */
    globalThis.webrtc_aec3_version = () => readCString(Module._webrtc_aec3_version());

    globalThis.webrtc_aec3_config_to_json = (config) => {
        const p = Module._webrtc_aec3_config_to_json(config);
        const json = readCString(p);
        Module._webrtc_aec3_free_string(p);
        return json;
    };

    globalThis.webrtc_aec3_config_from_json = (json) => {
        const p = allocStr(json);
        const config = Module._webrtc_aec3_config_from_json(p, flag);
        free(p);
        return Module.HEAP32[flag >> 2] ? config : 0;
    };

    /* ---- metrics ---- */
    globalThis.aec3KmpGetMetrics = (control) => {
        Module._aec3_wasm_get_metrics(control, delayMs, echoReturnLoss, echoReturnLossEnhancement);
        METRICS[0] = Module.HEAP32[delayMs >> 2];
        METRICS[1] = Module.HEAPF64[echoReturnLoss >> 3];
        METRICS[2] = Module.HEAPF64[echoReturnLossEnhancement >> 3];
        return METRICS;
    };

    /* Typed arrays have no element accessors in Kotlin/Wasm, so reads go
     * through a function (the sdl-kmp glue does the same). */
    globalThis.aec3KmpFloat32Get = (array, index) => array[index];

    /* ---- channel data ---- */
    globalThis.aec3KmpWriteChannel = (buffer, channel, data) => {
        const ptr = Module._webrtc_aec3_audio_buffer_get_channel_data(buffer, channel);
        if (!ptr) return;
        const samples = Module._webrtc_aec3_audio_buffer_samples_per_channel(buffer);
        const count = Math.min(samples, data.length);
        Module.HEAPF32.subarray(ptr >> 2, (ptr >> 2) + count).set(data.subarray(0, count));
    };

    globalThis.aec3KmpReadChannel = (buffer, channel) => {
        const ptr = Module._webrtc_aec3_audio_buffer_get_channel_data(buffer, channel);
        if (!ptr) return new Float32Array(0);
        const samples = Module._webrtc_aec3_audio_buffer_samples_per_channel(buffer);
        // A copy: the heap view dies with the next allocation, and the caller
        // keeps this array.
        return new Float32Array(Module.HEAPF32.subarray(ptr >> 2, (ptr >> 2) + samples));
    };

    return Module;
}
