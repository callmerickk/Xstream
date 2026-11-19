package com.example.streamnest

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 0.8.7 — "Choose your look", once per profile (new and existing; skippable). Step 1 Home style, step 2 colours. Tapping a
 * choice shows it moving, full screen; "Use this" keeps it, Back returns to the choices. Skipped = Live + Midnight.
 */
fun lookDue(texts: Map<String, String>, p: Profile?): Boolean =
    !BuildConfig.IS_TV && !BuildConfig.LIVE_ONLY && p != null && texts["lookChosen"].isNullOrBlank()

private data class LookOpt(val key: String, val name: String, val desc: String)

@Composable
fun FirstLookScreen(vm: AppViewModel, onDone: () -> Unit) {
    val texts by vm.texts.collectAsState()
    var step by remember { mutableIntStateOf(0) }
    var style by remember { mutableStateOf(homeStyleOf(texts)) }
    var theme by remember { mutableStateOf(currentThemeName(texts).takeIf { n -> Themes.any { it.name == n } } ?: "Midnight") }
    var preview by remember { mutableStateOf<String?>(null) }
    val styles = listOf(LookOpt("live", "Live", "Curved card, trailer on tap, red glow"), LookOpt("streamx", "Stack", "A big card with the next ones stacked behind"),
        LookOpt("classic", "Classic", "Centre card with a glowing rim"))
    val themes = listOf("Midnight", "Crimson", "Ocean", "Violet").mapNotNull { n -> Themes.firstOrNull { it.name == n } }
    fun barFor(s: String) = when (s) { "live" -> "orbit"; "streamx" -> "streamx"; else -> "capsule" }
    fun lookOf(name: String) = lookOfTheme(Themes.firstOrNull { it.name == name } ?: Themes.first())
    fun finish(skip: Boolean) {
        val s = if (skip) "live" else style; val th = if (skip) "Midnight" else theme
        vm.setText("homeStyle", s); vm.setText("navBar", barFor(s))
        Themes.firstOrNull { it.name == th }?.let { applyThemeOpt(vm, it) }
        vm.setText("lookChosen", "1")
        ActLog.add("APP", "look chosen: $s · $th" + if (skip) " (skipped)" else "")
        vm.refresh(); onDone()
    }
    BackHandler { if (preview != null) preview = null else if (step == 1) step = 0 else finish(true) }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF111734), Color(0xFF0F1014))))) {
        AnimatedContent(preview, transitionSpec = { (fadeIn(tween(350)) + scaleIn(tween(350), 0.96f)) togetherWith fadeOut(tween(200)) }, label = "look") { pv ->
            if (pv != null) {
                // Full-screen moving preview.
                val pStyle = if (step == 0) pv else style
                val pTheme = if (step == 0) theme else pv
                val name = if (step == 0) styles.first { it.key == pv }.name + " Home" else "$pv colours"
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
                    Text("PREVIEW", fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF6A5C))
                    Text(name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    MiniHome(pStyle, barFor(pStyle), lookOf(pTheme), Modifier.padding(vertical = 14.dp).weight(1f).fillMaxWidth()
                        .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(12.dp)), animate = true)
                    Text("Like it? Tap Use this. Back shows the other choices.", fontSize = 13.sp, color = Color(0xFFC3C7D6))
                    Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(25.dp)).background(Color(0x26FFFFFF)).clickable { preview = null }, Alignment.Center) {
                            Text("‹ Back", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Box(Modifier.weight(1.4f).height(50.dp).clip(RoundedCornerShape(25.dp)).background(Color.White).clickable {
                            if (step == 0) { style = pv; preview = null; step = 1 } else { theme = pv; preview = null; finish(false) }
                        }, Alignment.Center) { Text("Use this", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black) }
                    }
                }
            } else Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Wordmark("Xstream", 20.sp)
                    Spacer(Modifier.weight(1f))
                    Text("Skip", Modifier.clip(RoundedCornerShape(14.dp)).clickable { finish(true) }.padding(10.dp), color = Color(0xFFB8BED3), fontSize = 14.sp)
                }
                Text(if (step == 0) "Choose your Home" else "Choose your colours", Modifier.padding(top = 10.dp), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Tap one to see it moving · step ${step + 1} of 2", fontSize = 13.sp, color = Color(0xFFB8BED3))
                Spacer(Modifier.height(16.dp))
                val opts = if (step == 0) styles else themes.map { LookOpt(it.name, it.name, it.note.ifBlank { if (it.enhanced) "glowing" else "" }) }
                opts.forEach { o ->
                    val selected = if (step == 0) o.key == style else o.key == theme
                    Row(Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF17181E))
                        .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else Color(0x1FFFFFFF), RoundedCornerShape(18.dp))
                        .clickable { preview = o.key }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val st = if (step == 0) o.key else style
                        val th = if (step == 0) theme else o.key
                        MiniHome(st, barFor(st), lookOf(th), Modifier.size(96.dp, 170.dp), animate = true)
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(o.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(o.desc, fontSize = 12.5.sp, color = Color(0xFFB8BED3))
                        }
                    }
                }
                Text("You can change everything later in Settings › Layout › Look.", Modifier.padding(vertical = 10.dp), fontSize = 12.sp, color = Color(0xFF8A90A6))
            }
        }
    }
}
