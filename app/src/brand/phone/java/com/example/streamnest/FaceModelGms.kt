package com.example.streamnest

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.OptionalModuleApi
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest

/**
 * 0.9.6 (mlkitUnbundled): Xstream phone only (brand source folder, never synced to StreamX). The face model of "Follow the
 * speaker" lives in Google Play services there; this is the only file that names Play-services classes, so StreamX and
 * the TV apps (bundled model) never need them on their classpath. Loaded by name from SpeakerTracker (FaceModelHook).
 */
class FaceModelGms : FaceModelHook {
    override fun supported(ctx: Context): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    override fun prepare(ctx: Context, detector: Any) {
        if (!supported(ctx)) return
        val api = detector as? OptionalModuleApi ?: return
        val client = ModuleInstall.getClient(ctx)
        client.areModulesAvailable(api).addOnSuccessListener { r ->
            if (!r.areModulesAvailable()) {
                client.installModules(ModuleInstallRequest.newBuilder().addApi(api).build())
                ActLog.add("SUB", "face model: asked Play services to download it")
            }
        }
    }
}
