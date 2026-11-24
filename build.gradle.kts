plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
    // 0.9.2: Firebase crash reports (Crashlytics) and Performance Monitoring (Apache-2.0), for both apps.
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("com.google.firebase.crashlytics") version "3.0.2" apply false
    id("com.google.firebase.firebase-perf") version "1.4.2" apply false
}

// 0.9.2: shared Gradle part (this file is synced to StreamX by tools/sync-to-streamx.sh, so both apps get the same
// Firebase setup). app/google-services.json holds all four apps (StreamX, StreamX TV, Xstream, Xstream TV).
subprojects {
    pluginManager.withPlugin("com.android.application") {
        apply(plugin = "com.google.gms.google-services")
        apply(plugin = "com.google.firebase.crashlytics")
        apply(plugin = "com.google.firebase.firebase-perf")
        // No mapping upload while building (the code isn't renamed: -dontobfuscate), so a build never needs Firebase.
        extensions.getByType(com.android.build.api.dsl.ApplicationExtension::class.java).buildTypes.all {
            (this as ExtensionAware).extensions.findByName("firebaseCrashlytics")?.withGroovyBuilder { setProperty("mappingFileUploadEnabled", false) }
        }
        dependencies {
            "implementation"(platform("com.google.firebase:firebase-bom:33.5.1"))
            "implementation"("com.google.firebase:firebase-crashlytics")
            "implementation"("com.google.firebase:firebase-perf")
        }
    }
}
