package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object CommonActivity {
    /** The screen plugins may use (Xstream's plugin settings screen while it's open). */
    @Volatile var activity: Activity? = null
    fun showToast(message: String?, duration: Int? = null) {
        val ctx: Context = activity ?: CloudStreamApp.context ?: return
        if (message.isNullOrBlank()) return
        Handler(Looper.getMainLooper()).post { runCatching { Toast.makeText(ctx, message, duration ?: Toast.LENGTH_SHORT).show() } }
        CsBridge.note("toast: $message")
    }
    fun showToast(act: Activity?, message: String?, duration: Int? = null) = showToast(message, duration)
}

class CloudStreamApp {
    companion object {
        @Volatile var context: Context? = null
        fun getKey(path: String): String? = context?.getSharedPreferences("cs_keys", Context.MODE_PRIVATE)?.getString(path, null)
        /** Stored as text: strings as they are, anything else as JSON (like CloudStream). */
        fun <T> setKey(path: String, value: T) {
            val s: String? = when (value) { null -> null; is String -> value; else -> runCatching { mapper.writeValueAsString(value) }.getOrNull() }
            context?.getSharedPreferences("cs_keys", Context.MODE_PRIVATE)?.edit()?.putString(path, s)?.apply()
        }
        fun <T> setKey(folder: String, path: String, value: T) = setKey("$folder/$path", value)
        fun getKey(folder: String, path: String): String? = getKey("$folder/$path")
        fun removeKey(path: String) { context?.getSharedPreferences("cs_keys", Context.MODE_PRIVATE)?.edit()?.remove(path)?.apply() }
    }
}
