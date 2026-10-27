package com.example.streamnest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/*
 * June & Aiden: cut out of the Gemini video, upper body, each one on their own and standing straight, with a red
 * light on their edges and the chest melting into blur (baked into the frames, assets/avatars/<name>/).
 *
 * Motion: whole video frames, never a hard swap. Frames are blended into each other (smooth at 60-120 Hz),
 * a clip only jumps to a frame that looks the same (meta.json "jumps"), changes between clips glide the body from
 * one position to the other while they dissolve, and a little breathing and head sway keep them alive.
 * Talking: real mouth shapes cut from the talking part of the video (mouth.webp), picked by how loud the voice
 * is at the speaker right now.
 */

enum class AiBuddy(val label: String) { June("June"), Aiden("Aiden");
    val other: AiBuddy get() = if (this == June) Aiden else June
    companion object { fun of(s: String?): AiBuddy = if (s.equals("aiden", true)) Aiden else June }
}

/** What's in assets/avatars/<name>/meta.json. */
class AvatarMeta(o: JsonObject) {
    val fps = (o["fps"] as? JsonPrimitive)?.intOrNull ?: 24
    val w = (o["w"] as JsonPrimitive).intOrNull ?: 400
    val h = (o["h"] as JsonPrimitive).intOrNull ?: 440
    val count = (o["count"] as JsonPrimitive).intOrNull ?: 0
    /** Where the face centre is (frame px), for placing them. */
    val faceX = (o["faceX"] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: (w / 2f)
    val faceY = (o["faceY"] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: (h * 0.3f)
    /** Calm frames (smiling, listening): played as an endless, never-repeating walk. */
    val calm: IntArray = (o["calm"] as JsonArray).map { (it as JsonPrimitive).intOrNull ?: 0 }.toIntArray()
    /** Frames they talk in (head moves like when talking): played as a loop while their voice plays. */
    val speak: IntArray = (o["speak"] as? JsonArray)?.map { (it as JsonPrimitive).intOrNull ?: 0 }?.toIntArray() ?: calm
    private val calmSet = calm.toHashSet()
    private val speakSet = speak.toHashSet()
    fun inLoop(loop: String, i: Int) = if (loop == "speak") i in speakSet else i in calmSet
    fun loop(loop: String) = if (loop == "speak") speak else calm
    /** Clips played once: name -> [from, to]. */
    val clips: Map<String, IntArray> = (o["clips"] as JsonObject).mapValues { (_, v) -> v.jsonArray.map { (it as JsonPrimitive).intOrNull ?: 0 }.toIntArray() }
    /** For each calm frame: other calm frames that look almost the same (safe places to jump to). */
    val jumps: Map<Int, IntArray> = (o["jumps"] as JsonObject).entries.associate { (k, v) -> k.toInt() to v.jsonArray.map { (it as JsonPrimitive).intOrNull ?: 0 }.toIntArray() }
    /** Mouth centre in each frame. */
    val mouth: Array<FloatArray> = (o["mouth"] as JsonArray).map { p -> p.jsonArray.map { (it as JsonPrimitive).doubleOrNull?.toFloat() ?: 0f }.toFloatArray() }.toTypedArray()
    val patchW = (o["patchW"] as? JsonPrimitive)?.intOrNull ?: 0
    val patchH = (o["patchH"] as? JsonPrimitive)?.intOrNull ?: 0
    val patchDy = (o["patchDy"] as? JsonPrimitive)?.intOrNull ?: 0
    val patches = (o["patches"] as? JsonPrimitive)?.intOrNull ?: 0
}

/** Decodes and caches one buddy's frames (a few ahead, on a background thread). Shared, so reopening is instant. */
class AvatarFrames private constructor(private val ctx: Context, val buddy: AiBuddy) {
    private val dir = "avatars/${buddy.name.lowercase()}"
    val meta: AvatarMeta? = runCatching {
        ctx.assets.open("$dir/meta.json").use { s -> AvatarMeta(Api.json.parseToJsonElement(s.readBytes().decodeToString()).jsonObject) }
    }.onFailure { ActLog.add("AI", "avatar meta missing: ${it.message}") }.getOrNull()
    val count: Int get() = meta?.count ?: 0
    private val cache = object : LruCache<Int, Bitmap>(26) {}
    private val pending = HashSet<Int>()
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "avatar-${buddy.name}").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true } }
    @Volatile var mouthAtlas: Bitmap? = null; private set

    init {
        exec.execute { mouthAtlas = runCatching { ctx.assets.open("$dir/mouth.webp").use { BitmapFactory.decodeStream(it) } }.getOrNull() }
    }

    fun get(i: Int): Bitmap? = cache.get(i)

    fun want(list: IntArray) {
        for (f in list) {
            if (f < 0 || f >= count) continue
            val skip = synchronized(pending) { cache.get(f) != null || !pending.add(f) }
            if (skip) continue
            exec.execute {
                val b = runCatching {
                    ctx.assets.open("$dir/f%03d.webp".format(f)).use { s ->
                        BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                    }
                }.getOrNull()
                if (b != null) cache.put(f, b)
                synchronized(pending) { pending.remove(f) }
            }
        }
    }

    companion object {
        private val all = HashMap<AiBuddy, AvatarFrames>()
        fun of(ctx: Context, b: AiBuddy): AvatarFrames = synchronized(all) { all.getOrPut(b) { AvatarFrames(ctx.applicationContext, b) } }
        /** Warm the first frames up early, so they appear without a blank moment. */
        fun warm(ctx: Context) { AiBuddy.entries.forEach { b -> val f = of(ctx, b); f.meta?.let { m -> f.want(m.calm.take(10).toIntArray() + m.speak.take(6).toIntArray()) } } }
    }
}

