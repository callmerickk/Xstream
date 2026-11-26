package com.example.streamnest

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Music prompt 5 (switch musicLook2): the Music theme, separate from Xstream's own theme.
 * Saved in the "music" prefs, one set of keys per profile ("look2_<profile>_look / glass / follow").
 *  - look   : one of the 5 existing [MusicLook] colour looks (the old global "look" key is only read as a first value).
 *  - glass  : frosted bars, cards and sheets over a blurred copy of the playing cover.
 *  - follow : background and accent come from the playing cover.
 * With the switch off nothing here is read or written, and [MusicLook] works exactly as before.
 */
object MusicLook2 {
    private const val PREFS = "music"
    private val _glass = MutableStateFlow(false)
    private val _follow = MutableStateFlow(false)
    val glass: StateFlow<Boolean> = _glass.asStateFlow()
    val follow: StateFlow<Boolean> = _follow.asStateFlow()

    @Volatile private var pid = ""
    @Volatile private var wifiWarned = false

    private fun safe(id: String) =
        id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(80).ifBlank { "default" }

    private fun key(name: String) = "look2_${safe(pid)}_$name"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Reads this profile's look, Glass and Follow the song, and puts the look into [MusicLook.current]. */
    fun load(ctx: Context, profileId: String) {
        if (!Feature.musicLook2) return
        pid = profileId
        val p = prefs(ctx)
        val own = p.getString(key("look"), null)
        val name = own ?: p.getString("look", null) ?: "Elegant"
        val look = MusicLook.all.firstOrNull { it.name == name } ?: MusicLook.all[0]
        MusicLook.current.value = look
        _glass.value = p.getBoolean(key("glass"), false)
        _follow.value = p.getBoolean(key("follow"), false)
        MusicLog.add(
            "Music look: profile \"${safe(profileId)}\" -> ${look.name}" +
                (if (own == null) " (first time: taken from the old global look)" else "") +
                ", Glass ${if (_glass.value) "on" else "off"}, Follow the song ${if (_follow.value) "on" else "off"}"
        )
    }

    /** Picks one of the 5 colour looks for this profile (the old global key is left alone). */
    fun setLook(ctx: Context, look: MusicLook.Look) {
        MusicLook.current.value = look
        prefs(ctx).edit().putString(key("look"), look.name).apply()
        MusicLog.add("Music look: ${look.name} chosen")
    }

    fun setGlass(ctx: Context, on: Boolean) {
        _glass.value = on
        prefs(ctx).edit().putBoolean(key("glass"), on).apply()
        MusicLog.add("Music look: Glass ${if (on) "on" else "off"}" +
            if (on && android.os.Build.VERSION.SDK_INT < 31) " (this Android can't blur, a dark scrim over the cover is used instead)" else "")
    }

    fun setFollow(ctx: Context, on: Boolean) {
        _follow.value = on
        prefs(ctx).edit().putBoolean(key("follow"), on).apply()
        MusicLog.add("Music look: Follow the song ${if (on) "on" else "off"}")
    }

    /** True only on Wi-Fi (the canvas clip uses this). Needs ACCESS_NETWORK_STATE; without it the answer is false and is logged once. */
    fun onWifi(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.onFailure {
        if (!wifiWarned) {
            wifiWarned = true
            MusicLog.add("Music look: could not read the network type (${it.javaClass.simpleName}); treated as not Wi-Fi")
        }
    }.getOrDefault(false)

    /** Blur needs Android 12 or newer (Modifier.blur does nothing below). */
    val canBlur: Boolean get() = android.os.Build.VERSION.SDK_INT >= 31
}
