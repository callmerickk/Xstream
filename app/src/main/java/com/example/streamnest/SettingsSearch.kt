package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One searchable setting: where it lives ([pages] = page keys to open in order) and words that find it. */
data class SettingEntry(val title: String, val sub: String, val pages: List<String>, val keywords: String = "")

private val P = mapOf(
    "account" to "Account", "layout" to "Layout", "homeLayout" to "Home Layout", "cw" to "Continue Watching",
    "poster" to "Poster Card Style", "streams" to "Streams", "content" to "Sources & Extensions", "addons" to "Sources", "extensions" to "Extensions",
    "streamFilters" to "Stream Filters", "collections" to "Collections", "integrations" to "Integrations", "quickStart" to "Quick Start", "detailPage" to "Detail Page", "playback" to "Playback", "network" to "Network & DNS", "about" to "Licenses",
    "updates" to "Update Log", "advanced" to "Advanced", "dev" to "Developer's Corner",
)

val SettingsIndex = listOf(
    SettingEntry("Account & Cloud Sync", "Sign in, auto sync across your phone and TV, watch history.", listOf("account"), "data history cloud sync sign in login firebase google password"),
    SettingEntry("Clear watch history", "Removes Continue Watching and your stats.", listOf("account"), "delete reset history"),
    // Layout
    SettingEntry("Theme", "Classic and enhanced colour themes.", listOf("layout"), "color colour accent crimson ocean violet emerald amber rose custom"),
    SettingEntry("Custom theme", "Pick your own two colours.", listOf("layout"), "color colour hex"),
    SettingEntry("AMOLED Black", "Pure black backgrounds for OLED screens.", listOf("layout"), "dark oled black"),
    SettingEntry("Navigation Bar", "Adaptive, always expanded or always compact.", listOf("layout"), "bottom bar nav capsule"),
    SettingEntry("App Icon", "Red Glow, Ribbon X or Halo Play.", listOf("layout"), "logo launcher icon"),
    SettingEntry("Home Layout", "Hero cards, row order and visibility.", listOf("layout", "homeLayout"), "home rows order hide"),
    SettingEntry("Show Hero Section", "Sliding cards at the top of Home.", listOf("layout", "homeLayout"), "hero deck cards"),
    SettingEntry("Trailers on cards", "Muted trailer on the card you stop at.", listOf("layout", "homeLayout"), "trailer youtube autoplay video"),
    SettingEntry("Addon poster designs", "Posters exactly as the addon designs them.", listOf("layout", "homeLayout"), "top 10 ranked rank numbers trending poster shape landscape addon design"),
    SettingEntry("Auto-slide", "Cards move on their own.", listOf("layout", "homeLayout"), "slide carousel"),
    SettingEntry("Continue Watching", "Show, card style and cache.", listOf("layout", "cw"), "resume progress"),
    SettingEntry("Poster Card Style", "Card width and corner radius.", listOf("layout", "poster"), "poster width corner size landscape"),
    SettingEntry("Landscape Posters", "Wide Hotstar-style cards on Home.", listOf("layout", "poster"), "wide cards"),
    SettingEntry("Hide labels", "Hide title logos on wide cards.", listOf("layout", "poster"), "logo labels"),
    SettingEntry("Streams", "How stream sources are listed.", listOf("layout", "streams"), "sources list"),
    SettingEntry("Fusion badges", "Quality, audio and codec badges on streams.", listOf("layout", "streams"), "badges 4k hdr atmos 1080p fusion"),
    SettingEntry("Badge pack URL", "Use your own badge images.", listOf("layout", "streams"), "badges json pack logo"),
    SettingEntry("Addon name", "Show which addon each stream comes from.", listOf("layout", "streams"), "source addon"),
    // Content
    SettingEntry("Addons", "Install, remove, refresh and sort addons.", listOf("content", "addons"), "addon install manifest stremio cinemeta"),
    SettingEntry("Stream Filters", "Include, exclude or force HDR, Dolby Vision, 4K…", listOf("content", "streamFilters"), "hdr sdr dolby vision dv 4k 1080p cam filter exclude include forced required visual tags"),
    SettingEntry("Collections", "Import Nuvio collections, reorder or hide them.", listOf("content", "collections"), "nuvio collection folders import json paste link studios networks genres streaming services"),
    SettingEntry("Trakt client ID", "Used for Trakt lists in Collections.", listOf("integrations"), "trakt client id api key lists collections"),
    // Playback
    SettingEntry("Default player", "Built-in player or another app.", listOf("playback"), "player external vlc mx"),
    SettingEntry("Content advisory", "Violence / profanity / nudity at start.", listOf("playback"), "parental guide"),
    SettingEntry("Info when paused", "Title, rating and story after pausing.", listOf("playback"), "pause"),
    SettingEntry("Subtitles", "Size, bold, colours and outline.", listOf("playback"), "subtitle captions cc font"),
    SettingEntry("Auto Stream Selection", "Manual, first source or regex match.", listOf("playback"), "autoplay auto play"),
    SettingEntry("Regex patterns", "Primary pattern and fallbacks.", listOf("playback"), "regex fallback pattern"),
    SettingEntry("Player engine", "VLC (recommended) or ExoPlayer.", listOf("playback"), "vlc exoplayer engine codec mkv heavy files"),
    SettingEntry("Starting brightness", "Videos start at 50% brightness.", listOf("playback"), "brightness screen dim start"),
    SettingEntry("Starting volume", "Videos start at 50% volume.", listOf("playback"), "volume sound loud quiet start audio level"),
    SettingEntry("Fast start", "Start playing as soon as possible.", listOf("playback"), "buffer buffering instant start"),
    SettingEntry("Reuse last link", "Resume with the stream that worked last time.", listOf("playback"), "last link resume"),
    SettingEntry("Skip Intro", "Detect intros and recaps.", listOf("playback"), "intro recap skip introdb"),
    SettingEntry("Auto-play Next Episode", "Start the next episode near the end.", listOf("playback"), "next episode binge"),
    SettingEntry("Quick Start", "Fast addons for instant play.", listOf("content", "quickStart"), "quick start fast instant addon group quality selector"),
    SettingEntry("Preferred audio language", "Language order for Quick Start.", listOf("content", "quickStart"), "language hindi english tamil audio dub"),
    SettingEntry("Starting quality", "Auto / Full HD / Mid HD / Low.", listOf("content", "quickStart"), "quality full hd 1080p 720p 480p auto"),
    SettingEntry("Prepare video on the title page", "Preload for instant start.", listOf("content", "quickStart"), "preload instant buffer start wifi"),
    SettingEntry("Detail Page", "Ratings, cast, more like this.", listOf("layout", "detailPage"), "detail page ratings cast more like this"),
    // Integrations
    SettingEntry("AI assistant", "Groq, Gemini, OpenRouter or ChatGPT key.", listOf("integrations"), "ai groq gemini chatgpt openai openrouter key voice assistant"),
    SettingEntry("AI button logo", "Your own transparent logo in the AI button.", listOf("integrations"), "ai logo icon button"),
    SettingEntry("Forget AI memory", "Clear what the assistant remembers.", listOf("integrations"), "ai memory history"),
    SettingEntry("Recommended for you", "Home row from what you liked.", listOf("integrations"), "recommendations recommended row"),
    SettingEntry("Ask \"How was it?\"", "Go for it / Skip / Just surfing after watching.", listOf("integrations"), "vote rating like dislike"),
    SettingEntry("TMDB", "Sharper backdrops and logos.", listOf("integrations"), "tmdb artwork api key"),
    SettingEntry("MDBList ratings", "Rotten Tomatoes, Metacritic and more.", listOf("integrations"), "mdblist ratings rotten tomatoes metacritic imdb"),
    // Network / about / advanced
    SettingEntry("DNS provider", "Cloudflare, Google, Quad9, AdGuard.", listOf("network"), "dns doh network blocked"),
    SettingEntry("Licenses & Attribution", "Data sources and licenses.", listOf("about"), "license about"),
    SettingEntry("Update Log", "What's new and check for updates.", listOf("updates"), "changelog version update"),
    SettingEntry("Startup animation", "Xstream logo animation when the app opens.", listOf("advanced"), "splash intro logo animation startup"),
    SettingEntry("Auto-open last profile", "Countdown ring on \"Who's watching?\".", listOf("advanced"), "timer countdown profile auto select startup"),
    SettingEntry("Skip \"Who's watching?\"", "Open the last profile straight away.", listOf("advanced"), "remember last profile startup"),
    SettingEntry("Clear Continue Watching Cache", "Refresh watch progress.", listOf("advanced"), "cache"),
    SettingEntry("Developer's Corner", "App name, logo, password and UI texts.", listOf("advanced", "dev"), "developer password app name texts"),
)

