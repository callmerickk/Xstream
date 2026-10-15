package com.example.streamnest

import android.content.Context
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Play timing log (Developer's Corner): every Play press records each step with its time — tap, player open, link
 * opened, tracks found, ready, first picture — and how much was read from the saved video. The last 30 plays are kept.
 */
object PlayTiming {
    private const val MAX = 30
    private var prefs: android.content.SharedPreferences? = null
    private var t0 = 0L
    private var cur: StringBuilder? = null
    private var title = ""
    private var marked = HashSet<String>()
    /** Bytes the player read from the saved video since this play started. */
    val savedRead = AtomicLong(0)

    fun init(ctx: Context) { if (prefs == null) prefs = ctx.getSharedPreferences("play_timing", Context.MODE_PRIVATE) }

    /** Play was pressed (or the player opened without a press, e.g. next episode). */
    fun begin(what: String, detail: String) {
        cur?.let { finish("left before the first picture") }
        t0 = SystemClock.elapsedRealtime()
        title = what
        marked = HashSet()
        savedRead.set(0)
        ActLog.add("PLAY", "start · $what · $detail")
        cur = StringBuilder().append(SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).format(Date())).append(" · ").append(what).append('\n')
            .append("   +0.00 s  ").append(detail).append('\n')
    }

    /** A step; [once] = only the first time this step happens in this play. */
    fun mark(step: String, once: String? = null) {
        val b = cur ?: return
        if (once != null && !marked.add(once)) return
        val s = (SystemClock.elapsedRealtime() - t0) / 1000f
        ActLog.add("PLAY", "+%.2f s · %s".format(s, step))
        b.append("   +").append("%.2f".format(s)).append(" s  ").append(step).append('\n')
    }

    fun active(): Boolean = cur != null

    /** The play ended (first picture, error or left): saved to the log. */
    fun finish(result: String) {
        val b = cur ?: return
        val s = (SystemClock.elapsedRealtime() - t0) / 1000f
        ActLog.add("PLAY", "= $result after %.2f s".format(s))
        b.append("   = ").append(result).append(" after ").append("%.2f".format(s)).append(" s")
        cur = null
        val p = prefs ?: return
        val all = listOf(b.toString()) + all()
        p.edit().putString("log", all.take(MAX).joinToString("\n\u0001\n")).apply()
    }

    /** A step after the first picture (e.g. the upgrade offer): added to this play's saved entry, with its clock time. */
    fun late(step: String) {
        if (cur != null) { mark(step); return }
        ActLog.add("PLAY", step)
        val p = prefs ?: return
        val list = all().toMutableList()
        if (list.isEmpty()) return
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        list[0] = list[0] + "\n   [" + clock + "]  " + step
        p.edit().putString("log", list.take(MAX).joinToString("\n\u0001\n")).apply()
    }

    fun all(): List<String> = prefs?.getString("log", null)?.split("\n\u0001\n")?.filter { it.isNotBlank() }.orEmpty()

    fun clear() { prefs?.edit()?.remove("log")?.apply() }
}
