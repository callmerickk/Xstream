package com.example.streamnest

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Catches a crash, saves its details to a file, and shows them on the next start (so a TV crash can be
 * photographed and sent). Nothing leaves the device.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val ver = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
                val text = buildString {
                    append("Xstream ").append(ver).append(" · ").append(android.os.Build.MODEL)
                    append(" · Android ").append(android.os.Build.VERSION.RELEASE).append('\n')
                    append("Thread: ").append(thread.name).append('\n')
                    append(android.util.Log.getStackTraceString(e))
                }
                File(app.filesDir, FILE).writeText(text.take(12_000))
                ActLog.crash(text)
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /**
     * Why the app's process ended last times (Android 11+): low memory, a crash inside native code, a freeze (ANR)…
     * Those never reach the handler above, so the log showed nothing. Each new one is written to the Activity log once.
     */
    fun logExitReasons(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val prefs = ctx.getSharedPreferences("crashlog", Context.MODE_PRIVATE)
            val seen = prefs.getLong("exitSeen", 0L)
            val list = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 8).filter { it.timestamp > seen }
            list.sortedBy { it.timestamp }.forEach { e ->
                val why = when (e.reason) {
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory (Android closed it)"
                    android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash in native code"
                    android.app.ApplicationExitInfo.REASON_ANR -> "froze (not responding)"
                    android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "used too many resources"
                    android.app.ApplicationExitInfo.REASON_SIGNALED -> "killed by the system (signal ${e.status})"
                    android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> null
                    android.app.ApplicationExitInfo.REASON_USER_STOPPED -> null
                    android.app.ApplicationExitInfo.REASON_EXIT_SELF -> null
                    else -> "other (${e.reason})"
                } ?: return@forEach
                val mem = "pss ${e.pss / 1024} MB · rss ${e.rss / 1024} MB"
                val tomb = if (e.reason == android.app.ApplicationExitInfo.REASON_ANR)
                    runCatching { e.traceInputStream?.bufferedReader()?.use { r -> r.readText().lines().filter { l -> "backtrace" in l || " #0" in l || "Abort message" in l || "signal" in l || "\"main\"" in l }.take(12).joinToString(" | ") } }.getOrNull().orEmpty()
                    else ""
                ActLog.add("ERROR", "app ended: $why · ${java.text.SimpleDateFormat("dd MMM HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.timestamp))} · $mem${e.description?.let { " · $it" } ?: ""}${if (tomb.isNotBlank()) " · $tomb" else ""}")
            }
            list.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("exitSeen", it).apply() }
        }
    }

    fun read(ctx: Context): String? = runCatching { File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()
    fun clear(ctx: Context) { runCatching { File(ctx.filesDir, FILE).delete() } }
}

/** "Xstream closed unexpectedly last time" — shows the saved error once, over everything. */
@Composable
fun CrashNotice() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(CrashLog.read(ctx)) }
    val t = text ?: return
    val close = { CrashLog.clear(ctx); text = null }
    BackHandler { close() }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    // Only the important part: the error line and the first lines that mention Xstream code.
    val lines = t.lines()
    val short = (lines.take(4) + lines.drop(4).filter { it.contains("streamnest") }.take(10)).joinToString("\n")
    Box(
        Modifier.fillMaxSize().background(Color(0xE6000000)).clickable(remember { MutableInteractionSource() }, null) {},
        Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 680.dp).padding(24.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF15161C))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(20.dp)).padding(22.dp),
        ) {
            Text("Xstream closed unexpectedly last time", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Take a photo or screenshot of this and send it to get it fixed. Press OK or BACK to close.", Modifier.padding(top = 6.dp), fontSize = 13.sp, color = Muted)
            Box(
                Modifier.padding(top = 14.dp).fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0B0C10)).verticalScroll(rememberScrollState()).padding(12.dp),
            ) {
                Text(short, fontSize = 10.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFD6D9E1))
            }
            var focused by remember { mutableStateOf(false) }
            Box(
                Modifier.padding(top = 16.dp).align(Alignment.End).focusRequester(fr)
                    .onFocusChanged { focused = it.isFocused }
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (focused) Color.White else Color(0x26FFFFFF))
                    .clickable(remember { MutableInteractionSource() }, null) { close() }
                    .padding(horizontal = 26.dp, vertical = 10.dp),
            ) {
                Text("OK", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (focused) Color.Black else Color.White)
            }
        }
    }
}
