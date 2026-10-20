plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    lint { abortOnError = false; checkReleaseBuilds = false }
    namespace = "com.example.streamnest"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.xstream"
        minSdk = 24
        targetSdk = 34
        versionCode = 11
        versionName = "0.4.6 beta"
    }
    // Two apps from one project: the phone app and StreamX TV (Fire TV / Android TV).
    // "gradle assembleDebug" builds both APKs.
    flavorDimensions += "device"
    productFlavors {
        create("phone") {
            dimension = "device"
            buildConfigField("boolean", "IS_TV", "false")
            // 64-bit ARM phones only: every phone from the last ~6 years. Keeps the VLC engine small.
            ndk { abiFilters += listOf("arm64-v8a") }
        }
        create("tv") {
            dimension = "device"
            applicationIdSuffix = ".tv"
            versionCode = 42
            versionName = "TV 0.7.2 beta"
            buildConfigField("boolean", "IS_TV", "true")
            // Fire TV Sticks run 32-bit Android; 32-bit ARM also runs on every 64-bit TV box.
            ndk { abiFilters += listOf("armeabi-v7a") }
        }
    }
    // One fixed key for every build, so new APKs install over the old app without uninstalling.
    signingConfigs {
        create("fixed") {
            storeFile = file("streamnest.keystore")
            storePassword = "streamnest"
            keyAlias = "streamnest"
            keyPassword = "streamnest"
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
            // Not debuggable: Android then fully optimises the app's code, which makes scrolling and
            // animations much smoother (debuggable builds run Compose noticeably slower).
            isDebuggable = false
            isJniDebuggable = false
            // Remove unused code and resources (mostly unused icons) so the APK stays small.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
        release {
            signingConfig = signingConfigs.getByName("fixed")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // coroutines 1.11 is built with a newer Kotlin than this project's compiler; reading its metadata is safe.
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        // Keep one copy of the C++ runtime, and store native libraries compressed (much smaller APK).
        jniLibs {
            pickFirsts += listOf("**/libc++_shared.so")
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(bom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.7.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.7.1")
    implementation("androidx.media3:media3-ui:1.7.1")
    // Saved video on storage (instant play): SimpleCache needs its database module.
    implementation("androidx.media3:media3-database:1.7.1")
    implementation("androidx.media3:media3-datasource:1.7.1")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.7.1")
    // NextLib (as CloudStream): FFmpeg audio AND video decoders for ExoPlayer — Dolby Digital / DD+ / DTS / TrueHD and
    // video formats the phone can't decode play anyway. (Only the needed ARM library is packaged, see abiFilters.)
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.7.1-0.9.0")
    // VLC engine: backup for the rare files ExoPlayer can't open (ExoPlayer plays first, like Nuvio).
    implementation("org.videolan.android:libvlc-all:3.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    // Watch together voice + video (WebRTC, org.webrtc). Only the ARM library of each app is packaged (abiFilters).
    implementation("io.getstream:stream-webrtc-android:1.3.9")
    // StreamX VPN: WireGuard's official Android library (open source), and a QR scanner for WireGuard QR codes.
    implementation("com.wireguard.android:tunnel:1.0.20250531")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Installs the libraries' ahead-of-time profiles: much smoother first scrolls on Fire TV.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    // New-episode alerts in the background (Settings › Notifications).
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Earbuds / headset / TV remote play-pause keys reach the player (media session).
    implementation("androidx.media3:media3-session:1.7.1")
    // Fingerprint unlock for locked profiles.
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    // JavaScript engine for StreamX plugins (Plugins.kt).
    implementation("org.mozilla:rhino:1.7.15")
    // CloudStream plugins run on Xstream's own CloudStream library (com.lagradost.*). The open-source libraries plugins
    // call directly (all MIT / Apache 2.0): HTML parsing, JSON, URLs, and AppCompat / Material for their settings.
    implementation("org.jsoup:jsoup:1.18.1")
    // Plugins are built with coroutines 1.11 (runBlocking is "runBlockingK" there): use the same version.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("io.ktor:ktor-http:2.3.12")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // "Continue with Google" on phones (Credential Manager). Fire TV signs in with email + password.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
