package com.example.streamnest

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

/*
 * EDIT TEXTS HERE (defaults) or inside the app: Settings -> Developer's Corner.
 * Values changed inside the app are saved on the phone and override these defaults.
 */
val Defaults: Map<String, String> = linkedMapOf(
    "appName" to "Xstream",
    "showBrand" to "yes",
    "forYou" to "For You",
    "badge" to "#{rank} in {catalog} Today",
    "newOn" to "New on {app}",
    "watchNow" to "Watch Now",
    "continueWatching" to "Continue Watching for {name}",
    "recommendedFor" to "Recommended for {name}",
    "myList" to "My List",
    "pillLeft" to "TV",
    "pillRight" to "Movies",
    "pillSports" to "Sports",
    "navHome" to "Home",
    "navMySpace" to "My Space",
    "pillLive" to "Live",
    "helpSettings" to "Help & Settings",
    "whoWatching" to "Who's watching?",
    "navSearch" to "Search",
    "navLive" to "Live TV",
    "navLibrary" to "Library",
    "navProfile" to "Profile",
    "searchHint" to "Search movies & series",
    "playBtn" to "Play",
    "chooseSource" to "Choose source",
    "noStreams" to "No playable (direct URL) streams found. Install an addon that provides streams.",
    "emptyMsg" to "Nothing to show. Check your connection or add an addon.",
    "addonsTitle" to "Sources",
    "addonsHint" to "Paste an addon link (https:// or stremio://). Addons provide catalogs, streams and subtitles.",
    "addonUrlHint" to "Addon URL",
    "installBtn" to "Install",
    "profileName" to "Guest",
    "autoSlide" to "yes",
    "slideSeconds" to "5",
    // Settings values (changed from Help & Settings, not listed in the text editor)
    "theme" to "Crimson",
    "accent2" to "#FF6A5C",
    "amoled" to "no",
    "navStyle" to "adaptive",
    "showHero" to "yes",
    "rowsHidden" to "",
    "rowsOrder" to "",
    "cwShow" to "yes",
    "cwStyle" to "card",
    "posterWidth" to "Balanced",
    "posterCorner" to "Rounded",
    "landscapePosters" to "yes",
    "rankedRows" to "yes",
    "addonPosters" to "yes",
    "hideLabels" to "no",
    "addonsOff" to "",
    "rememberProfile" to "no",
    "lastProfile" to "",
    "showAddonName" to "yes",
    "fusionBadges" to "yes",
    "badgePack" to "",
    "tagRules" to "",
    "fastStart" to "yes",
    "reuseLastLink" to "yes",
    "splash" to "yes",
    "autoProfileSecs" to "5",
    "showRecs" to "yes",
    "recsAfterCw" to "yes",
    "notifyOn" to "yes",
    "subLangs" to "english",
    "autoQuality" to "yes",
    "askVote" to "yes",
    "aiOn" to "yes",
    "aiProvider" to "groq",
    "aiLogoUrl" to "",
    "tmdbKey" to "",
    "mdbKey" to "",
    "detailRatings" to "yes",
    "detailCast" to "yes",
    "detailMore" to "yes",
    "qsOn" to "yes",
    "playerEngine" to "exo",
    "qsAddons" to "",
    "qsLangs" to "hindi,english,original",
    "qsQuality" to "auto",
    "qsHideDupes" to "no",
    "qsPreload" to "wifi",
    // Playback
    "subSize" to "20",
    "subBold" to "yes",
    "subColor" to "#FFFFFF",
    "subBg" to "#00000000",
    "subOutline" to "yes",
    "subNetflix" to "yes",
    "subFollow" to "no",
    "subOutlineColor" to "#000000",
    "playerChoice" to "builtin",
    "autoPlayMode" to "manual",
    "autoRegex" to "",
    "skipIntro" to "yes",
    "autoSkipIntro" to "no",
    "parallelDl" to "yes",
    "autoOnlineSubs" to "yes",
    "avLayout" to "bubble",
    "qsUpgrade" to "yes",
    "subAutoSync" to "yes",
    "autoNext" to "no",
    "nextThreshold" to "95",
    "showAdvisory" to "yes",
    "pauseInfo" to "yes",
    "startBright" to "yes",
    "startBrightLevel" to "50",
    "startBrightMode" to "fixed",
    "startVol" to "yes",
    "startVolLevel" to "50",
    "startVolMode" to "fixed",
    "dns" to "system",
    "cardTrailers" to "yes",
    "accent" to "#E50914",
    "background" to "#07080C",
    "deckTint" to "#3A0E17",
    "cardCorner" to "34",
    // Collections (Nuvio-style)
    "traktClientId" to "",
    "colOrder" to "",
    "colHidden" to "",
    "showCollections" to "yes",
    // Player: preview frames above the seek bar while seeking (phone drag, TV remote)
    "seekThumbs" to "yes",
)

