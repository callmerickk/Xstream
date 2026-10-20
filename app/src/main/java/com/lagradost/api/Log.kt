package com.lagradost.api

/** (Instance methods, not static: plugins call them on Log.INSTANCE.) Xstream's own stand-in for CloudStream's logger: plugin messages go to Android's log and the plugin test log. */
object Log {
    fun d(tag: String, message: String) { android.util.Log.d(tag, message); com.lagradost.cloudstream3.CsBridge.log("D", tag, message) }
    fun i(tag: String, message: String) { android.util.Log.i(tag, message); com.lagradost.cloudstream3.CsBridge.log("I", tag, message) }
    fun w(tag: String, message: String) { android.util.Log.w(tag, message); com.lagradost.cloudstream3.CsBridge.log("W", tag, message) }
    fun e(tag: String, message: String) { android.util.Log.e(tag, message); com.lagradost.cloudstream3.CsBridge.log("E", tag, message) }
    fun v(tag: String, message: String) { android.util.Log.v(tag, message) }
}
