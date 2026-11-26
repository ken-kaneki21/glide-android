import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gharaudio.launcher"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.gharaudio.launcher"
        minSdk = 28
        targetSdk = 37
        versionCode = 11009
        versionName = "1.12.1-transcription-preview"
        ndk { abiFilters += listOf("arm64-v8a") } // Verified LG G6 ABI; no NDK build is required.

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            signingConfig = null // Public code only; configure signing locally. Original personal key is unchanged.
            proguardFiles("speech-proguard.pro")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

val speechInputs = providers.gradleProperty("glideSpeechInputs").orNull
    ?: error("Set -PglideSpeechInputs to the verified local Vosk/JNA AAR directory; weights are imported separately.")
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("glideSpeechModelFixture", file("$speechInputs/vosk-model-small-en-us-0.15.zip").absolutePath)
}
fun verifiedSpeechAar(name: String, expected: String): File {
    val artifact = file("$speechInputs/$name")
    check(artifact.isFile) { "Missing speech runtime: $name" }
    val digest = MessageDigest.getInstance("SHA-256")
    artifact.inputStream().use { stream ->
        val bytes = ByteArray(65536)
        while (true) { val n = stream.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
    }
    check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expected) { "Speech runtime SHA-256 mismatch: $name" }
    return artifact
}

dependencies {
    implementation(files(verifiedSpeechAar("vosk-android-0.3.75.aar", "ab2f8b91ac8051561aa325546b35fed9a68b36b8121bac5c6fb927525c4adfad")))
    implementation(files(verifiedSpeechAar("jna-5.18.1.aar", "7f053e3ec99e14dd71259c82c1c8a02738d64a13c31226b2acc170f3060951e0")))
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-session:1.10.1")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.media:media:1.7.0")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Restricted personal font binaries stay local and are never substituted automatically.
tasks.named("preBuild").configure {
    doFirst {
        val missing = listOf("zegoe_light.ttf", "zegoe_regular.ttf", "zegoe_semibold.ttf", "zegoe_bold.ttf")
            .filterNot { layout.projectDirectory.file("src/main/res/font/$it").asFile.isFile }
        check(missing.isEmpty()) { "Provide your own licensed local Zegoe fonts; see FONT_SETUP.md. Missing: $missing" }
    }
}
