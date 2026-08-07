/*
 * Per-OS/arch JNI artifact: linux-aarch64.
 * Ships libwebrtc_aec3_jni.so as a classpath resource at
 * /cn/enaium/webrtc/aec3/native/linux-aarch64/, which NativeLoader
 * (in :aec3's jvmMain) extracts and System.load()s at runtime.
 *
 * Builds natively on Linux arm64 hosts, or cross-compiles on Linux x86_64
 * hosts when the aarch64-linux-gnu toolchain is installed (CI does this via
 * `sudo apt-get install gcc-aarch64-linux-gnu g++-aarch64-linux-gnu`).
 */
import java.io.File
import org.gradle.internal.os.OperatingSystem

plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

group = rootProject.group
version = rootProject.version

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val jniOs = "linux"
val jniArch = "aarch64"
val classifier = "$jniOs-$jniArch"
val libFile = "libwebrtc_aec3_jni.so"
val resourceDir = "cn/enaium/webrtc/aec3/native/$classifier"

val host = OperatingSystem.current()
val hostArch = System.getProperty("os.arch").lowercase()
val hostIsLinuxArm64 = host.isLinux && (hostArch == "aarch64" || hostArch == "arm64")
val hostIsLinuxX64 = host.isLinux && (hostArch == "amd64" || hostArch == "x86_64")

fun hasAarch64CrossToolchain(): Boolean {
    val name = "aarch64-linux-gnu-gcc"
    return System.getenv("PATH")?.split(File.pathSeparator).orEmpty().any { dir ->
        val f = File(dir, name)
        f.isFile && f.canExecute()
    }
}

val canBuildHere = hostIsLinuxArm64 || (hostIsLinuxX64 && hasAarch64CrossToolchain())

val nativeOutputDir = layout.buildDirectory.dir("jni-native/$classifier")
val cmakeBuildDir = layout.buildDirectory.dir("cmake-jni/$classifier")

val configureJniLibrary by tasks.registering(Exec::class) {
    group = "build"
    description = "cmake-configures libwebrtc_aec3_jni for $classifier."
    onlyIf { canBuildHere }
    val outDir = nativeOutputDir.get().asFile
    val buildDir = cmakeBuildDir.get().asFile
    doFirst {
        outDir.mkdirs()
        buildDir.mkdirs()
    }
    workingDir = buildDir
    val javaHome = System.getProperty("java.home") ?: System.getenv("JAVA_HOME") ?: ""
    val jniInclude = if (javaHome.isNotEmpty()) "$javaHome/include" else ""
    val args = mutableListOf(
        "cmake",
        rootProject.file("jni").absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DJNI_INCLUDE_DIR=$jniInclude",
        "-DJNI_INCLUDE_DIR_PLATFORM=$jniInclude/linux",
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${outDir.absolutePath}",
    )
    if (hostIsLinuxX64) {
        args += listOf(
            "-DCMAKE_SYSTEM_NAME=Linux",
            "-DCMAKE_SYSTEM_PROCESSOR=aarch64",
            "-DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc",
            "-DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++",
        )
    }
    commandLine(args)
}

val buildJniLibrary by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libwebrtc_aec3_jni.so for $classifier."
    onlyIf { canBuildHere }
    dependsOn(configureJniLibrary)
    workingDir = cmakeBuildDir.get().asFile
    commandLine("cmake", "--build", ".", "--config", "Release")
    inputs.files(rootProject.file("jni/CMakeLists.txt"), rootProject.file("jni/jni_bridge.cpp"))
    inputs.dir(rootProject.file("jni/c_api"))
    inputs.dir(rootProject.file("webrtc-aec3"))
    outputs.file(nativeOutputDir.map { it.file(libFile) })
}

tasks.named<Copy>("processResources") {
    dependsOn(buildJniLibrary)
    from(nativeOutputDir) {
        include(libFile)
        into(resourceDir)
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    coordinates(
        groupId = rootProject.group.toString(),
        artifactId = "webrtc-aec3-kmp-jni-jvm-$classifier",
        version = rootProject.version.toString(),
    )
    pom {
        name.set("webrtc-aec3-kmp-jni-jvm-$classifier")
        description.set(
            "Prebuilt JNI shared library for webrtc-aec3-kmp on $jniOs/$jniArch. " +
                "Loaded automatically by NativeLoader; not intended to be depended on directly.",
        )
        url.set("https://github.com/Enaium/webrtc-aec3-kmp")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("BSD-3-Clause")
                url.set("https://opensource.org/licenses/BSD-3-Clause")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("Enaium")
            }
        }
        scm {
            url.set("https://github.com/Enaium/webrtc-aec3-kmp")
            connection.set("scm:git:git@github.com:Enaium/webrtc-aec3-kmp.git")
            developerConnection.set("scm:git:git@github.com:Enaium/webrtc-aec3-kmp.git")
        }
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/Enaium/webrtc-aec3-kmp/issues")
        }
    }
}
