package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CsBridge
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.ExtractorApi

/** Marks a plugin's main class (read from its manifest by Xstream). */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class CloudstreamPlugin

abstract class BasePlugin {
    @Suppress("PropertyName") var __filename: String? = null
    open fun load() {}
    open fun beforeUnload() {}
    fun registerMainAPI(element: MainAPI) {
        element.sourcePlugin = __filename
        APIHolder.allProviders.removeAll { it.name == element.name && it.javaClass.name == element.javaClass.name }
        APIHolder.allProviders.add(element)
        CsBridge.registerMain(element)
    }
    fun registerExtractorAPI(element: ExtractorApi) { CsBridge.registerExtractor(element) }
}

abstract class Plugin : BasePlugin() {
    open fun load(context: Context) { load() }
    /** The plugin's own resources (layouts, images), when its manifest asks for them. */
    var resources: Resources? = null
    /** Set by plugins that have a settings screen. */
    var openSettings: ((context: Context) -> Unit)? = null
}