/**
 * Plays one buddy. Drive it from the UI: [play] a move ("wave", "laugh", "talk"), set [talking] while their voice
 * plays and feed [level] (0..1, loudness at the speaker now).
 */
class AvatarPlayer(val frames: AvatarFrames) {
    private val m get() = frames.meta
    var clip = "calm"; private set
    var f0 = 0; private set
    var f1 = 1; private set
    var t = 0f; private set
    private var end = 0
    private var then: String? = null
    var talking = false
    /** Voice loudness at the speaker (set every frame while talking). */
    var level = 0f
    var lvl = 0f; private set
    var patchA = 0f; private set
    var time = 0.0; private set
    /** The clip being left: drawn on top, dissolving while the body glides to the new spot. */
    class Ghost(val f0: Int, val f1: Int, val t: Float) { var k = 0f }
    var ghost: Ghost? = null; private set
    var tick by mutableIntStateOf(0); private set
    private val rnd = java.util.Random()

    init {
        m?.let { mm -> if (mm.calm.isNotEmpty()) { f0 = mm.calm[rnd.nextInt(minOf(8, mm.calm.size))]; f1 = nextLoop(f0) } }
    }

    private fun isLoop(c: String) = c == "calm" || c == "speak"

    /** Next frame of the loop we're in: forward, or now and then a jump to a frame that looks the same (never back and forth). */
    private fun nextLoop(f: Int): Int {
        val mm = m ?: return 0
        val fwd = f + 1
        val ok = mm.inLoop(clip, fwd)
        if (ok && rnd.nextFloat() > 0.035f) return fwd
        val list = mm.jumps[if (ok) fwd else f] ?: mm.jumps[f]
        if (list == null || list.isEmpty()) return mm.loop(clip).first()
        return list[rnd.nextInt(list.size)]
    }

    fun play(name: String) {
        val mm = m ?: return
        if (isLoop(name)) { toLoop(name); return }
        val c = mm.clips[name] ?: return
        if (clip == name) return
        ghost = Ghost(f0, f1, t)
        clip = name; f0 = c[0]; f1 = minOf(c[0] + 1, c[1]); t = 0f; end = c[1]
    }

    private fun toLoop(name: String) {
        val mm = m ?: return
        if (clip == name) return
        // land on the frame of that loop whose position is closest to where we are
        val p = mm.mouth.getOrNull(f0) ?: floatArrayOf(0f, 0f)
        val list = mm.loop(name)
        var best = list[0]; var bd = Float.MAX_VALUE
        for (c in list) { val q = mm.mouth[c]; val d = (p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1]); if (d < bd) { bd = d; best = c } }
        ghost = Ghost(f0, f1, t)
        clip = name; f0 = best; f1 = nextLoop(best); t = 0f
    }
    private fun toCalm() = toLoop(if (talking) "speak" else "calm")

    /** Advance by [dt] seconds. */
    fun step(dt: Float) {
        val mm = m ?: return
        time += dt
        // the voice: fast up, slower down
        val target = if (talking) level else 0f
        val tau = if (target > lvl) 0.035f else 0.09f
        lvl += (target - lvl) * (1f - exp(-dt / tau))
        val want = if (talking || lvl > 0.03f) 1f else 0f
        patchA += (want - patchA) * (1f - exp(-dt / 0.12f))
        // talking: the head moves like when they talk (the speak loop); listening: calm. Once-off clips finish first.
        if (isLoop(clip) && ghost == null) { val want2 = if (talking) "speak" else "calm"; if (clip != want2) toLoop(want2) }
        val nextNeeded = frames.get(f1) != null
        if (nextNeeded) t += dt * mm.fps
        while (t >= 1f) {
            t -= 1f; f0 = f1
            if (isLoop(clip)) f1 = nextLoop(f0)
            else if (f0 >= end) { val n = then; then = null; if (n != null) play(n) else toCalm() }
            else f1 = f0 + 1
        }
        ghost?.let { g -> g.k += dt / 0.34f; if (g.k >= 1f) ghost = null }
        // decode ahead
        val ahead = IntArray(6)
        var x = f1
        for (k in 0 until 6) { ahead[k] = x; x = if (isLoop(clip)) (if (mm.inLoop(clip, x + 1)) x + 1 else x) else minOf(x + 1, end) }
        frames.want(intArrayOf(f0) + ahead)
        tick++
    }

    fun mouthAt(f0: Int, f1: Int, t: Float): FloatArray {
        val mm = m ?: return floatArrayOf(0f, 0f)
        val a = mm.mouth.getOrNull(f0) ?: return floatArrayOf(0f, 0f)
        val b = mm.mouth.getOrNull(f1) ?: a
        return floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
    }
}

