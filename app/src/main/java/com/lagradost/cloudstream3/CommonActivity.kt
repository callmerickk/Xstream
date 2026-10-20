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
        fun setKey(path: String, value: String?) { context?.getSharedPreferences("cs_keys", Context.MODE_PRIVATE)?.edit()?.putString(path, value)?.apply() }
    }
}
