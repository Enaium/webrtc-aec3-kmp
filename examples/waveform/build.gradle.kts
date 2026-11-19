import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// ---------------------------------------------------------------------------
// Android stub libraries
//
// SDL3's Android drivers reference the platform libraries (libEGL, libGLESv2,
// libOpenSLES, libaaudio, ...) at link time, and Kotlin/Native ships stubs for
// them in its Android toolchain sysroot. The NDK's own stubs cannot stand in:
// the toolchain's linker cannot read their compressed debug sections. That
// sysroot only exists once the toolchain has been downloaded, which happens
// while a build runs, so every Android link is pointed at a directory this
// build fills first (running the target's compile downloads the toolchain).
// ---------------------------------------------------------------------------
val androidStubDir = layout.buildDirectory.dir("androidStubs")

/** Android API level the stubs are taken for (AAudio needs 26). */
val ANDROID_API_LEVEL = 26

/** ABI to the NDK triple its stubs live under. */
val androidTriples = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "arm-linux-androideabi",
    "x86_64" to "x86_64-linux-android",
    "x86" to "i686-linux-android",
)

/** Where the Android links look for the stubs. */
fun androidStubLinkDir(abi: String): String = androidStubDir.get().asFile.resolve(abi).absolutePath

/** The stubs inside the Kotlin/Native Android sysroot, or `null` before it is downloaded. */
fun konanAndroidStubDir(abi: String): File? {
    val triple = androidTriples[abi] ?: return null
    val konanData = System.getenv("KONAN_DATA_DIR")
        ?: "${System.getProperty("user.home")}/.konan"
    return File(konanData, "dependencies").listFiles()
        // Newest first: a toolchain left over from an older Kotlin has an older sysroot.
        ?.filter { it.isDirectory && it.name.matches(Regex("target-toolchain-.*-android_ndk")) }
        ?.maxByOrNull { it.name }
        ?.resolve("sysroot/usr/lib/$triple/$ANDROID_API_LEVEL")
        ?.takeIf { it.isDirectory }
}

// Shared by every Android ABI:
//  - compiler-rt builtins embedded in the sdl-kmp/imgui-kmp klibs overlap with
//    K/N's bundled libgcc on some ABIs (e.g. __sync_* on armv7), so the first
//    definition wins;
//  - Android devices come with either 4 KB or 16 KB memory pages, so every
//    PT_LOAD segment of libmain.so is aligned for both (the same flags the JNI
//    shared library is built with).
val androidMainLinkerOpts = listOf(
    "-Wl,--allow-multiple-definition",
    "-Wl,-z,max-page-size=16384",
    "-Wl,-z,common-page-size=16384",
)

/**
 * NDK sysroot library directory for [abi], or `null` when no NDK is installed.
 *
 * The static libraries linked into libmain.so (aec3, and the other
 * dependencies' AAR builders) are compiled by the NDK, so their objects
 * reference std::__ndk1 symbols - `_ZTTNSt6__ndk119basic_ostringstream...`
 * among them - that the libc++ Kotlin/Native bundles in its own sysroot does
 * not define. Putting the NDK's libc++ on the link line resolves them; without
 * it the library loads with "dlopen failed: cannot locate symbol" on the
 * device.
 */
fun androidNdkLibDir(abi: String): String? {
    val sysroot = rootProject.extra["androidNdkSysroot"] as String? ?: return null
    val triple = androidTriples[abi] ?: return null
    return "$sysroot/usr/lib/$triple"
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        mainRun {
            mainClass = "cn.enaium.webrtc.aec3.examples.waveform.Main_jvmKt"
        }
    }

    // Native targets that every dependency publishes klibs for (aec3,
    // imgui-kmp, sdl-kmp and audio-io-kmp all ship macOS, Linux x86_64 and
    // Windows klibs, so the same UI source builds into a standalone executable
    // on each of them).
    macosArm64 {
        binaries.executable()
    }

    macosX64 {
        binaries.executable()
    }

    linuxX64 {
        binaries.executable()
    }

    mingwX64 {
        binaries.executable()
    }

    // Browser build: audio-io-kmp and sdl-kmp publish wasmJs artifacts that
    // drive WebAudio and SDL3 compiled to wasm, so the same UI source runs in
    // a page. AEC3 has no wasm target - the browser build plays the capture
    // through (see WasmWaveformPipeline) - which is why the AEC3 pipeline
    // lives in its own source set below.
    wasmJs {
        binaries.executable()
        browser()
    }

    // Android native: SDLActivity (from the SDL3 Android archive) loads
    // libmain.so and calls its exported SDL_main, so the example is packaged as
    // a shared library here and turned into an APK by
    // :examples:waveform:android.
    androidNativeArm64 {
        binaries.sharedLib("main") {
            linkerOpts("-L" + androidStubLinkDir("arm64-v8a"))
            androidNdkLibDir("arm64-v8a")?.let { linkerOpts("$it/libc++_shared.so") }
            linkerOpts(*androidMainLinkerOpts.toTypedArray())
        }
    }

    androidNativeArm32 {
        binaries.sharedLib("main") {
            linkerOpts("-L" + androidStubLinkDir("armeabi-v7a"))
            androidNdkLibDir("armeabi-v7a")?.let { linkerOpts("$it/libc++_shared.so") }
            linkerOpts(*androidMainLinkerOpts.toTypedArray())
        }
    }

    androidNativeX64 {
        binaries.sharedLib("main") {
            linkerOpts("-L" + androidStubLinkDir("x86_64"))
            androidNdkLibDir("x86_64")?.let { linkerOpts("$it/libc++_shared.so") }
            linkerOpts(*androidMainLinkerOpts.toTypedArray())
        }
    }

    androidNativeX86 {
        binaries.sharedLib("main") {
            linkerOpts("-L" + androidStubLinkDir("x86"))
            androidNdkLibDir("x86")?.let { linkerOpts("$it/libc++_shared.so") }
            linkerOpts(*androidMainLinkerOpts.toTypedArray())
        }
    }

    // The repository disables the automatic default hierarchy template
    // (gradle.properties), so materialize it here: the JVM, native and Android
    // native targets need their shared intermediate source sets (nativeMain,
    // appleMain, macosMain, ...) for the common UI code and the entry points.
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                // Dear ImGui + ImPlot bindings and the SDL3 window/renderer
                // they are driven from; audio-io-kmp captures and plays the PCM.
                implementation(libs.imgui.kmp)
                implementation(libs.sdl.kmp)
                implementation(libs.audio.io.kmp)
            }
        }

        // The AEC3 pipeline is the only thing in the example that binds the
        // C++ library through its cinterop klib. Keeping it out of commonMain
        // is what lets wasmJs build: :aec3 publishes no wasm target, while the
        // UI above only knows the WaveformPipeline interface.
        val aec3Main by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(project(":aec3"))
            }
        }
        jvmMain.get().dependsOn(aec3Main)
        nativeMain.get().dependsOn(aec3Main)
    }
}

