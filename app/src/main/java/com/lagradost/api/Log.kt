package com.lagradost.api

/** Xstream's own stand-in for CloudStream's logger: plugin messages go to Android's log and the plugin test log. */
object Log {
    @JvmStatic fun d(tag: String, message: String) { android.util.Log.d(tag, message); com.lagradost.cloudstream3.CsBridge.log("D", tag, message) }
    @JvmStatic fun i(tag: String, message: String) { android.util.Log.i(tag, message); com.lagradost.cloudstream3.CsBridge.log("I", tag, message) }
    @JvmStatic fun w(tag: String, message: String) { android.util.Log.w(tag, message); com.lagradost.cloudstream3.CsBridge.log("W", tag, message) }
    @JvmStatic fun e(tag: String, message: String) { android.util.Log.e(tag, message); com.lagradost.cloudstream3.CsBridge.log("E", tag, message) }
    @JvmStatic fun v(tag: String, message: String) { android.util.Log.v(tag, message) }
}