fun searchSettings(q: String): List<SettingEntry> {
    val words = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return SettingsIndex.filter { Public.dev || "dev" !in it.pages }.mapNotNull { e ->
        val title = e.title.lowercase()
        val hay = "$title ${e.sub.lowercase()} ${e.keywords} ${e.pages.joinToString(" ") { (P[it] ?: it).lowercase() }}"
        if (words.all { hay.contains(it) }) {
            val score = when {
                title.startsWith(words.first()) -> 0
                words.all { title.contains(it) } -> 1
                else -> 2
            }
            score to e
        } else null
    }.sortedBy { it.first }.map { it.second }
}

@Composable
internal fun SettingsSearchBar(query: String, onQuery: (String) -> Unit) {
    val focus = LocalFocusManager.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(54.dp).clip(shape)
            .background(CardBg).border(1.dp, Color(0x14FFFFFF), shape).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, Modifier.size(22.dp), tint = Muted)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search settings", fontSize = 16.sp, color = Muted)
            BasicTextField(
                query, onQuery, Modifier.fillMaxWidth().tvOkKeyboard(), singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = tvKeyboard(KeyboardOptions(imeAction = ImeAction.Search)),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
        }
        if (query.isNotEmpty()) Icon(Icons.Default.Close, "Clear", Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).clickable { onQuery("") }, tint = Muted)
    }
}

