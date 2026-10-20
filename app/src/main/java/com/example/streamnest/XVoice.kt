package com.example.streamnest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.sin

/** Spoken requests that are for the AI rather than a title search ("something funny", "movies like Inception"…). */
fun soundsLikeAsk(said: String): Boolean {
    val s = " " + said.lowercase().trim() + " "
    val cues = listOf(" suggest", " recommend", " something ", " anything ", " like ", " similar", " i want", " i'm in the mood", " im in the mood",
        " feel like", " what should", " show me", " give me", " best ", " top ", " find me", " movies about", " shows about", " with ", " for kids", " tonight")
    return cues.any { it in s } || Voice.moods.any { m -> m.words.any { w -> " $w " in s } } || s.trim().split(" ").size >= 6
}

/**
 * Xstream voice search: an in-app glass panel (no system dialog). A glowing orb breathes with your voice, the words
 * appear as you speak; a title goes straight to search, a request ("something funny for tonight") goes to the AI.
 */
@Composable
fun VoiceSearchSheet(onSearch: (String) -> Unit, onAsk: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var heard by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    var state by remember { mutableStateOf("starting") } // starting, listening, thinking, done, error
    var final by remember { mutableStateOf<String?>(null) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; if (!ok) state = "error" }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.RECORD_AUDIO) }
    DisposableEffect(granted, attempt) {
        if (!granted) return@DisposableEffect onDispose {}
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { state = "error"; return@DisposableEffect onDispose {} }
        val rec = SpeechRecognizer.createSpeechRecognizer(ctx)
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { state = "listening" }
            override fun onBeginningOfSpeech() { state = "listening" }
            override fun onRmsChanged(rms: Float) { level = ((rms + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { state = "thinking"; level = 0f }
            override fun onError(e: Int) { if (final == null) state = if (heard.isNotBlank()) "done" else "error"; if (heard.isNotBlank() && final == null) final = heard }
            override fun onResults(r: Bundle?) {
                val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (t.isNotEmpty()) { heard = t; final = t; state = "done" } else state = "error"
            }
            override fun onPartialResults(r: Bundle?) { r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) heard = it } }
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        runCatching { rec.startListening(i) }.onFailure { state = "error" }
        onDispose { runCatching { rec.cancel(); rec.destroy() } }
    }
    // What you said: a title → search; a request → the AI (after a short look at the words).
    LaunchedEffect(final) {
        val f = final ?: return@LaunchedEffect
        delay(650)
        if (soundsLikeAsk(f)) onAsk(f) else onSearch(f)
    }

    val inf = rememberInfiniteTransition(label = "voice")
    val t by inf.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "t")
    val lv by animateFloatAsState(level, spring(stiffness = 300f), label = "lv")

    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xE6050608)).clickable(remember { MutableInteractionSource() }, null) { onClose() }, Alignment.BottomCenter) {
            val shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
            Column(
                Modifier.fillMaxWidth().clip(shape)
                    .background(Brush.verticalGradient(listOf(Color(0xFF16121C), Color(0xFF0B0B10))))
                    .border(1.dp, Brush.verticalGradient(listOf(Accent.copy(alpha = 0.55f), Color.Transparent)), shape)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .navigationBarsPadding().padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.width(40.dp).height(4.dp).clip(CircleShape).background(Color(0x33FFFFFF)))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp), tint = Accent)
                    Text("  Voice search · AI", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB9BFCC))
                    Icon(Icons.Default.Close, "Close", Modifier.size(30.dp).clip(CircleShape).clickable { onClose() }.padding(5.dp), tint = Color(0xFFB9BFCC))
                }
                // The orb: soft rings that grow with your voice.
                Box(Modifier.padding(top = 18.dp).size(190.dp), Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val c = center; val r0 = size.minDimension / 2
                        for (k in 0 until 3) {
                            val wob = sin(t + k * 2.1f) * 0.04f
                            val rr = r0 * (0.46f + 0.18f * k * (0.4f + lv) + wob)
                            drawCircle(Accent.copy(alpha = (0.22f - k * 0.06f) * (0.5f + lv)), rr, c)
                        }
                        drawCircle(Brush.radialGradient(listOf(Color(0xFFFF6A3D), Accent, Color(0xFF5A0610)), c, r0 * 0.42f), r0 * (0.36f + 0.05f * lv), c)
                    }
                    Icon(Icons.Default.Mic, null, Modifier.size(40.dp).graphicsLayer { scaleX = 1f + lv * 0.15f; scaleY = 1f + lv * 0.15f }, tint = Color.White)
                }
                // A little waveform.
                Canvas(Modifier.padding(top = 4.dp).fillMaxWidth(0.6f).height(28.dp)) {
                    val n = 28; val w = size.width / n
                    for (i in 0 until n) {
                        val h = size.height * (0.12f + (0.2f + lv * 0.8f) * (0.5f + 0.5f * sin(t * 3 + i * 0.55f)).toFloat())
                        drawLine(Color.White.copy(alpha = 0.55f), Offset(i * w + w / 2, size.height / 2 - h / 2), Offset(i * w + w / 2, size.height / 2 + h / 2), strokeWidth = w * 0.45f)
                    }
                }
                Text(
                    when {
                        heard.isNotBlank() -> "“$heard”"
                        state == "error" -> if (!granted) "Allow the microphone to search with your voice" else "Didn't catch that"
                        state == "starting" -> "Getting ready…"
                        else -> "Say a title — or ask, like “something funny for tonight”"
                    },
                    Modifier.padding(top = 16.dp).fillMaxWidth(), textAlign = TextAlign.Center,
                    fontSize = if (heard.isNotBlank()) 21.sp else 15.sp, fontWeight = if (heard.isNotBlank()) FontWeight.Bold else FontWeight.Medium,
                    color = if (heard.isNotBlank()) Color.White else Color(0xFFB9BFCC),
                )
                if (final != null) Text(if (soundsLikeAsk(final!!)) "Asking the AI…" else "Searching…", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Accent)
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state == "error" || final != null) Pill(Icons.Default.Mic, "Try again") { heard = ""; final = null; state = "starting"; if (!granted) ask.launch(Manifest.permission.RECORD_AUDIO) else attempt++ }
                    if (heard.isNotBlank()) {
                        Pill(Icons.Default.Search, "Search") { onSearch(heard) }
                        Pill(Icons.Default.AutoAwesome, "Ask AI") { onAsk(heard) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x1FFFFFFF)).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = Color.White)
        Text("  $label", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Voice search → AI: the request waiting to be opened in the assistant. */
object VoiceAskBus { val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
