package com.example.streamnest

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One chapter of the user manual: a title, an icon and its topics (heading → how it works). */
private data class Chapter(val title: String, val icon: ImageVector, val topics: List<Pair<String, String>>)

private val ManualChapters = listOf(
    Chapter("Getting started", Icons.Default.RocketLaunch, listOf(
        "Sign in" to "On the first start, sign in with email + password (or Google) or skip. Signed in, every profile's addons, settings, collections, My List and progress sync to your other phones and TVs within seconds (Settings › Account & Cloud Sync).",
        "Choose your experience" to "Elite loads ahead and switches quality by itself; Regular is the classic way. Change it any time in Settings › Playback › Streaming mode. \"Elite only on Wi-Fi\" keeps mobile data light.",
        "Add addons" to "Xstream plays from Stremio-style addons. Settings › Content & Discovery › Addons: paste an addon link and install. Reorder, refresh or switch addons off there. Quick Start (same section) chooses the fast addons Play uses first.",
        "Who's watching?" to "Pick a profile. Your last profile has a red ring and opens by itself when the ring completes (Settings › Advanced › Startup: timer, or skip the screen). Up to 5 profiles, each with its own addons, settings, history and watchlist.",
        "Profile lock" to "My Space › Edit (pencil) › a profile › Profile lock › Set PIN (4–6 digits). Opening that profile then asks for the PIN. Changing or removing a PIN asks for the current one.",
        "Kids profile" to "In Edit profile, switch on Kids profile: it shows only animated and family titles.",
    )),
    Chapter("Home", Icons.Default.Home, listOf(
        "Hero cards" to "The big cards at the top slide by themselves; swipe them to take over (one card per swipe). Tap a card to open the title, + to add it to My List, ▶ Trailer for the full-screen trailer.",
        "Card trailers" to "Hold (long-press) a card to turn card trailers on: the card you land on plays its trailer inside the card. Hold again to turn them off. Sound is on; the speaker button mutes it, and your choice is kept for the next cards.",
        "Continue Watching" to "Big cards with the time left, a red \"Next · S5 E12\" when the next episode is ready, or a gold \"New episode in 3 days\". Long-press a card: Go to details, Play manually, Start from beginning, Remove.",
        "Rows" to "Recommended for you (from your votes, watchlist and the AI), Top 10 rows with big rank numbers, and your Collections (Streaming services, Genres, Studios…). Change rows in Settings › Layout › Home layout.",
        "Capsule, AI button and bar" to "The filter capsule and the AI button shrink while you scroll down and come back when you scroll up. Tap Home again to jump to the top and refresh. Long-press My Space in the bottom bar to switch profiles quickly.",
    )),
    Chapter("Title page", Icons.Default.Movie, listOf(
        "Play button" to "Plays (or resumes) the title. Series: the episode you're on, or the next one.",
        "The status light on Play" to "A small light in the corner of Play tells you what is prepared:\n• grey blinking = preparing\n• green = plays instantly (a paused player is already waiting)\n• amber = plays the normal way\n• red = no stream found\nLong-press the light for the details.",
        "More options" to "Long-press Play or tap ⋯: Play manually (choose a source), Start from beginning, Watchlist, Watch trailer, All trailers & videos, Download, Share.",
        "Trailer button" to "Tap = the newest official trailer in the Xstream player. Hold = every trailer, teaser and clip, newest first. The trailer player has CC, Fit / Fill and Zoom.",
        "Episodes" to "Seasons and episodes with watched marks. Tap an episode to play it; long-press to choose its source yourself.",
        "Cast and similar titles" to "Tap anyone in the cast for their actor page (biography and every role). \"If you liked…\" shows similar titles with a match %.",
    )),
    Chapter("How Xstream picks a stream", Icons.Default.AutoAwesome, listOf(
        "Instant play" to "When a title page opens, Xstream finds the streams, checks the best link and saves its first ~15–60 seconds (or the part at your resume point) to storage. Play then starts from storage like a file on a hard disk. Saved video uses up to 2 GB; the oldest is removed first.",
        "Fast start" to "Pressing Play opens the player at once. The best three links (your quality, language and the Source Test ranking) are asked at the same time and the first server that answers with a video plays; a link that sends nothing for 6 s is replaced. With Parallel download (Settings › Playback) the start of a video — and, while you watch, the part just ahead — comes in several pieces at once, like a download manager.",
        "Which link" to "Your quality (4K / Full HD / Mid HD / Low) first, then your languages (Hindi → English → original), then the Source Test ranking (most reliable and fastest providers first). Very heavy files (REMUX, TrueHD) and links that already failed are tried last.",
        "When a link fails" to "Refused links and web pages go straight to the next source; a link that doesn't start within 8–10 s is switched. Dead links (web pages, \"not found\", expired) are hidden from Sources. Only when every link of a source fails do other addons get tried.",
        "Tested sources only" to "On by default: provider groups that keep failing in the Source Test are hidden from Sources and never picked; never-tested ones always stay. Servers that only send web pages are hidden as a whole. Switch it in Developer's Corner › Source Test.",
        "Choosing yourself" to "Player › More › Sources (Quick Start and All sources tabs), or Play manually on the title page: the full sources page with Fusion badges (4K, HDR, Dolby Vision, Atmos, 5.1…). Stream filters (Content & Discovery) include, exclude or force tags like HDR or 4K.",
    )),
    Chapter("The player", Icons.Default.PlayCircle, listOf(
        "Show / hide controls" to "Tap the video. Controls hide after 4 s — never while your finger is on them, on the seek bar or in a panel.",
        "Play / pause" to "The big button in the middle, or double-tap the middle of the screen. Earbuds and Bluetooth play/pause buttons work too.",
        "Seeking" to "Drag the seek bar to see a preview of that moment. Double-tap the left / right side: back / forward (30 s with a bubble that adds up while the controls are hidden, 10 s while they're shown).",
        "Speed" to "Press and hold anywhere for 2×; keep holding and slide right / left to change it from 0.5× to 4×. Lift to go back. Or More › Speed.",
        "Brightness, volume, zoom" to "Swipe up / down on the left side for brightness, on the right for volume. Pinch out to fill the screen, pinch in for the original size. Videos start at your chosen brightness and volume (Settings › Playback); your phone's volume comes back when you leave.",
        "Top bar" to "Title, episode and source on the left. On the right: clock, Watch together (people icon), lock (blocks touches — tap the lock to unlock), quality (shows what's really playing; pick 4K / Full HD / Mid HD / Low) and back.",
        "Bottom row" to "Watch Next · Audio & Subtitles · Episodes · Next Episode · ›. The › adds Sleep, Picture-in-picture, Sources, Aspect ratio, Speed, Open in another player and Info. The row scrolls sideways and fades softly at its rounded edges.",
        "Skip intro / recap" to "A Skip Intro / Skip Recap button appears for episodes with known timestamps. Settings › Playback › Skip segments › Auto-skip jumps over them by itself (once each — seek back to watch).",
        "Next episode" to "Near the end credits a Next Episode pill appears with a ring counting down the credits; tap it to start the next episode now. The next episode is prepared in advance so it starts without buffering.",
        "Pause info" to "Pause for a few seconds and an info screen shows the title, year, rating and story. Tap to close it.",
        "Remembered for you" to "Aspect ratio, audio language and subtitle language carry over to the next videos. \"Resumed at …\" offers Start from beginning.",
    )),
    Chapter("Audio & subtitles", Icons.Default.Subtitles, listOf(
        "Audio" to "Audio & Subtitles opens three columns: Audio (your languages first; other language versions of the same title too) | Subtitles | Style. Dolby Digital, DD+, DTS and TrueHD play through the built-in decoder.",
        "Subtitles" to "Subtitles inside the video first, then online ones (OpenSubtitles and your subtitle addons) in your languages — add one without leaving the video. Choose languages in Settings › Playback.",
        "Style" to "Size, colour, background, bold and outline — changes show instantly.",
    )),
    Chapter("Sleep timer, sound & picture-in-picture", Icons.Default.Bedtime, listOf(
        "Sleep timer" to "More › Sleep: 15 / 30 / 45 / 60 minutes or the end of this video. The sound fades out over the last 30 seconds, then the video pauses.",
        "Audio boost & Night mode" to "Audio & Subtitles › Sound (under the audio tracks). Audio boost makes phone speakers up to ~2× louder. Night mode softens loud scenes and lifts quiet dialogue. Both are remembered.",
        "Picture-in-picture" to "More › Picture-in-picture, or simply go Home while a video plays: it keeps playing in a small window you can move around. Turn the automatic one off in Settings › Playback › Picture-in-picture.",
    )),
    Chapter("Watch together", Icons.Default.Groups, listOf(
        "Start a room" to "Play something › the people icon at the top right › Start a room with this video. You get a 4-letter code. You need to be signed in.",
        "Join a room" to "My Space › Watch together, or the people icon in the player, then type the code. The same video opens at the same second.",
        "In the room" to "Play, pause, seek or a source change by anyone happens for everyone; the host's player keeps the time and anyone more than 2.5 s off is moved to it. Tap the reaction buttons to send hearts, stars, laughs or fire to everyone's screen. The host's \"End the room\" closes it for all.",
    )),
    Chapter("Downloads", Icons.Default.Download, listOf(
        "Download" to "Title page › ⋯ (or long-press Play) › Download. Xstream picks the best plain video file (your quality and language, the most reliable provider) and downloads it with Android's download manager — you'll see its progress in the notification shade.",
        "Watch offline" to "My Space › Downloads: tap a finished one to play it. Play on a downloaded title's page also plays the file. No internet needed.",
        "Delete" to "The bin button in My Space › Downloads, or ⋯ › Remove download. Downloads live in Xstream's own folder (removed with the app). Streaming playlists (HLS / DASH) can't be downloaded as one file — Xstream skips them.",
    )),
    Chapter("Plugins", Icons.Default.Extension, listOf(
        "What they are" to "Small JavaScript files that find streams for a title, asked together with your addons. Their links go through the same ranking, filters and fast start.",
        "Install" to "Settings › Content & Discovery › Plugins: paste a link to the .js file (or the code) and tap Install. Switch plugins on / off, Test them, Update or Remove them there; their messages appear in the Log.",
    )),
    Chapter("Search & discover", Icons.Default.Search, listOf(
        "Search" to "Type 3 letters or more: results appear as soon as the first source answers, with title logos, year and runtime; suggestions and recent searches too.",
        "Moods" to "Before you type, pick a mood (Chill, Thriller night, Family…) for a row of fitting titles from your Home rows. \"Surprise me\" opens a well-rated random title.",
    )),
    Chapter("AI assistant X", Icons.Default.Mic, listOf(
        "Set up" to "Settings › Integrations: add a free Groq key (or Gemini, OpenRouter, ChatGPT). If a model isn't available for your key, Xstream switches to one that is.",
        "Talk or type" to "Tap the AI orb on Home. X answers out loud, listens again by itself, and you can interrupt any time. Your words appear live as you speak; X replies in English, Hindi or Hinglish and can search, recommend and open titles. You can also type.",
        "Her voice" to "Settings › Integrations › Her voice: pick a voice (\"online\" voices sound the most human), tap Hear for a sample, set the talking speed.",
        "AI logo" to "Settings › Integrations › AI button logo: pick an image (a transparent PNG looks best) or paste a link. It syncs to every phone you sign in on.",
        "Votes & recommendations" to "After watching, \"How was it?\" asks Go for it / Skip / Just surfing. Your votes, watchlist and the AI build the Recommended row.",
    )),
    Chapter("My Space & profiles", Icons.Default.Person, listOf(
        "My Space" to "Your profiles, Watchlist, Continue Watching, Watch together, Downloads and Help & Settings.",
        "Profile pictures" to "Edit profile › Choose file (photo, GIF or a video up to 15 s that loops silently), From URL, or GIFs (search GIPHY, preview it in the round frame, use it). Animated pictures stay animated on your other devices. GIF search needs a free GIPHY key (Settings › Integrations › GIPHY).",
        "Profiles" to "Add (up to 5), rename, Kids profile, lock with a PIN, delete. Each profile has its own addons, settings, collections, history and watchlist.",
    )),
    Chapter("Home screen widget", Icons.Default.Widgets, listOf(
        "Add it" to "Long-press your phone's home screen › Widgets › Xstream › Continue watching.",
        "Use it" to "It shows your latest title with its progress and time left. Tap Resume: Xstream opens the title and continues where you stopped.",
    )),
    Chapter("Settings tour", Icons.Default.Settings, listOf(
        "Account & Cloud Sync" to "Sign in, auto sync, sync now, sign out. Some settings can be kept separate for phone and TV.",
        "Layout" to "Themes (classic, gradient, custom, AMOLED black), app icon (Red Glow, Ribbon X, Halo Play), home layout (hero on/off, slide speed, rows), Continue Watching style, poster style, navigation bar, stream badges.",
        "Content & Discovery" to "Addons, Quick Start (fast addons + quality + language order + addon speed list), Stream filters, Collections (paste or import Nuvio collections JSON).",
        "Playback" to "Streaming mode (Elite / Regular), player engine (Auto / VLC first), auto stream selection (first source or regex patterns), brightness & volume at start, subtitle languages and style, skip segments, picture-in-picture, external player.",
        "Network & DNS" to "Cloudflare, Google, Quad9 or AdGuard secure DNS for the whole app, including video.",
        "Integrations" to "AI keys and voice, AI logo, recommendations, TMDB (sharper artwork, cast, trailers), MDBList (more ratings), GIPHY, Trakt.",
        "Notifications" to "New-episode alerts for shows in My List and Continue Watching.",
        "Updates" to "When a new version exists, a pop-up offers Update now, Later or Skip this version (also Settings › Update Log). Xstream downloads and installs it for you.",
        "Advanced" to "Startup animation, profile auto-open timer and cache.",
    )),
    Chapter("Developer's Corner", Icons.Default.Code, listOf(
        "Opening it" to "Settings › Advanced › Developer's Corner. Your fingerprint opens it (the password still works); switch that in Developer's Corner › Fingerprint. App name and logo editing live here.",
        "Source Test" to "Plays every link of random popular movies and series until the first picture (in the background or on screen), ranks providers per quality and language, can arrange your addons and Quick Start, and writes a full log (Save to Downloads / Share). Results add up over runs; failures from your real plays appear at the top of the next log.",
        "Ranking" to "Upload shares your ranking with every Xstream user (owner account only); Reload shared gets the latest; Reset starts over. Switches: Hide dead links, Tested sources only, Let Source Test arrange my addons.",
        "Play timing log" to "Every Play, step by step with times (tap → player → link → tracks → first picture). Copy, share or clear it.",
        "Playback log & fingerprint" to "Why links failed is kept in the playback log. Fingerprint for Developer's Corner and for locked profiles is switched here. The screen stays awake while the Source Test runs.",
    )),
    Chapter("Xstream on TV", Icons.Default.Tv, listOf(
        "Home" to "◀ at the left edge opens the menu (profile, Search, Home, Library, My Space, Settings); ▶ hides it. ◀ ▶ change hero cards, ▼ to Play, ▶ to +.",
        "Player with the remote" to "▲ shows the controls (and pauses), ▼ hides them and plays on. Controls hidden: ◀ ▶ jump 30 s. On the seek bar ◀ ▶ move 10 s with a preview, OK plays from there.",
        "Typing" to "Text boxes open the keyboard only when you press OK on them.",
    )),
    Chapter("Troubleshooting", Icons.Default.Build, listOf(
        "Red light / nothing plays" to "No addon returned a working link. Check your addons (Settings › Content & Discovery), try Play manually, or run the Source Test. Your connection or DNS can matter: try another DNS in Network & DNS.",
        "Slow start" to "Wait for the green light before pressing Play for an instant start. Developer's Corner › Play timing log shows where the time went — share it with the developer.",
        "No sound / wrong colours" to "Audio formats your phone can't play are greyed out in Audio — pick another track. Some Dolby Vision-only files (no HDR) may show odd colours on phones without Dolby Vision; pick another file in Sources.",
        "The app closed" to "On the next start Xstream shows what went wrong — take a screenshot and send it.",
        "Update won't install" to "Allow \"Install unknown apps\" for Xstream when Android asks; updates install over the old app and keep your data.",
    )),
)