@Composable
internal fun SettingsSearchResults(query: String, open: (String) -> Unit) {
    val results = remember(query) { searchSettings(query) }
    if (results.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.SearchOff, null, Modifier.size(44.dp), tint = Muted)
            Text("No settings match \"$query\"", Modifier.padding(top = 12.dp), color = Muted, fontSize = 15.sp)
        }
        return
    }
    SectionLabel("${results.size} result" + if (results.size == 1) "" else "s")
    SettingsCard {
        results.forEachIndexed { i, e ->
            if (i > 0) RowDivider(20.dp)
            Row(
                Modifier.fillMaxWidth().clickable { e.pages.forEach(open) }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(highlight(e.title, query), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text(e.pages.joinToString("  ›  ") { P[it] ?: it }, fontSize = 13.sp, color = Accent, modifier = Modifier.padding(top = 2.dp))
                    Text(e.sub, fontSize = 13.sp, color = Muted)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)
            }
        }
    }
}

private fun highlight(text: String, q: String) = buildAnnotatedString {
    val w = q.trim().lowercase().split(Regex("\\s+")).firstOrNull().orEmpty()
    val i = if (w.isEmpty()) -1 else text.lowercase().indexOf(w)
    if (i < 0) append(text) else {
        append(text.substring(0, i))
        withStyle(SpanStyle(color = Accent, fontWeight = FontWeight.Bold)) { append(text.substring(i, i + w.length)) }
        append(text.substring(i + w.length))
    }
}
