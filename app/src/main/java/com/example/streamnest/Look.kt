package com.example.streamnest

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The app's look for everyone: in-app logo and the Home logo animation. Chosen in Developer's Corner and published to
 * the cloud (shared/appLook); every phone picks it up when the app opens. The launcher icon follows on the next fresh
 * start, unless the person picked their own icon in Settings.
 */
object AppLook {
    val ANIMS = listOf(
        "broadcast_living" to "Broadcast + Living signal",
        "broadcast" to "Broadcast",
        "tune" to "Tune in",
        "living" to "Living signal",
        "hanging" to "Hanging X",
        "none" to "No animation",
    )
    val logo = MutableStateFlow("signal")
    val anim = MutableStateFlow("broadcast_living")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("look", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        logo.value = prefs(ctx).getString("logo", null) ?: "signal"
        anim.value = prefs(ctx).getString("anim", null) ?: "broadcast_living"
    }

    fun logoRes(id: String): Int = AppIcons.all.firstOrNull { it.id == id }?.res ?: R.drawable.logo_signal

    /** On this phone (Developer's Corner "Use on this phone", or what the cloud says). */
    fun set(ctx: Context, l: String, a: String) {
        logo.value = l; anim.value = a
        prefs(ctx).edit().putString("logo", l).putString("anim", a).apply()
        if (!AppIcons.picked(ctx) && AppIcons.all.any { it.id == l } && AppIcons.active(ctx) != l) AppIcons.setPending(ctx, l)
    }

    /** Call relay for Watch together voice / video (TURN): url(s), username, password. Set in Developer's Corner. */
    val turn = MutableStateFlow(Triple("", "", ""))

    /** The AI logo everyone gets (from the cloud); when set it wins over a per-account logo. */
    val aiLogo = MutableStateFlow("")

    @Volatile private var lastFetch = 0L
    /** The app came to the front: the shared look again (at most every 2 minutes), so a new AI logo shows without a restart. */
    suspend fun refresh(ctx: Context) { if (System.currentTimeMillis() - lastFetch > 2 * 60_000L) fetch(ctx) }

    suspend fun fetch(ctx: Context) = withContext(Dispatchers.IO) {
        lastFetch = System.currentTimeMillis()
        val txt = runCatching { Api.get("${CloudConfig.DB_URL}/shared/appLook.json") }.getOrNull() ?: return@withContext
        val o = runCatching { Api.json.parseToJsonElement(txt).jsonObject }.getOrNull() ?: return@withContext
        fun str(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }
        // AI logo and X's voice: the same on every Xstream account.
        val vm = Public.vm
        str("aiLogo")?.let { p -> aiLogo.value = p; vm?.syncAiLogo(p) }
        if (vm != null) withContext(Dispatchers.Main) {
            str("aiVoice")?.let { v -> if (vm.store("aiVoice") != v) { vm.storePut("aiVoice", v); vm.storePut(if (str("aiVoiceHindi") == "yes") "aiVoiceHi" else "aiVoiceEn", v) } }
            str("aiVoiceRate")?.let { r -> if (r.toFloatOrNull() != null) vm.storePut("aiVoiceRate", r) }
        }
        turn.value = Triple(str("turnUrl") ?: "", str("turnUser") ?: "", str("turnPass") ?: "")
        vpnJson.value = str("vpnServers") ?: "[]"
        Vpn.setShared(vpnJson.value)
        val l = str("logo")?.takeIf { id -> AppIcons.all.any { it.id == id } } ?: return@withContext
        val a = str("anim")?.takeIf { id -> ANIMS.any { it.first == id } } ?: anim.value
        withContext(Dispatchers.Main) { set(ctx, l, a) }
    }

    /** Writes only the given fields of shared/appLook (owner's account only). Null = done. */
    private suspend fun patch(fields: Map<String, String>): String? = withContext(Dispatchers.IO) {
        val t = Cloud.token() ?: return@withContext "Sign in first."
        val body = buildJsonObject { fields.forEach { (k, v) -> put(k, v) }; put("at", System.currentTimeMillis()) }
        runCatching {
            Api.http.newCall(Request.Builder().url("${CloudConfig.DB_URL}/shared/appLook.json?auth=$t")
                .patch(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                if (r.isSuccessful) null else if (r.code == 401 || r.code == 403) "Not allowed: add the Firebase rule for shared/appLook, and use the owner's account." else "HTTP ${r.code}"
            }
        }.getOrElse { "Couldn't reach the cloud." }
    }

    /** Developer's Corner: publish logo + animation for everyone (and this phone's AI logo and voice with it). */
    suspend fun publish(l: String, a: String): String? {
        val vm = Public.vm
        val f = mutableMapOf("logo" to l, "anim" to a)
        vm?.aiLogoNow()?.takeIf { it.isNotBlank() }?.let { f["aiLogo"] = it }
        vm?.store("aiVoice")?.takeIf { it.isNotBlank() }?.let { f["aiVoice"] = it }
        vm?.store("aiVoiceRate")?.takeIf { it.isNotBlank() }?.let { f["aiVoiceRate"] = it }
        return patch(f)
    }

    /** Xstream VPN servers for everyone (JSON list of SxServer: name, country, address, public key — no secrets). */
    val vpnJson = MutableStateFlow("[]")
    suspend fun publishVpn(json: String): String? { val r = patch(mapOf("vpnServers" to json)); if (r == null) { vpnJson.value = json; Vpn.setShared(json) }; return r }

    /** Developer's Corner › Call relay: for everyone. */
    suspend fun publishTurn(url: String, user: String, pass: String): String? {
        val r = patch(mapOf("turnUrl" to url.trim(), "turnUser" to user.trim(), "turnPass" to pass.trim()))
        if (r == null) turn.value = Triple(url.trim(), user.trim(), pass.trim())
        return r
    }

    /** The developer changed the AI logo or X's voice: it goes to every Xstream account right away. */
    fun publishAi(logo: String? = null, voice: String? = null, hindi: Boolean = false, rate: String? = null) {
        val f = mutableMapOf<String, String>()
        logo?.let { f["aiLogo"] = it; aiLogo.value = it }
        voice?.let { f["aiVoice"] = it; f["aiVoiceHindi"] = if (hindi) "yes" else "no" }
        rate?.let { f["aiVoiceRate"] = it }
        if (f.isEmpty()) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val err = patch(f)
            withContext(Dispatchers.Main) { if (err != null) Snack.show("Not sent to everyone: $err") else Snack.show(if (logo != null) "AI logo sent to every account" else "Voice set for every account") }
        }
    }
}

