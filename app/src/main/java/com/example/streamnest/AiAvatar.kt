package com.example.streamnest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp

/*
 * June & Aiden: the two assistants, animated from the Gemini video you made (cut out with a red rim light).
 * Frames ship in assets/avatars/<name>/fNNN.webp (512 × 288, 24 fps). They are decoded a few frames ahead on
 * a background thread, so the screen never waits on a decode (no stutter). The mouth follows the real voice:
 * while the assistant talks, the jaw opens with the loudness of the audio that is playing at that moment.
 */

enum class AiBuddy(val label: String) { June("June"), Aiden("Aiden");
    val other: AiBuddy get() = if (this == June) Aiden else June
    companion object { fun of(s: String?): AiBuddy = if (s.equals("aiden", true)) Aiden else June }
}

/** A part of the video: frames [from]..[to]. [loop] clips play back and forth until something else starts. */
data class AvClip(val from: Int, val to: Int, val loop: Boolean)

object AvatarSpec {
    const val FPS = 24
    const val W = 512
    const val H = 288
    val clips: Map<AiBuddy, Map<String, AvClip>> = mapOf(
        AiBuddy.June to mapOf(
            "idle" to AvClip(0, 24, true), "wave" to AvClip(34, 70, false), "talk" to AvClip(80, 140, true),
            "smile" to AvClip(144, 196, false), "laugh" to AvClip(84, 122, false),
        ),
        AiBuddy.Aiden to mapOf(
            "idle" to AvClip(0, 40, true), "laugh" to AvClip(56, 108, false), "talk" to AvClip(114, 170, true),
            "smile" to AvClip(114, 170, false), "wave" to AvClip(174, 199, false),
        ),
    )
    /** Mouth on the talking frames (frame pixels): centre x, lip line y, half width, chin y; face centre x. */
    val mouth: Map<AiBuddy, FloatArray> = mapOf(
        AiBuddy.June to floatArrayOf(266f, 207f, 32f, 247f, 266f),
        AiBuddy.Aiden to floatArrayOf(247f, 207f, 38f, 257f, 250f),
    )
}

