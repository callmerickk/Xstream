package com.lagradost.cloudstream3

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Runs [f] on every item at the same time and returns the results in order. */
suspend fun <A, B> List<A>.amap(f: suspend (A) -> B): List<B> = coroutineScope { map { async { f(it) } }.awaitAll() }
suspend fun <A, B> Map<out A, B>.amap(f: suspend (Map.Entry<A, B>) -> Unit) = coroutineScope { entries.map { launch { f(it) } } }
suspend fun <A, B> List<A>.amapIndexed(f: suspend (index: Int, A) -> B): List<B> = coroutineScope { mapIndexed { i, a -> async { f(i, a) } }.awaitAll() }
suspend fun runAllAsync(vararg transforms: suspend () -> Unit) = coroutineScope { transforms.map { launch { runCatching { it() } } } }
