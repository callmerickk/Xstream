package com.example.streamnest

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow

/** Xstream Live (phone): no offline voice model and no face detection — both left out to keep the app small. */
object WakeWord {
    val status = MutableStateFlow("off")
    var onName: () -> Unit = {}
    @Volatile var strict = false
    @Volatile var quietUntil = 0L
    fun hasModel(ctx: Context) = false
    fun start(ctx: Context) {}
    fun stop() {}
}

object SpeakerTracker {
    fun reset() {}
    suspend fun speakerX(frame: Bitmap): Float? = null
}