/** Decodes and caches one buddy's frames. Shared, so opening the card again is instant. */
class AvatarFrames private constructor(private val ctx: Context, val buddy: AiBuddy) {
    val count: Int = runCatching { ctx.assets.list("avatars/${buddy.name.lowercase()}")?.count { it.endsWith(".webp") } ?: 0 }.getOrDefault(0)
    private val cache = object : LruCache<Int, Bitmap>(36) {}
    private val pending = HashSet<Int>()
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "avatar-${buddy.name}").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true } }

    fun get(i: Int): Bitmap? = cache.get(i)

    /** Ask for [i] and the next few frames to be ready. */
    fun want(i: Int, ahead: List<Int>) {
        (listOf(i) + ahead).forEach { f ->
            if (f < 0 || f >= count) return@forEach
            synchronized(pending) { if (cache.get(f) != null || !pending.add(f)) return@forEach }
            exec.execute {
                val b = runCatching {
                    ctx.assets.open("avatars/${buddy.name.lowercase()}/f%03d.webp".format(f)).use { s ->
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
        /** Warm the first frames up early (app start), so the welcome appears without a blank moment. */
        fun warm(ctx: Context) { AiBuddy.entries.forEach { b -> of(ctx, b).want(0, (1..12).toList()) } }
    }
}

/**
 * Plays the clips: loops idle / talk back and forth, plays wave / smile / laugh once and returns, cross-fades
 * between clips. Drive it from the UI: [play] a move, set [talking] while the voice plays, and feed [mouth].
 */
class AvatarPlayer(val frames: AvatarFrames) {
    private val spec get() = AvatarSpec.clips.getValue(frames.buddy)
    var clip = "idle"; private set
    private var pos = 0.0           // fractional frame inside the clip timeline
    private var dir = 1
    private var then: String? = null
    var talking = false
    /** 0..1: how open the jaw is right now (from the voice). */
    var mouth by mutableFloatStateOf(0f)
    var frame by mutableIntStateOf(0); private set
    var prevFrame by mutableIntStateOf(-1); private set
    var fade by mutableFloatStateOf(1f); private set
    var tick by mutableIntStateOf(0); private set

    fun play(name: String, after: String? = null) {
        val c = spec[name] ?: return
        if (name == clip && c.loop) return
        prevFrame = frame; fade = 0f
        clip = name; then = after; dir = 1
        pos = c.from.toDouble()
    }

    /** Advance by [dt] seconds. */
    fun step(dt: Double) {
        val c = spec[clip] ?: return
        if (talking && clip == "idle") play("talk")
        if (!talking && clip == "talk") play("idle")
        pos += dir * dt * AvatarSpec.FPS
        if (pos > c.to) {
            if (c.loop) { dir = -1; pos = c.to - (pos - c.to) }
            else { val n = then ?: if (talking) "talk" else "idle"; then = null; play(n) }
        }
        if (pos < c.from) { dir = 1; pos = c.from + (c.from - pos) }
        if (fade < 1f) fade = (fade + (dt / 0.28).toFloat()).coerceAtMost(1f)
        val want = pos.toInt().coerceIn(0, maxOf(0, frames.count - 1))
        val ahead = (1..8).map { (want + dir * it).coerceIn(c.from, c.to) }.distinct()
        frames.want(want, ahead)
        // Only move on when the next frame is decoded: the picture holds a frame instead of flashing empty.
        if (frames.get(want) != null) frame = want
        tick++
    }
}

/**
 * Draws the avatar: cross-fade between clips and the talking jaw (a bitmap mesh: the area under the lip line
 * moves down with the voice, the lips part). [scale] = frame pixels → view pixels; [faceAt] = where the face
 * centre sits across the view (0..1); [top] = offset from the top in px.
 */
@Composable
fun AvatarView(player: AvatarPlayer, modifier: Modifier, faceAt: Float = 0.5f, topFrac: Float = 0f, fill: Float = 1.18f, onFrame: (Double) -> Unit = {}) {
    LaunchedEffect(player) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.0 else ((now - last) / 1e9).coerceAtMost(0.1)
                last = now
                onFrame(dt)
                player.step(dt)
            }
        }
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val mesh = remember { FloatArray((MW + 1) * (MH + 1) * 2) }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") player.tick
        val m = AvatarSpec.mouth.getValue(player.frames.buddy)
        val scale = size.width / AvatarSpec.W * fill
        val dx = size.width * faceAt - m[4] * scale
        val dy = size.height * topFrac
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            fun draw(i: Int, alpha: Float, jaw: Float) {
                val b = player.frames.get(i) ?: return
                paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
                nc.save()
                nc.translate(dx, dy)
                nc.scale(scale, scale)
                if (jaw < 0.02f) nc.drawBitmap(b, 0f, 0f, paint)
                else {
                    buildMesh(mesh, b.width.toFloat(), b.height.toFloat(), m, jaw)
                    nc.drawBitmapMesh(b, MW, MH, mesh, 0, null, 0, paint)
                }
                nc.restore()
            }
            val f = player.fade
            if (f < 1f && player.prevFrame >= 0) draw(player.prevFrame, 1f - f, 0f)
            draw(player.frame, if (f < 1f && player.prevFrame >= 0) f else 1f, if (player.clip == "talk" || player.talking) player.mouth else 0f)
        }
    }
}

private const val MW = 40
private const val MH = 24

/** Grid over the frame; below the lip line (around the mouth) points move down with [jaw], the upper lip lifts a little. */
private fun buildMesh(out: FloatArray, w: Float, h: Float, m: FloatArray, jaw: Float) {
    val mx = m[0]; val ly = m[1]; val hw = m[2]; val chin = m[3]
    val drop = jaw * hw * 0.30f
    var k = 0
    for (j in 0..MH) {
        val y = h * j / MH
        for (i in 0..MW) {
            val x = w * i / MW
            val nx = (x - mx) / (hw * 1.6f)
            val across = exp(-(nx * nx).toDouble()).toFloat()
            var oy = 0f
            if (y >= ly) {
                // whole lower face moves with the jaw, fading out under the chin
                val down = when {
                    y <= chin -> 1f
                    else -> (1f - (y - chin) / (hw * 0.9f)).coerceIn(0f, 1f)
                }
                oy = drop * across * down * smooth01((y - ly) / (hw * 0.10f) + 0.35f)
            } else {
                val up = (1f - (ly - y) / (hw * 0.45f)).coerceIn(0f, 1f)
                oy = -drop * 0.18f * across * up
            }
            out[k++] = x
            out[k++] = y + oy
        }
    }
}

private fun smooth01(t: Float): Float { val c = t.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }

/** Keeps a stable reference for the mouth level reading inside the frame loop. */
@Composable
fun rememberAvatarPlayer(buddy: AiBuddy): AvatarPlayer {
    val ctx = LocalContext.current
    return remember(buddy) { AvatarPlayer(AvatarFrames.of(ctx, buddy)) }
}

/** abs helper kept for future clip blending */
internal fun near(a: Float, b: Float) = abs(a - b) < 0.01f
