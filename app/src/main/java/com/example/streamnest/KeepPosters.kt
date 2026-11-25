package com.example.streamnest

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 0.9.7 (smartCache): the pictures of the first screen survive "Clear cache" (Coil's picture cache lives in cacheDir).
 * A picture interceptor: the first ~30 pictures shown after a start (Home's first screen) are copied small into app
 * storage (≈ 20–40 KB each, newest 60 kept); a request for a kept picture at the same size reads the local copy first.
 */
object KeepPosters : coil.intercept.Interceptor {
    private const val PER_START = 30
    private val savedThisStart = AtomicInteger()
    private fun dir(ctx: Context) = File(AppStore.dir(ctx), "posters").apply { mkdirs() }
    private fun name(key: String) = Integer.toHexString(key.hashCode()) + "_" + key.length + ".jpg"

    override suspend fun intercept(chain: coil.intercept.Interceptor.Chain): coil.request.ImageResult {
        val req = chain.request
        val url = req.data as? String
        if (!Feature.smartCache || BuildConfig.IS_TV || url == null || !url.startsWith("http")) return chain.proceed(req)
        val w = (chain.size.width as? coil.size.Dimension.Pixels)?.px ?: return chain.proceed(req)
        val ctx = req.context
        val f = File(dir(ctx), name("$url|$w"))
        if (f.length() > 0) {
            val r = chain.proceed(req.newBuilder().data(f).build())
            if (r is coil.request.SuccessResult) return r
        }
        val r = chain.proceed(req)
        if (r is coil.request.SuccessResult && f.length() == 0L && savedThisStart.get() < PER_START) {
            val bm = (r.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (bm != null && !bm.isRecycled && bm.width <= 800 && savedThisStart.incrementAndGet() <= PER_START) {
                val copy = runCatching { bm.copy(android.graphics.Bitmap.Config.ARGB_8888, false) }.getOrNull()
                if (copy != null) Thread {
                    runCatching {
                        f.outputStream().use { copy.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, it) }
                        dir(ctx).listFiles()?.sortedByDescending { it.lastModified() }?.drop(60)?.forEach { it.delete() }
                    }
                }.apply { priority = Thread.MIN_PRIORITY }.start()
            }
        }
        return r
    }
}
