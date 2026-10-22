package com.example.streamnest

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * A see-through screen that hosts a CloudStream plugin's own settings (they expect an AppCompat activity with a
 * fragment manager). Closes itself when the plugin's dialog is gone.
 */
class CsSettingsActivity : AppCompatActivity() {
    companion object {
        fun open(ctx: Context, key: String) {
            ctx.startActivity(Intent(ctx, CsSettingsActivity::class.java).putExtra("key", key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CommonActivity.activity = this
        val key = intent.getStringExtra("key")
        val plug = key?.let { CsPlugins.loaded[it]?.plugin } as? Plugin
        val open = plug?.openSettings
        if (open == null) { finish(); return }
        supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
                if (fm.fragments.none { it !== f && it.isAdded }) finish()
            }
        }, false)
        guardMainLoop()
        runCatching { open(this) }.onFailure { com.lagradost.cloudstream3.mvvm.logError(it); finish() }
    }

    /**
     * A plugin's settings screen that throws while it builds its views would take the whole app down (it happens inside
     * Android's fragment code, outside our try). While this screen is open, such an error only closes it.
     */
    private fun guardMainLoop() {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        h.post {
            while (!isDestroyed) {
                try { android.os.Looper.loop() } catch (e: Throwable) {
                    val fromPlugin = e.stackTrace.any { el -> !el.className.startsWith("android") && !el.className.startsWith("java") &&
                        !el.className.startsWith("com.example") && !el.className.startsWith("kotlin") && !el.className.startsWith("com.google.android.material") &&
                        !el.className.startsWith("dalvik") && !el.className.startsWith("com.android") } ||
                        (e is IllegalArgumentException && e.message?.contains("Theme.MaterialComponents") == true)
                    if (!fromPlugin || isDestroyed) throw e
                    ActLog.add("CSPLAY", "plugin settings failed: ${e.javaClass.simpleName} ${e.message?.take(120)}")
                    Snack.show("This plugin's settings couldn't open")
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        if (CommonActivity.activity === this) CommonActivity.activity = null
        super.onDestroy()
    }
}