private object SignalOnce { var played = false }

/**
 * The Signal X mark (white X + three red waves on a dark tile), animated:
 * Broadcast — the X draws stroke by stroke, the waves ripple out once; Tune in — the waves flicker on like a signal
 * finding its connection, then the X pops; Living signal — a soft ripple every 6 s ([paused] while scrolling).
 * Plays once per app start.
 */
@Composable
fun SignalMark(size: Dp, anim: String, paused: Boolean = false, standalone: Boolean = false, onIntroDone: () -> Unit = {}) {
    val intro = anim == "broadcast" || anim == "broadcast_living" || anim == "tune"
    val first = remember { standalone || !SignalOnce.played }
    val x1 = remember { Animatable(if (anim == "broadcast" || anim == "broadcast_living") (if (first) 0f else 1f) else 1f) }
    val x2 = remember { Animatable(x1.value) }
    val w = remember { List(3) { Animatable(if (intro && first) 0f else 1f) } }
    val pop = remember { Animatable(1f) }
    val pulse = remember { Animatable(0f) }
    val pausedNow by rememberUpdatedState(paused)
    LaunchedEffect(anim) {
        if (intro && first) {
            if (!standalone) SignalOnce.played = true
            if (anim == "tune") {
                // Flicker: waves come and go, then lock; the X gives a small "connected" pop.
                val steps = listOf(0 to 1f, 1 to 0.7f, 2 to 0.4f, 1 to 0f, 2 to 0f, 1 to 0.7f, 2 to 0.4f)
                delay(200)
                for ((i, v) in steps) { w[i].snapTo(if (v == 0f) 0.15f else 1f); delay(180) }
                w[0].snapTo(1f)
                pop.animateTo(1.18f, tween(140)); pop.animateTo(1f, tween(200))
            } else {
                delay(150)
                x1.animateTo(1f, tween(350, easing = FastOutSlowInEasing))
                x2.animateTo(1f, tween(350, easing = FastOutSlowInEasing))
                coroutineScope {
                    w.forEachIndexed { i, a -> launch { delay(150L * i); a.animateTo(1f, tween(600, easing = FastOutSlowInEasing)) } }
                }
            }
            onIntroDone()
        } else onIntroDone()
        if (anim == "living" || anim == "broadcast_living") {
            while (true) {
                delay(6_000)
                if (pausedNow) continue
                pulse.animateTo(1f, tween(260)); pulse.animateTo(0f, tween(420))
            }
        }
    }
    val red = BrandRed
    Canvas(Modifier.size(size).graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
        val k = this.size.minDimension / 100f
        drawRoundRect(Color(0xFF121216), size = this.size, cornerRadius = CornerRadius(23f * k))
        val white = Color(0xFFF4F4F6)
        // Xstream: a red X with white signal waves (StreamX's colours swapped).
        fun line(a: Offset, b: Offset, p: Float) { if (p > 0f) drawLine(red, a * k, (a + (b - a) * p) * k, 9f * k, StrokeCap.Round) }
        line(Offset(22f, 36f), Offset(46f, 64f), x1.value)
        line(Offset(22f, 64f), Offset(46f, 36f), x2.value)
        val radii = listOf(20f, 29f, 38f); val alphas = listOf(1f, 0.7f, 0.4f)
        for (i in 0..2) {
            val appear = w[i].value
            if (appear <= 0f) continue
            val sc = (0.6f + 0.4f * appear) * (1f + 0.12f * pulse.value)
            val al = (alphas[i] * appear + (1f - alphas[i]) * pulse.value * 0.6f).coerceIn(0f, 1f)
            scale(sc, pivot = Offset(34f * k, 50f * k)) {
                val r = radii[i] * k
                drawArc(white.copy(alpha = al), -40f, 80f, false, topLeft = Offset(40f * k - r, 50f * k - r), size = Size(r * 2, r * 2),
                    style = Stroke(6f * k, cap = StrokeCap.Round))
            }
        }
    }
}

