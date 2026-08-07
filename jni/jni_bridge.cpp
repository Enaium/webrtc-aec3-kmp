/*
 *  Copyright (c) 2025 The WebRTC AEC3 Java project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree.
 */

#include <jni.h>
#include "webrtc_aec3_c.h"

// ============================================================================
// Config
// ============================================================================

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_webrtc_aec3_Jni_configCreateDefault(JNIEnv* env, jclass clazz) {
    auto* config = webrtc_aec3_config_create_default();
    return reinterpret_cast<jlong>(config);
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_aec3_Jni_configDestroy(JNIEnv* env, jclass clazz, jlong ptr) {
    auto* config = reinterpret_cast<webrtc_aec3_config_t*>(ptr);
    webrtc_aec3_config_destroy(config);
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_aec3_Jni_configSetDelayDefaultDelay(JNIEnv* env, jclass clazz, jlong ptr, jlong value) {
    auto* config = reinterpret_cast<webrtc_aec3_config_t*>(ptr);
    webrtc_aec3_config_set_delay_default_delay(config, static_cast<size_t>(value));
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_aec3_Jni_configSetFilterInitialStateSeconds(JNIEnv* env, jclass clazz, jlong ptr, jfloat value) {
    auto* config = reinterpret_cast<webrtc_aec3_config_t*>(ptr);
    webrtc_aec3_config_set_filter_initial_state_seconds(config, value);
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_aec3_Jni_configSetFilterConservativeInitialPhase(JNIEnv* env, jclass clazz, jlong ptr, jboolean value) {
    auto* config = reinterpret_cast<webrtc_aec3_config_t*>(ptr);
    webrtc_aec3_config_set_filter_conservative_initial_phase(config, value != JNI_FALSE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_enaium_webrtc_aec3_Jni_configValidate(JNIEnv* env, jclass clazz, jlong ptr) {
    auto* config = reinterpret_cast<webrtc_aec3_config_t*>(ptr);
    return webrtc_aec3_config_validate(config) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_cn_enaium_webrtc_aec3_Jni_configToJson(JNIEnv* env, jclass clazz, jlong ptr) {
    auto* config = reinterpret_cast<webrtc_aec3_config_t*>(ptr);
    char* json = webrtc_aec3_config_to_json(config);
    jstring result = env->NewStringUTF(json);
    webrtc_aec3_free_string(json);
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_webrtc_aec3_Jni_configFromJson(JNIEnv* env, jclass clazz, jstring jsonStr) {
    const char* jsonChars = env->GetStringUTFChars(jsonStr, nullptr);
    bool parsing_successful = false;
    auto* config = webrtc_aec3_config_from_json(jsonChars, &parsing_successful);
    env->ReleaseStringUTFChars(jsonStr, jsonChars);
    if (!parsing_successful && config) {
        webrtc_aec3_config_destroy(config);
        return 0;
    }
    return reinterpret_cast<jlong>(config);
}

// ============================================================================
// Environment
// ============================================================================

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_webrtc_aec3_Jni_environmentCreate(JNIEnv* env, jclass clazz) {
    auto* env_obj = webrtc_aec3_environment_create();
    return reinterpret_cast<jlong>(env_obj);
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_aec3_Jni_environmentDestroy(JNIEnv* env, jclass clazz, jlong ptr) {
    auto* env_obj = reinterpret_cast<webrtc_aec3_environment_t*>(ptr);
    webrtc_aec3_environment_destroy(env_obj);
}

// ============================================================================
// Factory
// ============================================================================

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_webrtc_aec3_Jni_factoryCreate(JNIEnv* env, jclass clazz) {
    auto* factory = webrtc_aec3_factory_create();
    return reinterpret_cast<jlong>(factory);
}

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_webrtc_aec3_Jni_factoryCreateWithConfig(JNIEnv* env, jclass clazz, jlong configPtr) {
    auto* config = reinterpret_cast<webrtc_aec3_config_t*>(configPtr);
    auto* factory = webrtc_aec3_factory_create_with_config(config);
    return reinterpret_cast<jlong>(factory);
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_aec3_Jni_factoryDestroy(JNIEnv* env, jclass clazz, jlong ptr) {
    auto* factory = reinterpret_cast<webrtc_aec3_factory_t*>(ptr);
    webrtc_aec3_factory_destroy(factory);
}

// ============================================================================
// EchoControl
// ============================================================================

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_webrtc_aec3_Jni_echoControlCreate(JNIEnv* env, jclass clazz, jlong factoryPtr, jlong envPtr, jint sampleRate, jint renderChannels, jint captureChannels) {
    auto* factory = reinterpret_cast<webrtc_aec3_factory_t*>(factoryPtr);
    auto* env_obj = reinterpret_cast<webrtc_aec3_environment_t*>(envPtr);
    auto* ec = webrtc_aec3_echo_control_create(factory, env_obj, sampleRate, renderChannels, captureChannels);
    return reinterpret_cast<jlong>(ec);
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_aec3_Jni_echoControlDestroy(JNIEnv* env, jclass clazz, jlong ptr) {
    auto* ec = reinterpret_cast<webrtc_aec3_echo_control_t*>(ptr);
    webrtc_aec3_echo_control_destroy(ec);
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_aec3_Jni_echoControlAnalyzeRender(JNIEnv* env, jclass clazz, jlong ecPtr, jlong bufferPtr) {
    auto* ec = reinterpret_cast<webrtc_aec3_echo_control_t*>(ecPtr);
    auto* buffer = reinterpret_cast<webrtc_aec3_audio_buffer_t*>(bufferPtr);
    webrtc_aec3_echo_control_analyze_render(ec, buffer);
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_aec3_Jni_echoControlAnalyzeCapture(JNIEnv* env, jclass clazz, jlong ecPtr, jlong bufferPtr) {
    auto* ec = reinterpret_cast<webrtc_aec3_echo_control_t*>(ecPtr);
    auto* buffer = reinterpret_cast<webrtc_aec3_audio_buffer_t*>(bufferPtr);
    webrtc_aec3_echo_control_analyze_capture(ec, buffer);
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_aec3_Jni_echoControlProcessCapture(JNIEnv* env, jclass clazz, jlong ecPtr, jlong bufferPtr, jboolean levelChange) {
    auto* ec = reinterpret_cast<webrtc_aec3_echo_control_t*>(ecPtr);
    auto* buffer = reinterpret_cast<webrtc_aec3_audio_buffer_t*>(bufferPtr);
    webrtc_aec3_echo_control_process_capture(ec, buffer, levelChange != JNI_FALSE);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_cn_enaium_webrtc_aec3_Jni_echoControlGetMetrics(JNIEnv* env, jclass clazz, jlong ecPtr) {
    auto* ec = reinterpret_cast<webrtc_aec3_echo_control_t*>(ecPtr);
    webrtc_aec3_metrics_t metrics = webrtc_aec3_echo_control_get_metrics(ec);
    jdouble values[3] = {
        metrics.echo_return_loss,
        metrics.echo_return_loss_enhancement,
        static_cast<jdouble>(metrics.delay_ms)
    };
    jdoubleArray result = env->NewDoubleArray(3);
    env->SetDoubleArrayRegion(result, 0, 3, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_aec3_Jni_echoControlSetAudioBufferDelay(JNIEnv* env, jclass clazz, jlong ecPtr, jint delayMs) {
    auto* ec = reinterpret_cast<webrtc_aec3_echo_control_t*>(ecPtr);
    webrtc_aec3_echo_control_set_audio_buffer_delay(ec, delayMs);
}

// ============================================================================
// AudioBuffer
// ============================================================================

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_webrtc_aec3_Jni_audioBufferCreate(JNIEnv* env, jclass clazz, jint sampleRate, jint channels) {
    auto* buffer = webrtc_aec3_audio_buffer_create(sampleRate, channels);
    return reinterpret_cast<jlong>(buffer);
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_aec3_Jni_audioBufferDestroy(JNIEnv* env, jclass clazz, jlong ptr) {
    auto* buffer = reinterpret_cast<webrtc_aec3_audio_buffer_t*>(ptr);
    webrtc_aec3_audio_buffer_destroy(buffer);
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_aec3_Jni_audioBufferWriteChannel(JNIEnv* env, jclass clazz, jlong ptr, jint channel, jfloatArray data) {
    auto* buffer = reinterpret_cast<webrtc_aec3_audio_buffer_t*>(ptr);
    jfloat* elements = env->GetFloatArrayElements(data, nullptr);
    jsize len = env->GetArrayLength(data);
    float* dest = webrtc_aec3_audio_buffer_get_channel_data(buffer, channel);
    for (jsize i = 0; i < len; ++i) {
        dest[i] = elements[i];
    }
    env->ReleaseFloatArrayElements(data, elements, JNI_ABORT);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_cn_enaium_webrtc_aec3_Jni_audioBufferReadChannel(JNIEnv* env, jclass clazz, jlong ptr, jint channel) {
    auto* buffer = reinterpret_cast<webrtc_aec3_audio_buffer_t*>(ptr);
    int samples = webrtc_aec3_audio_buffer_samples_per_channel(buffer);
    const float* src = webrtc_aec3_audio_buffer_get_channel_data_const(buffer, channel);
    jfloatArray result = env->NewFloatArray(samples);
    env->SetFloatArrayRegion(result, 0, samples, src);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webrtc_aec3_Jni_audioBufferNumChannels(JNIEnv* env, jclass clazz, jlong ptr) {
    auto* buffer = reinterpret_cast<webrtc_aec3_audio_buffer_t*>(ptr);
    return webrtc_aec3_audio_buffer_num_channels(buffer);
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webrtc_aec3_Jni_audioBufferSamplesPerChannel(JNIEnv* env, jclass clazz, jlong ptr) {
    auto* buffer = reinterpret_cast<webrtc_aec3_audio_buffer_t*>(ptr);
    return webrtc_aec3_audio_buffer_samples_per_channel(buffer);
}

// ============================================================================
// Version
// ============================================================================

extern "C" JNIEXPORT jstring JNICALL
Java_cn_enaium_webrtc_aec3_Jni_version(JNIEnv* env, jclass clazz) {
    return env->NewStringUTF(webrtc_aec3_version());
}