/**
 * Draws a buddy filling [modifier]'s box: their face lands at ([faceAt] across, [faceTop] down), [scale] = how
 * much of the width their frame takes (1 = the frame's width equals the box width).
 */
@Composable
fun AvatarView(player: AvatarPlayer, modifier: Modifier, faceAt: Float = 0.5f, faceTop: Float = 0.30f, scale: Float = 1f, alive: Boolean = true, onFrame: (Float) -> Unit = {}) {
    LaunchedEffect(player) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                onFrame(dt)
                player.step(dt)
            }
        }
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val src = remember { Rect() }
    val dst = remember { RectF() }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") player.tick
        val mm = player.frames.meta ?: return@Canvas
        val k = size.width / mm.w * scale
        val ox = size.width * faceAt - mm.faceX * k
        val oy = size.height * faceTop - mm.faceY * k
        val tm = player.time.toFloat()
        // alive: breathing, a little sway, nods with the voice
        var rot = 0f; var tx = 0f; var ty = 0f; var sc = 1f
        if (alive) {
            rot = (0.7f * sin(tm * 2.1f + 1f) + 0.4f * sin(tm * 5.3f) + 0.2f * sin(tm * 0.7f))
            tx = 2.0f * sin(tm * 0.9f) + 0.8f * sin(tm * 2.3f)
            ty = 1.4f * sin((tm * 2f * PI / 4.2f).toFloat())
            sc = 1f + 0.006f * sin((tm * 2f * PI / 4.2f + 0.6f).toFloat())
            ty += player.lvl * 2.4f
            rot += player.lvl * 0.7f * sin(tm * 7.1f)
        }
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.translate(ox, oy)
            nc.scale(k, k)
            // pivot at the bottom centre (the chest), so the head moves most
            nc.translate(mm.w / 2f + tx, mm.h + ty)
            nc.rotate(rot)
            nc.scale(sc, sc)
            nc.translate(-mm.w / 2f, -mm.h.toFloat())
            fun frame(i: Int, alpha: Float, dx: Float, dy: Float) {
                if (alpha <= 0.004f) return
                val b = player.frames.get(i) ?: return
                paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
                nc.drawBitmap(b, dx, dy, paint)
            }
            fun pair(f0: Int, f1: Int, t: Float, a: Float, dx: Float, dy: Float) {
                frame(f0, a, dx, dy)
                if (t > 0.01f && player.frames.get(f1) != null) frame(f1, a * t, dx, dy)
            }
            val g = player.ghost
            var offX = 0f; var offY = 0f
            if (g != null) {
                val e = g.k.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
                val gp = player.mouthAt(g.f0, g.f1, g.t); val np = player.mouthAt(player.f0, player.f1, player.t)
                offX = (gp[0] - np[0]) * (1 - e); offY = (gp[1] - np[1]) * (1 - e)
                pair(player.f0, player.f1, player.t, 1f, offX, offY)
                pair(g.f0, g.f1, g.t, 1f - e, (np[0] - gp[0]) * e, (np[1] - gp[1]) * e)
            } else pair(player.f0, player.f1, player.t, 1f, 0f, 0f)
            // the real mouth, picked by the voice (only on calm frames: the clips have their own talking mouth)
            val atlas = player.frames.mouthAtlas
            if (atlas != null && (player.clip == "calm" || player.clip == "speak") && g == null && player.patchA > 0.01f && mm.patches > 0) {
                val p = player.mouthAt(player.f0, player.f1, player.t)
                val x = (player.lvl * 1.05f).coerceIn(0f, 1f) * (mm.patches - 1)
                val i0 = x.toInt(); val i1 = minOf(mm.patches - 1, i0 + 1); val fr = x - i0
                dst.set(p[0] - mm.patchW / 2f, p[1] - mm.patchH / 2f + mm.patchDy, p[0] + mm.patchW / 2f, p[1] + mm.patchH / 2f + mm.patchDy)
                paint.alpha = (player.patchA * 255).toInt().coerceIn(0, 255)
                src.set(i0 * mm.patchW, 0, (i0 + 1) * mm.patchW, mm.patchH); nc.drawBitmap(atlas, src, dst, paint)
                if (fr > 0.02f) {
                    paint.alpha = (player.patchA * fr * 255).toInt().coerceIn(0, 255)
                    src.set(i1 * mm.patchW, 0, (i1 + 1) * mm.patchW, mm.patchH); nc.drawBitmap(atlas, src, dst, paint)
                }
            }
            nc.restore()
        }
    }
}

@Composable
fun rememberAvatarPlayer(buddy: AiBuddy): AvatarPlayer {
    val ctx = LocalContext.current
    return remember(buddy) { AvatarPlayer(AvatarFrames.of(ctx, buddy)) }
}
