# webrtc-aec3-kmp

[![Maven Central](https://img.shields.io/maven-central/v/cn.enaium.webrtc.aec3/webrtc-aec3-kmp?label=Maven%20Central)](https://central.sonatype.com/artifact/cn.enaium.webrtc.aec3/webrtc-aec3-kmp)
[![License](https://img.shields.io/github/license/Enaium/webrtc-aec3-kmp)](https://github.com/Enaium/webrtc-aec3-kmp/blob/main/LICENSE)
[![GitHub Actions](https://img.shields.io/github/actions/workflow/status/Enaium/webrtc-aec3-kmp/test.yml?label=test)](https://github.com/Enaium/webrtc-aec3-kmp/actions/workflows/test.yml)
[![GitHub Repo stars](https://img.shields.io/github/stars/Enaium/webrtc-aec3-kmp?style=social)](https://github.com/Enaium/webrtc-aec3-kmp)

Kotlin Multiplatform bindings for the [WebRTC AEC3](https://github.com/Enaium/webrtc-aec3) echo canceller — a high-performance acoustic echo cancellation library extracted from WebRTC. The AEC3 algorithm removes the acoustic echo of the far-end (render) signal from the near-end (capture/microphone) signal in real time.

## Supported Platforms

| Platform       | Targets                                                     | Mechanism                                  |
| -------------- | ----------------------------------------------------------- | ------------------------------------------ |
| **Android**    | arm64-v8a, armeabi-v7a, x86, x86_64                          | JNI (shared library via CMake)             |
| **JVM**        | Linux x86_64/aarch64, macOS arm64/x86_64, Windows x86_64     | JNI (per-OS/arch JAR resource, auto-extracted by `NativeLoader`) |
| **iOS**        | arm64, x64, simulatorArm64                                   | Kotlin/Native cinterop (static library)    |
| **macOS**      | arm64, x86_64                                                | Kotlin/Native cinterop (static library)    |
| **Linux**      | x86_64                                                       | Kotlin/Native cinterop (static library)    |
| **Windows**    | mingwX64                                                     | Kotlin/Native cinterop (bindings)          |
| **tvOS**       | arm64, simulatorArm64                                        | Kotlin/Native cinterop (static library)    |
| **watchOS**    | arm64, simulatorArm64, deviceArm64                           | Kotlin/Native cinterop (static library)    |

## Gradle Dependency

**Kotlin Multiplatform / Android:**

```kotlin
implementation("cn.enaium.webrtc.aec3:webrtc-aec3-kmp:1.0.0")
```

**JVM:** the right native binary is resolved automatically — the `webrtc-aec3-kmp-jvm` artifact pulls in the matching `:jni-jvm-*` sibling on the classpath:

- `webrtc-aec3-kmp-jni-jvm-linux-x86_64`
- `webrtc-aec3-kmp-jni-jvm-linux-aarch64`
- `webrtc-aec3-kmp-jni-jvm-darwin-x86_64`
- `webrtc-aec3-kmp-jni-jvm-darwin-aarch64`
- `webrtc-aec3-kmp-jni-jvm-windows-x86_64`

`NativeLoader` detects `os.name`/`os.arch` at runtime, extracts the matching binary from the classpath to a temp directory, and `System.load`s it. No `java.library.path` setup is required for downstream JVM consumers.

## Quick Start

```kotlin
import cn.enaium.webrtc.aec3.createAec3AudioBuffer
import cn.enaium.webrtc.aec3.createAec3Config
import cn.enaium.webrtc.aec3.createAec3EchoControl
import cn.enaium.webrtc.aec3.createAec3Environment
import cn.enaium.webrtc.aec3.createAec3FactoryWithConfig

// 1. Create the object graph
val config = createAec3Config().apply {
    setDelayDefaultDelay(25)
    setFilterInitialStateSeconds(0.5f)
    setFilterConservativeInitialPhase(false)
}
val env = createAec3Environment()
val factory = createAec3FactoryWithConfig(config)
val echoControl = createAec3EchoControl(factory, env, 16000, 1, 1)

// 2. Process 10 ms frames (160 samples @ 16 kHz)
val render = createAec3AudioBuffer(16000, 1)   // far-end signal (what the speaker plays)
val capture = createAec3AudioBuffer(16000, 1)  // near-end signal (microphone)

render.writeChannel(0, farEndFrame)
echoControl.analyzeRender(render)

capture.writeChannel(0, micFrame)
echoControl.analyzeCapture(capture)
echoControl.processCapture(capture, false)

val cleanFrame = capture.readChannel(0)        // echo-cancelled near-end

// 3. Cleanup
render.close()
capture.close()
echoControl.close()
factory.close()
env.close()
config.close()
```

## API Reference

```kotlin
fun createAec3Config(): Aec3Config
fun createAec3ConfigFromJson(json: String): Aec3Config?
fun createAec3Environment(): Aec3Environment
fun createAec3Factory(): Aec3Factory
fun createAec3FactoryWithConfig(config: Aec3Config): Aec3Factory
fun createAec3AudioBuffer(sampleRate: Int, channels: Int): Aec3AudioBuffer
fun createAec3EchoControl(
    factory: Aec3Factory,
    env: Aec3Environment,
    sampleRate: Int,
    renderChannels: Int,
    captureChannels: Int
): Aec3EchoControl
```

### Aec3EchoControl

| Method                                      | Description                                            |
| ------------------------------------------- | ------------------------------------------------------ |
| `analyzeRender(render)`                     | Feed a far-end (render) frame before playback          |
| `analyzeCapture(capture)`                   | Feed a near-end (capture) frame from the microphone    |
| `processCapture(capture, levelChange)`      | Run AEC and write the echo-cancelled result in-place   |
| `getMetrics(): Aec3Metrics`                 | ERL, ERLE and estimated delay                          |
| `setAudioBufferDelay(delayMs)`              | Tune the render→capture delay estimate                 |

## Example

The [`example/`](example/) module is an Android app with a Jetpack Compose UI:

- **Echo cancellation switch** — toggle AEC on/off
- **Delay slider** (0–200 ms) — live `setAudioBufferDelay` tuning
- **Start/Stop button** — real-time `AudioRecord → AEC3 → AudioTrack` loopback

It plays a 440 Hz reference tone through the speaker, records the microphone, and cancels the acoustic echo in real time, showing ERL/ERLE/delay metrics.

## Building from Source

### Prerequisites

- JDK 17+
- CMake 3.16+
- Android SDK + NDK (for Android targets)
- Xcode command-line tools (for iOS/macOS/tvOS/watchOS targets)

### Clone with submodules

```bash
git clone --recursive https://github.com/Enaium/webrtc-aec3-kmp.git
cd webrtc-aec3-kmp
```

### Publish to Maven Local

```bash
./gradlew :aec3:publishToMavenLocal
```

### Run tests

```bash
./gradlew :aec3:jvmTest        # JVM (JNI)
./gradlew :aec3:macosArm64Test # macOS native
```

## Project Structure

```
webrtc-aec3-kmp/
├── webrtc-aec3/              # Git submodule (C++ library)
├── jni/
│   ├── CMakeLists.txt        # JNI shared library build
│   ├── jni_bridge.cpp        # JNI bridge (C++ → JVM/Android)
│   ├── c_api/                # C API (webrtc_aec3_c.h/.cc)
│   └── jvm/                  # Per-OS/arch JNI publication subprojects
│       ├── darwin-aarch64, darwin-x86_64
│       ├── linux-x86_64, linux-aarch64
│       └── windows-x86_64
├── aec3/                     # Kotlin Multiplatform module
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/       # expect declarations + common interfaces
│       ├── commonTest/
│       ├── jvmMain/          # JVM actual (JNI) + NativeLoader
│       ├── androidMain/      # Android actual (JNI)
│       ├── nativeMain/       # Native actual (cinterop)
│       └── nativeInterop/cinterop/
├── example/                  # Android Compose demo (loopback + AEC switch)
├── scripts/                  # Native build helpers
└── .github/workflows/        # publish + test
```

## License

[MIT](LICENSE) — see the [LICENSE](LICENSE) file.
