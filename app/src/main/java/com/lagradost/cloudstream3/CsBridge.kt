package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.utils.ExtractorApi
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Xstream ↔ plugin glue (our own code, not part of CloudStream): where registered providers / extractors go, and where
 * plugin log lines and unhandled extractor hosts are reported for the plugin test.
 */
object CsBridge {
    class Registered(val plugin: BasePlugin, val pluginName: String) {
        val apis = CopyOnWriteArrayList<MainAPI>()
        val extractors = CopyOnWriteArrayList<ExtractorApi>()
    }
    /** The plugin whose load() is running now (registrations go to it). */
    @Volatile var loading: Registered? = null
    val allExtractors = CopyOnWriteArrayList<ExtractorApi>()
    /** Log sink (set by Xstream's plugin test). */
    @Volatile var sink: ((String) -> Unit)? = null
    fun log(level: String, tag: String, msg: String) { sink?.invoke("[$level/$tag] ${msg.take(400)}") }
    fun note(msg: String) { sink?.invoke(msg) }

    internal fun registerMain(api: MainAPI) { loading?.apis?.add(api); log("I", "Register", "provider ${api.name} · ${api.mainUrl}") }
    internal fun registerExtractor(e: ExtractorApi) {
        loading?.extractors?.add(e)
        allExtractors.removeAll { it.javaClass.name == e.javaClass.name && it.mainUrl == e.mainUrl }
        allExtractors.add(e)
    }
}
