plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gios.lightsync"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.gios.lightsync"
        minSdk = 29
        targetSdk = 35
        // CI overwrites both from the workflow run number; see .github/workflows/build.yml
        versionCode = 1
        versionName = "1.3.0"

        // The LPIII is arm64 only; shipping four ABIs tripled the APK for nothing.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/lightsync.jks")
            storePassword = "lightsync"
            keyAlias = "lightsync"
            keyPassword = "lightsync"
        }
    }

    buildTypes {
        release {
            // On for the first time in this app. The agent is small, but it is also the app
            // that has to still work when everything else on the phone is broken, so the
            // keep rules in proguard-rules.pro are written out rather than inherited.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same committed key as debug, so either APK upgrades over the other.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    // The wheel, and the SyncMeta keys the fleet screen reads out of each app's provider.
    // The agent compiling against the same constants as the apps is the only thing keeping
    // the two sides of an untyped ContentResolver.call in step.
    implementation("com.gios:light-common:1.2.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Reading the setup code off a screen. CameraX for the frames — core, camera2 for the
    // backend, lifecycle to bind it, view for the PreviewView — and ZXing to decode. ZXing
    // rather than ML Kit is not a preference: ML Kit's barcode reader arrives through Play
    // Services, which LightOS does not have, so it would bind and never answer.
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.zxing:core:3.5.3")

    // Background sync. WorkManager sits on JobScheduler, so it needs no Play Services.
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Installs the baseline profile light-common ships in its AAR. Without this the profile is
    // packaged and never used: below API 31 nothing reads one on its own.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Version ordering on the fleet screen is plain arithmetic, so it runs here.
    testImplementation("junit:junit:4.13.2")
}
