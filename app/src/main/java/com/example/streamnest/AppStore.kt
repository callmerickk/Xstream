package com.example.streamnest

import android.content.Context
import java.io.File

/**
 * 0.9.7 (smartCache): app storage that Android's "Clear cache" and the exit clean never remove: the Glass backdrop,
 * the first-screen posters (KeepPosters) and the day's picks (PicksStore). The Home snapshot (shelves_<pid>.json,
 * homesteady_<pid>.json) and the saved picks were already in filesDir / preferences; only the Glass backdrop and the
 * pictures lived in cacheDir.
 */
object AppStore {
    fun dir(ctx: Context): File = File(ctx.noBackupFilesDir, "keep").apply { mkdirs() }
    fun file(ctx: Context, name: String) = File(dir(ctx), name)
    /** Where a writer / reader must look: the keep folder with smartCache, else the old cacheDir path. */
    fun pathFor(ctx: Context, legacyName: String): File = if (Feature.smartCache && !BuildConfig.IS_TV) file(ctx, legacyName) else File(ctx.cacheDir, legacyName)

    /** What lived in cacheDir before 0.9.7 (grep cacheDir: only the Glass backdrop). */
    private val legacy = listOf("glass_last.png")

    /** Once per start, before anything reads them: files still in cacheDir move over (same disk → a rename). */
    fun migrate(ctx: Context) {
        if (!Feature.smartCache || BuildConfig.IS_TV) return
        runCatching {
            for (n in legacy) {
                val src = File(ctx.cacheDir, n); if (!src.exists()) continue
                val dst = file(ctx, n); if (dst.exists()) { src.deleteRecursively(); continue }
                if (!src.renameTo(dst)) { src.copyRecursively(dst, true); src.deleteRecursively() }
            }
        }
    }
}
