cmake_minimum_required(VERSION 3.16)

project(webrtc_aec3_wasm C CXX)
set(CMAKE_CXX_STANDARD 20)

# Builds the static library `:aec3:linkWasmAec3` links into the Emscripten
# module. The Gradle tasks configure this file with the Emscripten toolchain,
# so no platform branch is needed here.
set(WEBRTC_AEC3_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../webrtc-aec3)
set(C_API_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../c_api)

# The link task reads build/wasmAec3/webrtc-aec3/libwebrtc_aec3.a.
set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/webrtc-aec3)

add_library(webrtc_aec3 STATIC)

target_compile_options(webrtc_aec3 PRIVATE
    -Wall
    -Wno-deprecated
    -fexceptions
)

# wasm32 has no thread-local Linux APIs and no prctl; the POSIX paths cover
# what the library uses (rtc_base/system/arch.h already has an __EMSCRIPTEN__
# branch).
target_compile_definitions(webrtc_aec3 PRIVATE WEBRTC_POSIX)

file(GLOB_RECURSE AEC3_SRC
    ${WEBRTC_AEC3_DIR}/src/**/*.cc
    ${WEBRTC_AEC3_DIR}/src/**/*.c
    ${WEBRTC_AEC3_DIR}/src/**/*.cpp
)

# wasm32 uses the scalar implementations: emcc rejects -msse/-mavx2 without
# -msimd128, and arm_neon.h is not available.
list(FILTER AEC3_SRC EXCLUDE REGEX ".*_avx2\\.cc$")
list(FILTER AEC3_SRC EXCLUDE REGEX ".*_sse2\\.cc$")
list(FILTER AEC3_SRC EXCLUDE REGEX ".*_sse\\.cc$")
list(FILTER AEC3_SRC EXCLUDE REGEX ".*_neon\\.cc$")

target_sources(webrtc_aec3 PRIVATE ${AEC3_SRC})
target_sources(webrtc_aec3 PRIVATE
    ${C_API_DIR}/webrtc_aec3_c.cc
    ${C_API_DIR}/webrtc_aec3_c.h
)

target_include_directories(webrtc_aec3 PUBLIC
    ${WEBRTC_AEC3_DIR}/src/
    ${WEBRTC_AEC3_DIR}/src/api
    ${WEBRTC_AEC3_DIR}/src/base/rtc_base
    ${WEBRTC_AEC3_DIR}/src/base
    ${WEBRTC_AEC3_DIR}/src/base/system_wrappers/include
    ${WEBRTC_AEC3_DIR}/src/audio_processing
    ${WEBRTC_AEC3_DIR}/src/absl
    ${WEBRTC_AEC3_DIR}/src/base/jsoncpp/include
    ${WEBRTC_AEC3_DIR}/src/audio_processing/aec3/
    ${WEBRTC_AEC3_DIR}/src/audio_processing/resampler
    ${WEBRTC_AEC3_DIR}/src/audio_processing/logging
    ${WEBRTC_AEC3_DIR}/src/audio_processing/include
    ${WEBRTC_AEC3_DIR}/src/audio_processing/utility
    ${WEBRTC_AEC3_DIR}/src/rtc_base/experiments
    ${WEBRTC_AEC3_DIR}/src/common_audio
    ${WEBRTC_AEC3_DIR}/src/common_audio/third_party/ooura/fft_size_128
    ${C_API_DIR}
)

set_target_properties(webrtc_aec3 PROPERTIES LINKER_LANGUAGE CXX)
