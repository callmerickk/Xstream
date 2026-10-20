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
        runCatching { open(this) }.onFailure { com.lagradost.cloudstream3.mvvm.logError(it); finish() }
    }

    override fun onDestroy() {
        if (CommonActivity.activity === this) CommonActivity.activity = null
        super.onDestroy()
    }
}
