package com.lagradost.cloudstream3.mvvm

fun logError(throwable: Throwable) {
    android.util.Log.e("PluginError", throwable.toString())
    com.lagradost.cloudstream3.CsBridge.log("E", "Error", throwable.javaClass.simpleName + ": " + (throwable.message ?: "") +
        (throwable.stackTrace.firstOrNull()?.let { " @ ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: ""))
}
fun <T> safe(block: () -> T): T? = runCatching(block).onFailure { logError(it) }.getOrNull()
suspend fun <T> safeAsync(block: suspend () -> T): T? = try { block() } catch (e: Throwable) { logError(e); null }
