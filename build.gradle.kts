plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = "cn.enaium.webrtc.aec3"
    val v = rootProject.findProperty("version") as? String
    version = if (v.isNullOrBlank() || v == "unspecified") "1.0-SNAPSHOT" else v
}
