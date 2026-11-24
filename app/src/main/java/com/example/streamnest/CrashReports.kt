package com.example.streamnest

import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.launch

/**
 * 0.9.2: Firebase crash reports (Crashlytics), both apps, phone and TV. Each report carries the app (brand), version,
 * flavor and a rough device tier, the short account code the log repo uses ("acct-" + 6 letters of a one-way hash of
 * the account id: never an email or the id itself), and the important activity-log lines that led up to it.
 * Nothing runs on the main thread at start: [start] hands its work to a background thread.
 */
object CrashReports {
    @Volatile private var fc: FirebaseCrashlytics? = null
    @Volatile private var started = false
    private val emailRx = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    /** A Firebase account id (28 letters and digits), never copied. */
    private val uidRx = Regex("(?<![A-Za-z0-9])[A-Za-z0-9]{28}(?![A-Za-z0-9])")
    /** The log lines copied into a report (the rest of the log stays on the device / in the log upload). */
    val mirrorTypes = setOf("APP", "SCREEN", "ERROR", "PLAYFAIL", "SWITCH")
    /** Crash reports are ready (lines are copied only then). */
    val on: Boolean get() = fc != null

    fun start(ctx: android.content.Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        Thread({
            runCatching {
                val c = FirebaseCrashlytics.getInstance()
                c.setCustomKey("brand", Brand.NAME)
                c.setCustomKey("version", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
                c.setCustomKey("flavor", BuildConfig.FLAVOR)
                c.setCustomKey("device_tier", tier(app))
                fc = c
                // The account, as the same short code the log repo shows (changes with sign-in / sign-out).
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                    Cloud.user.collect { u -> runCatching { c.setUserId(u?.uid?.let { code(it) } ?: "") } }
                }
            }
        }, "crash-reports").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** Same code as tools/collect.py in the log repo: "acct-" + sha256("sx|" + uid)[:6]. */
    fun code(uid: String): String = "acct-" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(("sx|$uid").toByteArray()).joinToString("") { "%02x".format(it) }.take(6)

    /** low / mid / high from the phone's memory and cores (TV sticks are usually "low"). */
    private fun tier(ctx: android.content.Context): String = runCatching {
        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val gb = mi.totalMem / 1_073_741_824.0
        val cores = Runtime.getRuntime().availableProcessors()
        when {
            am.isLowRamDevice || gb < 2.5 || cores <= 4 -> "low"
            gb < 5.5 -> "mid"
            else -> "high"
        } + " · ${"%.1f".format(gb)} GB · $cores cores"
    }.getOrDefault("?")

    /** An activity-log line (already masked by ActLog). Important ones are copied into the next crash report;
     *  Crashlytics keeps only the latest of them. Cheap: Crashlytics writes them on its own thread. */
    fun line(type: String, msg: String, important: Boolean) {
        val c = fc ?: return
        if (!important) return
        runCatching { c.log("$type ${uidRx.replace(emailRx.replace(msg, "•@•"), "•id•").take(200)}") }
    }
}
