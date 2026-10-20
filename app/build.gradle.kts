import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing comes from environment variables set by the release workflow.
// Local or unsigned builds fall back to the debug key.
val releaseStore: String? = System.getenv("HPA_KEYSTORE_PATH")
val releaseStorePass: String? = System.getenv("HPA_KEYSTORE_PASSWORD")
val releaseAlias: String? = System.getenv("HPA_KEY_ALIAS")
val releaseKeyPass: String? = System.getenv("HPA_KEY_PASSWORD")
val hasReleaseKey = !releaseStore.isNullOrBlank() && file(releaseStore!!).exists()

val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.hem.hybridphotoassist"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hem.hybridphotoassist"
        minSdk = 29
        targetSdk = 35
        versionCode = (versionProps.getProperty("versionCode") ?: "1").toInt()
        versionName = versionProps.getProperty("versionName") ?: "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseStore!!)
                storePassword = releaseStorePass
                keyAlias = releaseAlias
                keyPassword = releaseKeyPass
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { buildConfig = true }

    androidResources {
        // Models are memory-mapped, so they must stay uncompressed in the APK.
        noCompress += listOf("task", "tflite")
    }

    packaging {
        jniLibs {
            pickFirsts += listOf("**/libc++_shared.so")
            useLegacyPackaging = false
        }
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    // Tap-to-select (version and API taken from Google's official sample).
    implementation("com.google.mediapipe:tasks-vision:1.0.0")
    // Runs the LaMa erase-and-fill model on the phone.
    implementation("com.google.ai.edge.litert:litert:1.4.2")

    testImplementation("junit:junit:4.13.2")
}