// Fill the stub directories the Android links above point at. Depending on the
// compile is what guarantees the toolchain - and with it the stubs - has been
// downloaded by the time they are copied.
mapOf(
    "AndroidNativeArm64" to "arm64-v8a",
    "AndroidNativeArm32" to "armeabi-v7a",
    "AndroidNativeX64" to "x86_64",
    "AndroidNativeX86" to "x86",
).forEach { (targetName, abi) ->
    val copyStubs = tasks.register<Copy>("copyAndroidStubs$targetName") {
        description = "Copies the Android stub libraries for $abi into the link directory."
        dependsOn("compileKotlin$targetName")
        from(provider { konanAndroidStubDir(abi)?.let { listOf(it) } ?: emptyList<File>() })
        into(androidStubDir.map { it.dir(abi) })
    }
    listOf("linkMainDebugShared$targetName", "linkMainReleaseShared$targetName").forEach { linkName ->
        tasks.named(linkName) { dependsOn(copyStubs) }
    }
}

// SDL3 has to own the first thread on macOS, otherwise video driver init fails
// with "No available video device". Mirrors the imgui-kmp examples.
// --enable-native-access silences the JNI warnings on JDK 24+.
// Native executables and the Android shared library need none of this: their
// main / SDL_main already runs on the thread SDL owns.
tasks.withType<JavaExec>().configureEach {
    if (OperatingSystem.current().isMacOsX) {
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread")
    }
}

// ==================== web (wasmJs) ====================
// SDL3 and imgui are Emscripten modules shipped by the @enaium/sdl-kmp and
// @enaium/imgui-kmp npm packages; the page loads them before the Kotlin
// module. This assembles a self-contained static app directory instead of the
// wasmJs webpack run (which needs npm's webpack plugin too):
//   build/browserApp/
//     index.html                 (entry page; import map -> node_modules)
//     node_modules/@enaium/...    (the three glue packages)
//     waveform.mjs / *.wasm      (the Kotlin/Wasm executable)
// Serve it with any static file server, e.g. `python3 -m http.server`.

val wasmWorkDir = layout.projectDirectory.dir("wasm")
val installedSdlPackage = wasmWorkDir.dir("node_modules/@enaium/sdl-kmp")
val installedImguiPackage = wasmWorkDir.dir("node_modules/@enaium/imgui-kmp")
val installedAudioIoPackage = wasmWorkDir.dir("node_modules/@enaium/audio-io-kmp")

val npmInstall = tasks.register<Exec>("npmInstall") {
    description = "Installs the browser build's npm dependencies (the SDL3, imgui and Web Audio glue)."
    workingDir = wasmWorkDir.asFile
    commandLine("npm", "install", "--no-audit", "--no-fund")
    inputs.file(wasmWorkDir.file("package.json"))
    outputs.dir(wasmWorkDir.dir("node_modules"))
}

val copyWasmAssets = tasks.register<Copy>("copyWasmAssets") {
    description = "Assembles the static browser app under build/browserApp."
    dependsOn("compileDevelopmentExecutableKotlinWasmJs")
    dependsOn(npmInstall)
    from("src/wasmJsMain/resources/web")
    // npm installs file: dependencies as symlinks; copy the real contents so
    // the import map resolves them from the app directory.
    from(provider { installedSdlPackage.asFile.canonicalFile }) {
        into("node_modules/@enaium/sdl-kmp")
    }
    from(provider { installedImguiPackage.asFile.canonicalFile }) {
        into("node_modules/@enaium/imgui-kmp")
    }
    from(provider { installedAudioIoPackage.asFile.canonicalFile }) {
        into("node_modules/@enaium/audio-io-kmp")
    }
    from(layout.buildDirectory.dir("compileSync/wasmJs/main/developmentExecutable/kotlin")) {
        include("**")
    }
    into(layout.buildDirectory.dir("browserApp"))
}
