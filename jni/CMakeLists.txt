cmake_minimum_required(VERSION 3.16)

project(webrtc_aec3 C CXX)
set(CMAKE_CXX_STANDARD 20)

# Builds the JNI shared library by default. Native (cinterop) targets build
# only the static library by passing -DBUILD_JNI=OFF.
option(BUILD_JNI "Build the JNI shared library" ON)

# ---- webrtc-aec3 submodule + C API ----
set(WEBRTC_AEC3_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../webrtc-aec3)
set(C_API_DIR ${CMAKE_CURRENT_SOURCE_DIR}/c_api)

add_library(webrtc_aec3 STATIC)

if(MSVC)
    target_compile_options(webrtc_aec3 PRIVATE /W3 /EHsc /bigobj)
else()
    target_compile_options(webrtc_aec3 PRIVATE -Wall -fPIC -Wno-deprecated -fexceptions)
endif()

if(ANDROID)
    target_compile_definitions(webrtc_aec3 PRIVATE WEBRTC_ANDROID WEBRTC_POSIX)
    if(ANDROID_ABI STREQUAL "x86")
        target_compile_options(webrtc_aec3 PRIVATE -msse2)
    endif()
    target_include_directories(webrtc_aec3 PRIVATE ${ANDROID_NDK}/sources/android/cpufeatures)
elseif(APPLE)
    target_compile_definitions(webrtc_aec3 PRIVATE WEBRTC_MAC WEBRTC_POSIX)
elseif(WIN32)
    target_compile_definitions(webrtc_aec3 PRIVATE WEBRTC_WIN)
    if(MINGW)
        target_compile_options(webrtc_aec3 PRIVATE -mno-ms-bitfields)
        # MinGW-w64 cross-compilers don't add their sysroot include dir to
        # the default search path; add it explicitly so Windows.h and the
        # mingw-w64 libc headers resolve.
        target_include_directories(webrtc_aec3 SYSTEM PRIVATE /usr/x86_64-w64-mingw32/include)
    endif()
elseif(UNIX AND NOT APPLE)
    target_compile_definitions(webrtc_aec3 PRIVATE WEBRTC_LINUX WEBRTC_POSIX)
endif()

file(GLOB_RECURSE AEC3_SRC
    ${WEBRTC_AEC3_DIR}/src/**/*.cc
    ${WEBRTC_AEC3_DIR}/src/**/*.c
    ${WEBRTC_AEC3_DIR}/src/**/*.cpp
    ${WEBRTC_AEC3_DIR}/src/**/*.h
)

message(STATUS "CMAKE_SYSTEM_PROCESSOR: ${CMAKE_SYSTEM_PROCESSOR}")
message(STATUS "ANDROID_ABI: ${ANDROID_ABI}")

# On Apple, CMAKE_SYSTEM_PROCESSOR reflects the host even when cross-compiling
# via CMAKE_OSX_ARCHITECTURES, so derive the effective arch from it first.
if(APPLE AND CMAKE_OSX_ARCHITECTURES)
    set(EFFECTIVE_ARCH ${CMAKE_OSX_ARCHITECTURES})
else()
    set(EFFECTIVE_ARCH ${CMAKE_SYSTEM_PROCESSOR})
endif()

message(STATUS "EFFECTIVE_ARCH: ${EFFECTIVE_ARCH}")

if(NOT (EFFECTIVE_ARCH MATCHES "x86_64|AMD64|amd64|i686|x86"))
    # Non-x86: exclude AVX2, SSE2, and SSE files (not available on ARM)
    list(FILTER AEC3_SRC EXCLUDE REGEX ".*_avx2\\.cc$")
    list(FILTER AEC3_SRC EXCLUDE REGEX ".*_sse2\\.cc$")
    list(FILTER AEC3_SRC EXCLUDE REGEX ".*_sse\\.cc$")
else()
    # x86/x86_64: include SSE2 and SSE, need -mavx2 -mfma (GCC/Clang) or
    # /arch:AVX2 (MSVC) for AVX2 files
    file(GLOB_RECURSE AEC3_AVX2_SRC ${WEBRTC_AEC3_DIR}/src/**/*_avx2.cc)
    if(AEC3_AVX2_SRC)
        if(MSVC)
            set_source_files_properties(${AEC3_AVX2_SRC} PROPERTIES COMPILE_FLAGS "/arch:AVX2")
        else()
            set_source_files_properties(${AEC3_AVX2_SRC} PROPERTIES COMPILE_FLAGS "-mavx2 -mfma")
        endif()
    endif()
endif()

# Exclude NEON files on non-ARM platforms (they include arm_neon.h)
if(NOT (EFFECTIVE_ARCH MATCHES "aarch64|arm64|ARM64"))
    list(FILTER AEC3_SRC EXCLUDE REGEX ".*_neon\\.cc$")
endif()

if(ANDROID)
    list(FILTER AEC3_SRC EXCLUDE REGEX "cpu_features_linux\\.c$")
endif()

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

# ---- JNI shared library ----
if(BUILD_JNI)
    add_library(webrtc_aec3_jni SHARED ${CMAKE_CURRENT_SOURCE_DIR}/jni_bridge.cpp)
    set_target_properties(webrtc_aec3_jni PROPERTIES OUTPUT_NAME webrtc_aec3_jni)
    if(WIN32)
        # MinGW prefixes DLLs with "lib"; strip it so the artifact is
        # webrtc_aec3_jni.dll everywhere (NativeLoader expects no prefix).
        set_target_properties(webrtc_aec3_jni PROPERTIES PREFIX "")
    endif()

    if(ANDROID)
        find_library(log-lib log)
        if(log-lib)
            target_link_libraries(webrtc_aec3_jni PRIVATE ${log-lib})
        endif()
    elseif(JNI_INCLUDE_DIR)
        target_include_directories(webrtc_aec3_jni PRIVATE ${JNI_INCLUDE_DIR})
        if(JNI_INCLUDE_DIR_PLATFORM)
            target_include_directories(webrtc_aec3_jni PRIVATE ${JNI_INCLUDE_DIR_PLATFORM})
        endif()
    else()
        find_package(JNI REQUIRED)
        target_include_directories(webrtc_aec3_jni PRIVATE ${JNI_INCLUDE_DIRS})
    endif()

    target_include_directories(webrtc_aec3_jni PRIVATE ${C_API_DIR})
    target_link_libraries(webrtc_aec3_jni PRIVATE webrtc_aec3)

    if(APPLE)
        target_link_libraries(webrtc_aec3_jni PRIVATE "-framework CoreFoundation")
    elseif(WIN32)
        # timeGetTime() used by rtc_base/time_utils.cc
        target_link_libraries(webrtc_aec3_jni PRIVATE winmm)
    endif()
endif()
