plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ── Brand layer: the only part that differs between StreamX and Xstream ──
// The shared code reads these through Brand.kt (BuildConfig) and the manifest placeholders below.
// StreamX's values: BRAND_NAME "StreamX", IS_BETA false, UPDATE_REPO "callmerickk/StreamNest",
// UPDATE_CDN "https://streamx-dl.callmerick093.workers.dev", APK_PHONE "StreamX-phone.apk", APK_TV "StreamX-TV.apk",
// SHARED_ROOT "shared", invite "streamx" / "streamx-watch.pages.dev" / "/join", no brand res folder.
val brand = mapOf(
    "BRAND_NAME" to "Xstream",
    "UPDATE_REPO" to "callmerickk/Xstream",
    // Xstream has no download relay: updates come straight from GitHub.
    "UPDATE_CDN" to "",
    "APK_PHONE" to "Xstream-phone.apk",
    // 0.9.6: the small 32-bit phone APK (StreamX's brand file: "" = none, the updater then never asks for it).
    "APK_PHONE_ARM32" to "Xstream-phone-arm32.apk",
    "APK_TV" to "Xstream-TV.apk",
    // Xstream's own cloud shelf (falls back to shared/ for reading).
    "SHARED_ROOT" to "xshared",
    "INVITE_SCHEME" to "xstream",
    "INVITE_HOST" to "streamx-home.callmerick093.workers.dev",
    "INVITE_PATH" to "/xjoin",
)
val brandBeta = true

