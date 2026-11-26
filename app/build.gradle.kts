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
        versionCode = 11008
        versionName = "1.12.0-private-preview"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            signingConfig = null // Code-only export; configure signing locally. Personal installed key is unchanged.
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

dependencies {
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