/** Settings › User manual: every feature explained, with a search box. */
@Composable
fun ManualPage(onBack: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(setOf<String>()) }
    Column(Modifier.fillMaxSize().pageScroll().navigationBarsPadding().padding(bottom = 40.dp)) {
        PageHeader("User manual", onBack)
        Text("Everything Xstream can do, and how. Tap a chapter to open it.", Modifier.padding(horizontal = 20.dp), fontSize = 14.sp, color = Muted)
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).tvOkKeyboard(), singleLine = true,
            placeholder = { Text("Search the manual (e.g. trailer, lock, download)") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            shape = RoundedCornerShape(14.dp), keyboardOptions = tvKeyboard())
        val term = q.trim().lowercase()
        ManualChapters.filter { Public.dev || it.title != "Developer's Corner" }.forEach { ch ->
            val topics = if (term.isEmpty()) ch.topics else ch.topics.filter { (h, b) -> h.lowercase().contains(term) || b.lowercase().contains(term) || ch.title.lowercase().contains(term) }
            if (topics.isEmpty()) return@forEach
            val expanded = term.isNotEmpty() || ch.title in open
            val turn by animateFloatAsState(if (expanded) 90f else 0f, label = "chev")
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF15161C))) {
                Row(
                    Modifier.fillMaxWidth().clickable { open = if (ch.title in open) open - ch.title else open + ch.title }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(ch.icon, null, Modifier.size(22.dp), tint = Accent)
                    Text(ch.title, Modifier.weight(1f).padding(start = 12.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("${topics.size}", fontSize = 12.sp, color = Muted)
                    Icon(Icons.Default.ChevronRight, null, Modifier.padding(start = 6.dp).size(20.dp).rotate(turn), tint = Muted)
                }
                AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                        topics.forEach { (h, b) ->
                            Text(h, Modifier.padding(top = 10.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(b, Modifier.padding(top = 3.dp), fontSize = 13.sp, color = Color(0xFFC9CDD6), lineHeight = 19.sp)
                        }
                    }
                }
            }
        }
    }
}