android {
    lint { abortOnError = false; checkReleaseBuilds = false }
    namespace = "com.example.streamnest"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.xstream"
        minSdk = 24
        targetSdk = 34
        // 0.9.6: one code for both phone APKs (arm64 and arm32): a phone only ever installs the one for its own ABI.
        versionCode = 66
        versionName = "0.9.6"
        brand.forEach { (k, v) -> buildConfigField("String", k, "\"$v\"") }
        buildConfigField("boolean", "IS_BETA", brandBeta.toString())
        manifestPlaceholders["brandName"] = brand.getValue("BRAND_NAME")
        manifestPlaceholders["inviteScheme"] = brand.getValue("INVITE_SCHEME")
        manifestPlaceholders["inviteHost"] = brand.getValue("INVITE_HOST")
        manifestPlaceholders["invitePath"] = brand.getValue("INVITE_PATH")
        resValue("string", "brand_name", brand.getValue("BRAND_NAME"))
        resValue("string", "widget_start", "Start something in " + brand.getValue("BRAND_NAME"))
    }
    // Two apps from one project: the phone app and Xstream TV (Fire TV / Android TV).
    // "gradle assembleDebug" builds both APKs.
    flavorDimensions += "device"
    productFlavors {
        create("phone") {
            dimension = "device"
            buildConfigField("boolean", "IS_TV", "false")
            buildConfigField("boolean", "HAS_LIVE", "false")
            // 0.9.1: Xstream Music is back (Feature.music; launcher entry, service and widget in src/brand/phone).
            buildConfigField("boolean", "HAS_MUSIC", "true")
            // Testing app: no "Publish for everyone" buttons (as Xstream always had).
            buildConfigField("boolean", "NO_PUBLISH", "true")
            // 0.9.6: ONE value drives (1) the Feature switches noVlc / noVosk / mlkitUnbundled / webrtcOnDemand and
            // (2) which native libraries are packed (androidComponents below), so they can never disagree.
            // StreamX's brand file sets it to false (promoting the slim build to StreamX = flipping it there).
            buildConfigField("boolean", "SLIM_LIBS", brandBeta.toString())
            resValue("string", "app_name", brand.getValue("BRAND_NAME"))
            // 0.9.6: Xstream: the 64-bit APK by default, the 32-bit one with -PphoneAbi=arm32 (CI builds both: same key,
            // same versionCode). Before 0.9.6 one APK held both ABIs.
            ndk {
                abiFilters += when {
                    project.findProperty("phoneAbi") == "arm32" -> listOf("armeabi-v7a")
                    brandBeta -> listOf("arm64-v8a")
                    else -> listOf("arm64-v8a", "armeabi-v7a")
                }
            }
            resourceConfigurations += listOf("en")
        }
        create("tv") {
            dimension = "device"
            applicationIdSuffix = ".tv"
            versionCode = 43
            versionName = "TV 0.9.0 beta"
            buildConfigField("boolean", "IS_TV", "true")
            buildConfigField("boolean", "HAS_LIVE", "false")
            buildConfigField("boolean", "HAS_MUSIC", "false")
            buildConfigField("boolean", "NO_PUBLISH", "false")
            // 0.9.6: the TV app keeps every library (VLC, bundled face model, WebRTC in the APK).
            buildConfigField("boolean", "SLIM_LIBS", "false")
            resValue("string", "app_name", brand.getValue("BRAND_NAME") + " TV")
            // Fire TV Sticks run 32-bit Android; 32-bit ARM also runs on every 64-bit TV box.
            ndk { abiFilters += listOf("armeabi-v7a") + (if (project.hasProperty("smokeX86")) listOf("x86_64") else emptyList()) }
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
    buildFeatures { compose = true; buildConfig = true; resValues = true }
    // Xstream's brand resources (its logo colours) override the shared ones.
    sourceSets {
        getByName("phone").res.srcDir("src/brand/res")
        getByName("tv").res.srcDir("src/brand/res")
        // 0.9.1: Xstream's phone manifest (Music entries, 0.8.x invite links), merged over src/main's. The shared code has
        // no src/phone manifest; if it ever gets one, merge it into this file.
        getByName("phone").manifest.srcFile("src/brand/phone/AndroidManifest.xml")
        // 0.9.6: Xstream phone only: the Play-services face model glue (FaceModelGms.kt; StreamX / TV never compile it).
        getByName("phone").java.srcDir("src/brand/phone/java")
    }
    packaging {
        // Keep one copy of the C++ runtime, and store native libraries compressed (much smaller APK).
        jniLibs {
            pickFirsts += listOf("**/libc++_shared.so")
            useLegacyPackaging = true
        }
    }
}

// 0.9.6: Xstream PHONE only (SLIM_LIBS): the big native libraries stay out of the APK. Their Java/Kotlin classes stay (small)
// so the shared code compiles; Feature.noVlc / noVosk / webrtcOnDemand make sure nothing ever loads the missing .so files.
androidComponents {
    onVariants(selector().withFlavor("device" to "phone")) { v ->
        if (brandBeta) {
            v.packaging.jniLibs.excludes.addAll(listOf(
                "**/libvlc.so", "**/libvlcjni.so",               // LibVLC
                "**/libvosk.so", "**/libjnidispatch.so",         // Vosk + JNA ("Hey Aiden" uses Android's recogniser)
                "**/libjingle_peerconnection_so.so",             // WebRTC: downloaded on first use (WebRtcLib.kt)
            ))
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
    "phoneImplementation"("io.github.anilbeesetti:nextlib-media3ext:1.7.1-0.9.0")
    // TV 0.8.6: Media3's FFmpeg extension (Apache-2.0) built with LGPL-2.1 FFmpeg, audio decoders only (libs/, see FFMPEG.md).
    "tvImplementation"(files("libs/ffmpeg-audio-lgpl.aar"))
    "tvImplementation"("androidx.media3:media3-decoder:1.7.1")
    // VLC engine: backup for the rare files ExoPlayer can't open (ExoPlayer plays first, like Nuvio).
    // 0.9.6: kept for StreamX / TV; in Xstream's phone APK only the classes remain (the .so files are excluded above).
    implementation("org.videolan.android:libvlc-all:3.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    // "Hey Aiden": offline wake word, Vosk (Apache 2.0) + JNA.
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
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
    // CloudStream plugins run on StreamX's own CloudStream library (com.lagradost.*). The open-source libraries plugins
    // call directly (all MIT / Apache 2.0): HTML parsing, JSON, URLs, and AppCompat / Material for their settings.
    implementation("org.jsoup:jsoup:1.18.1")
    // Plugins are built with coroutines 1.11 (runBlocking is "runBlockingK" there): use the same version.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.google.code.gson:gson:2.11.0")
    // Subtitles › Follow the speaker (beta): on-device face + lip contours (Apache-licensed ML Kit client, no network).
    // 0.9.6: Xstream phone: the face model comes from Google Play services (no native model in the APK). Same API package
    // (com.google.mlkit.vision.face), so Speaker.kt is unchanged; the two artifacts can't share one build (duplicate classes).
    if (brandBeta) {
        "phoneImplementation"("com.google.android.gms:play-services-mlkit-face-detection:17.1.0")
        "tvImplementation"("com.google.mlkit:face-detection:16.1.6")
    } else {
        implementation("com.google.mlkit:face-detection:16.1.6")
    }
    implementation("io.ktor:ktor-http:2.3.12")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // "Continue with Google" on phones (Credential Manager). Fire TV signs in with email + password.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