data class Field(val key: String, val label: String)

val TextFields = listOf(
    Field("showBrand", "Show app name on Home (yes / no)"),
    Field("forYou", "Home title"),
    Field("badge", "Badge text ({rank} = number, {catalog} = row name, empty = hide)"),
    Field("newOn", "First row title ({app} = app name)"),
    Field("watchNow", "Watch button"),
    Field("continueWatching", "Continue row title ({name} = profile name)"),
    Field("recommendedFor", "Recommended row title ({name} = profile name)"),
    Field("myList", "My List title"),
    Field("pillLeft", "Pill tab 1 (shows series)"),
    Field("pillRight", "Pill tab 2 (shows movies)"),
    Field("pillSports", "Pill tab 3 (only appears if an addon has a sports catalog)"),
    Field("navHome", "Bottom bar: Home label"),
    Field("navMySpace", "Bottom bar: My Space label"),
    Field("pillLive", "Pill tab: Live TV"),
    Field("helpSettings", "My Space: settings button"),
    Field("whoWatching", "Profile picker title"),
    Field("navSearch", "Bottom bar: Search label"),
    Field("navLive", "Bottom bar: Live TV label"),
    Field("navLibrary", "Bottom bar: Library label"),
    Field("navProfile", "Bottom bar: Profile label"),
    Field("searchHint", "Search placeholder"),
    Field("playBtn", "Play button"),
    Field("chooseSource", "Source dialog title"),
    Field("noStreams", "No streams message"),
    Field("emptyMsg", "Empty home message"),
    Field("addonsTitle", "Addons page title"),
    Field("addonsHint", "Addons page hint"),
    Field("addonUrlHint", "Addon URL placeholder"),
    Field("installBtn", "Install button"),
)

val StyleFields = listOf(
    Field("accent", "Accent color (hex, e.g. #1F80E0)"),
    Field("background", "Background color (hex)"),
    Field("deckTint", "Home top glow color (hex)"),
    Field("cardCorner", "Card roundness (0 to 50)"),
)

object Palette {
    var accent by mutableStateOf(Color(0xFFE50914))
    var bg by mutableStateOf(Color(0xFF07080C))
    var tint by mutableStateOf(Color(0xFF3A0E17))
    var accent2 by mutableStateOf(Color(0xFFFF6A5C))
    var corner by mutableIntStateOf(34)
}

fun parseColor(s: String): Color? = runCatching {
    Color(android.graphics.Color.parseColor(if (s.trim().startsWith("#")) s.trim() else "#${s.trim()}"))
}.getOrNull()

val LocalTexts = compositionLocalOf<Map<String, String>> { emptyMap() }

@Composable
fun t(key: String): String = LocalTexts.current[key] ?: Defaults[key] ?: key

/** Text/icon colour that stays readable on top of the accent colour (e.g. black on the White theme). */
val OnAccent: Color get() = if (Palette.accent.red * 0.299f + Palette.accent.green * 0.587f + Palette.accent.blue * 0.114f > 0.62f) Color.Black else Color.White

fun isOn(v: String) = v.trim().lowercase() == "yes"

/**
 * Settings kept separately for phones and TVs by default (each profile can change this per section with
 * "Same on all devices / Only on this device type"). Everything else follows the profile to every device.
 */
val DefaultDeviceKeys = listOf(
    "qsQuality", "tagRules", "autoRegex", "playerEngine", "playerChoice", "autoPlayMode", "fastStart", "qsPreload",
    "subSize", "subBold", "subColor", "subBg", "subOutline", "subOutlineColor", "seekThumbs",
    "startBright", "startBrightLevel", "startBrightMode", "startVol", "startVolLevel", "startVolMode",
    "navStyle", "cardTrailers", "posterWidth",
)
