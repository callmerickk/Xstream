package com.example.streamnest

/**
 * The app's identity. Every value is set in app/build.gradle.kts (buildConfigField), so StreamX and Xstream are built
 * from the same code: only the Gradle file (and the brand resources it adds) differs per app.
 */
object Brand {
    /** "StreamX" or "Xstream": the name shown to people. */
    const val NAME = BuildConfig.BRAND_NAME
    /** true = the testing app (Xstream). Feature switches default to on there. */
    const val IS_BETA = BuildConfig.IS_BETA
    /** GitHub owner/repo whose releases are this app's updates. */
    const val UPDATE_REPO = BuildConfig.UPDATE_REPO
    const val REPO_URL = "https://github.com/" + UPDATE_REPO
    /** Download relay in front of GitHub's release files; "" = GitHub only. */
    const val UPDATE_CDN = BuildConfig.UPDATE_CDN
    /** Release file names the updater downloads. */
    const val APK_PHONE = BuildConfig.APK_PHONE
    const val APK_TV = BuildConfig.APK_TV
    /** 0.9.6: the 32-bit phone APK ("" = none: one APK for both ABIs, as StreamX). */
    const val APK_PHONE_ARM32 = BuildConfig.APK_PHONE_ARM32
    /** The developer's cloud shelf (Firebase): "shared" for StreamX, "xshared" for Xstream. */
    const val SHARED_ROOT = BuildConfig.SHARED_ROOT
    /** Watch together invites: <scheme>://join/CODE and https://<host><path>/CODE (also in the manifest). */
    const val INVITE_SCHEME = BuildConfig.INVITE_SCHEME
    const val INVITE_HOST = BuildConfig.INVITE_HOST
    const val INVITE_PATH = BuildConfig.INVITE_PATH
    const val INVITE_URL = "https://" + INVITE_HOST + INVITE_PATH
    /** Wordmark: "Xstream" starts with the red X, "StreamX" ends with it. */
    val X_FIRST: Boolean get() = NAME.startsWith("X")
}
