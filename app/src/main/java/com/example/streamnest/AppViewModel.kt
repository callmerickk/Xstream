package com.example.streamnest

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import java.security.MessageDigest
import java.security.SecureRandom

const val CINEMETA = "https://v3-cinemeta.strem.io"

class AppViewModel(app: Application) : AndroidViewModel(app), CloudHost {
    /** The open screens (Home underneath). Here, not in the composable, so a rebuilt screen keeps them. */
    val navStack = androidx.compose.runtime.mutableStateListOf<Screen>()
    private val ctx: Context get() = getApplication<Application>()
    private val sp = app.getSharedPreferences("addons", Context.MODE_PRIVATE)
    // "library" holds app-wide settings (custom texts, playlist) and also the FIRST profile's watch data,
    // so everything saved by older versions carries over into profile 1 automatically.
    private val glob = app.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val dev = app.getSharedPreferences("dev", Context.MODE_PRIVATE)

    val addons = MutableStateFlow<List<Addon>>(emptyList())
    val shelves = MutableStateFlow<List<Shelf>>(emptyList())
    val loading = MutableStateFlow(true)
    val searchResults = MutableStateFlow<List<Meta>>(emptyList())
    val addError = MutableStateFlow<String?>(null)
    private var searchJob: Job? = null

    val texts = MutableStateFlow(
        (glob.getString("custom", null)?.let { runCatching { Api.json.decodeFromString<Map<String, String>>(it) }.getOrNull() } ?: emptyMap())
            // v0.7 rename: drop the old default name so "StreamX" shows
            .filterNot { it.key == "appName" && it.value.trim().equals("streamnest", ignoreCase = true) }
            // v0.9.3: the old default audio order gains English (Hindi → English → Original audio)
            .mapValues { (k, v) -> if (k == "qsLangs" && v.replace(" ", "") == "hindi,original") "hindi,english,original" else v }
            // v0.9.5: ExoPlayer first (smoothest, like Nuvio) with VLC as backup; the old VLC-first choice moves over once.
            .let { m -> if (m["playerEngine"] == "vlc" && m["engineMigrated"] == null) m + ("playerEngine" to "exo") + ("engineMigrated" to "1") else m }
    )

    // bumped whenever the app logo changes, so screens reload it
    val imgVersion = MutableStateFlow(0)

    // ───────────── Profiles ─────────────
    val profiles = MutableStateFlow(loadProfiles())
    /** null until someone is picked on the "Who's watching?" screen */
    val current = MutableStateFlow<Profile?>(null)

    private var lib: SharedPreferences = glob
    val progress = MutableStateFlow<List<Progress>>(emptyList())
    val myList = MutableStateFlow<List<Meta>>(emptyList())
    val doneKeys = MutableStateFlow<Set<String>>(emptySet())
    val doneMs = MutableStateFlow(0L)
    /** StreamX: where you are in EVERY episode you started (Continue Watching keeps only the latest one per series). */
    val epProgress = MutableStateFlow<Map<String, Progress>>(emptyMap())

    private fun loadProfiles(): List<Profile> {
        val saved = glob.getString("profiles", null)?.let { runCatching { Api.json.decodeFromString<List<Profile>>(it) }.getOrNull() }
        if (!saved.isNullOrEmpty()) return saved
        // First run of this version: turn the existing user into profile 1 and add a Kids profile.
        val oldName = texts.value["profileName"]?.takeIf { it.isNotBlank() && it != "Guest" } ?: "Me"
        val oldAvatar = File(ctx.filesDir, "profile_avatar.img")
        val p1 = Profile("p1", oldName, avatar = if (oldAvatar.exists()) "img" else "")
        if (oldAvatar.exists()) oldAvatar.renameTo(avatarFile(p1))
        val list = listOf(p1, Profile("kids", "Kids", kids = true))
        glob.edit().putString("profiles", Api.json.encodeToString(list)).apply()
        return list
    }

    private fun saveProfiles(list: List<Profile>, mark: Boolean = true) {
        profiles.value = list
        glob.edit().putString("profiles", Api.json.encodeToString(list)).apply()
        current.value?.let { c -> current.value = list.firstOrNull { it.id == c.id } }
        if (mark) Cloud.markApp("profiles")
    }

    /** Where a profile's own data lives (profile 1 keeps using the original file, so old data carries over). */
    private fun prefsForId(pid: String): SharedPreferences =
        if (pid == "p1") glob else ctx.getSharedPreferences("library_$pid", Context.MODE_PRIVATE)

    /** 0.9.74 (from Xstream): one of that profile's own settings. */
    fun textOf(pid: String, key: String): String? = if (pid == dataPid) profTexts[key] else runCatching {
        prefsForId(pid).getString("pcustom", null)?.let { Api.json.decodeFromString<Map<String, String>>(it)[key] }
    }.getOrNull()
    /** 0.9.74 (from Xstream): "Picked for you" kept per profile (shown at once on the profile page). */
    fun pickedOf(pid: String): List<Meta> = runCatching {
        prefsForId(pid).getString("pickedFor", null)?.let { Api.json.decodeFromString<List<Meta>>(it) }
    }.getOrNull().orEmpty()
    fun savePicked(pid: String, list: List<Meta>) {
        if (list.isEmpty()) return
        prefsForId(pid).edit().putString("pickedFor", Api.json.encodeToString(list.take(10).map { it.copy(videos = null) })).apply()
    }

    private fun prefsFor(p: Profile): SharedPreferences =
        if (p.id == "p1") glob else ctx.getSharedPreferences("library_${p.id}", Context.MODE_PRIVATE)

    fun selectProfile(p: Profile) {
        if (texts.value["lastProfile"] != p.id) setText("lastProfile", p.id)
        val wasKids = current.value?.kids
        // Each profile has its own settings, addons and collections.
        val switched = dataPid != p.id
        if (switched) loadProfileData(p.id)
        if (!BuildConfig.IS_TV) SearchState.forProfile(p.id)
        current.value = p
        lib = prefsFor(p)
        progress.value = lib.getString("progress", null)?.let { runCatching { warm("p:${p.id}", it) { r -> Api.json.decodeFromString<List<Progress>>(r) } }.getOrNull() } ?: emptyList()
        epProgress.value = lib.getString("eprog", null)?.let { runCatching { Api.json.decodeFromString<Map<String, Progress>>(it) }.getOrNull() } ?: emptyMap()
        WatchLog.load(ctx, p.id)
        myList.value = (lib.getString("mylist", null)?.let { runCatching { warm("l:${p.id}", it) { r -> Api.json.decodeFromString<List<Meta>>(r) } }.getOrNull() } ?: emptyList()).distinctBy { it.id }
        doneKeys.value = lib.getStringSet("done", emptySet())?.toSet() ?: emptySet()
        doneMs.value = lib.getLong("doneMs", 0L)
        votes.value = lib.getString("votes", null)?.let { runCatching { Api.json.decodeFromString<Map<String, Vote>>(it) }.getOrNull() } ?: emptyMap()
        recommended.value = emptyList()
        if (switched || wasKids != p.kids || shelves.value.isEmpty()) refresh()
        refreshRecommended()
        // New-episode alerts: remember this profile's shows (after the addons are ready).
        viewModelScope.launch { delay(8000); runCatching { EpisodeAlerts.snapshot(ctx, this@AppViewModel) } }
        // Look up the streams of your top Continue Watching titles early, so their title pages prepare instantly.
        // TV 0.8.4: on the TV only the top title, only the quick plugins, and only once Home has settled (20 s) — asking
        // every plugin for two titles at start-up was what made a 1 GB Fire TV stick crawl.
        viewModelScope.launch {
            delay(if (BuildConfig.IS_TV) 20_000 else 5000)
            progress.value.sortedByDescending { it.updated }.take(if (BuildConfig.IS_TV) 1 else 3).forEach { pr -> // 0.9.79: phone top 3, one after another
                if (!BuildConfig.IS_TV) TvIdle.waitQuiet(1_500, 15_000)
                val type = if (pr.key.contains(":")) "series" else "movie"
                if (BuildConfig.IS_TV) runCatching {
                    // Only the quick ones: the streaming playlists and MovieBox (their pages are then kept 15 minutes).
                    val q = qsAddons().filter { n -> listOf("netflix", "prime", "hotstar", "moviebox").any { n.contains(it, true) } && !n.endsWith("M") }.toSet()
                    if (q.isNotEmpty()) kotlinx.coroutines.withTimeoutOrNull(30_000) { streamsFlow(type, pr.key, only = q).first { it.second } }
                } else runCatching { if (qsActive()) quickStreams(type, pr.key) else allStreams(type, pr.key) }
            }
        }
    }

    // ───────────── Per-profile storage used by AI, votes and recommendations ─────────────
    /** X's voice settings go with the account (saved with your settings, so every phone you sign in on gets them). */
    fun store(k: String): String? =
        if (k in VoiceKeys) texts.value[k]?.takeIf { it.isNotBlank() } ?: lib.getString(k, null) else lib.getString(k, null)
    fun storePut(k: String, v: String?) {
        lib.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply()
        if (k in VoiceKeys) setText(k, v ?: "")
    }

    /** "How was it?" votes for this profile, by title id. */
    val votes = MutableStateFlow<Map<String, Vote>>(emptyMap())
    val recommended = MutableStateFlow<List<Meta>>(emptyList())
    private var recJob: Job? = null

    fun vote(m: Meta, v: Int) {
        val id = m.id.substringBefore(":")
        val genres = m.genres.orEmpty().ifEmpty { votes.value[id]?.genres.orEmpty() }
        votes.value = votes.value + (id to Vote(id, m.type, m.name, m.poster, genres, v, System.currentTimeMillis()))
        lib.edit().putString("votes", Api.json.encodeToString(votes.value)).apply()
        markCur("votes")
        if (v != 0) { storePut("ai_rec_at", null); refreshRecommended() }
    }

    val appContext: Context get() = ctx

    fun resetVotes() {
        votes.value = emptyMap(); lib.edit().remove("votes").apply(); storePut("ai_rec_at", null); refreshRecommended(); markCur("votes")
    }

    suspend fun setAiLogo(uri: Uri) = withContext(Dispatchers.IO) {
        runCatching { ctx.contentResolver.openInputStream(uri)!!.use { i -> aiLogoFile().outputStream().use { o -> i.copyTo(o) } } }
        // Synced with your settings so the same logo shows on every phone you sign in on.
        val payload = runCatching { aiLogoPayload(aiLogoFile()) }.getOrNull().orEmpty()
        withContext(Dispatchers.Main) {
            setText("aiLogoUrl", ""); setText("aiLogoData", payload)
            ctx.getSharedPreferences("ai_logo", Context.MODE_PRIVATE).edit().putInt("hash", payload.hashCode()).apply()
            imgVersion.value = imgVersion.value + 1
        }
        // On the developer's phone the AI logo goes to every StreamX account (shared/appLook).
        if (Public.dev && payload.isNotBlank()) AppLook.publishAi(logo = payload)
    }

    /** The AI logo for the cloud: a small GIF as it is (stays animated), else a 256 px PNG. */
    fun aiLogoPayload(f: java.io.File): String {
        val bytes = f.readBytes()
        val gif = bytes.size > 6 && String(bytes, 0, 3) == "GIF"
        if (gif && bytes.size <= 400_000) return "gif:" + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return ""
        val scale = 256f / maxOf(bmp.width, bmp.height)
        val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
        val out = java.io.ByteArrayOutputStream()
        small.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        return "png:" + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    /** A logo that came from the cloud (another phone): written to this phone's logo file once. */
    fun syncAiLogo(payload: String) {
        val prefs = ctx.getSharedPreferences("ai_logo", Context.MODE_PRIVATE)
        if (payload.isBlank() || prefs.getInt("hash", 0) == payload.hashCode()) return
        // The developer removed the logo: the built-in one again.
        if (payload == "none") { aiLogoFile().delete(); prefs.edit().putInt("hash", payload.hashCode()).apply(); imgVersion.value = imgVersion.value + 1; return }
        val data = if (payload.startsWith("url:"))
            runCatching { Api.http.newCall(okhttp3.Request.Builder().url(payload.removePrefix("url:")).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null } }.getOrNull() ?: return
        else runCatching { android.util.Base64.decode(payload.substringAfter(':'), android.util.Base64.DEFAULT) }.getOrNull() ?: return
        runCatching { aiLogoFile().writeBytes(data) }.onSuccess {
            prefs.edit().putInt("hash", payload.hashCode()).apply()
            imgVersion.value = imgVersion.value + 1
        }
    }

    /** This phone's AI logo, ready for the cloud ("" = none). */
    // ── The AI face (separate from the button logo) ──
    suspend fun setAiFace(uri: Uri) = withContext(Dispatchers.IO) {
        runCatching { ctx.contentResolver.openInputStream(uri)!!.use { i -> aiFaceFile().outputStream().use { o -> i.copyTo(o) } } }
        val payload = runCatching { aiLogoPayload(aiFaceFile()) }.getOrNull().orEmpty()
        withContext(Dispatchers.Main) {
            setText("aiFaceData", payload)
            ctx.getSharedPreferences("ai_face", Context.MODE_PRIVATE).edit().putInt("hash", payload.hashCode()).apply()
            imgVersion.value = imgVersion.value + 1
        }
        if (Public.dev && payload.isNotBlank()) AppLook.publishAi(face = payload)
    }
    fun syncAiFace(payload: String) {
        val prefs = ctx.getSharedPreferences("ai_face", Context.MODE_PRIVATE)
        if (payload.isBlank() || prefs.getInt("hash", 0) == payload.hashCode()) return
        if (payload == "none") { aiFaceFile().delete(); prefs.edit().putInt("hash", payload.hashCode()).apply(); imgVersion.value = imgVersion.value + 1; return }
        val data = runCatching { android.util.Base64.decode(payload.substringAfter(':'), android.util.Base64.DEFAULT) }.getOrNull() ?: return
        runCatching { aiFaceFile().writeBytes(data) }.onSuccess { prefs.edit().putInt("hash", payload.hashCode()).apply(); imgVersion.value = imgVersion.value + 1 }
    }
    fun clearAiFace() {
        aiFaceFile().delete(); setText("aiFaceData", "")
        ctx.getSharedPreferences("ai_face", Context.MODE_PRIVATE).edit().remove("hash").apply()
        imgVersion.value = imgVersion.value + 1
    }
    fun aiFaceNow(): String = runCatching { aiFaceFile().takeIf { it.exists() }?.let { aiLogoPayload(it) } }.getOrNull().orEmpty()

    fun aiLogoNow(): String = runCatching { aiLogoFile().takeIf { it.exists() }?.let { aiLogoPayload(it) } }.getOrNull().orEmpty()

    fun clearAiLogo() {
        aiLogoFile().delete(); setText("aiLogoData", "")
        ctx.getSharedPreferences("ai_logo", Context.MODE_PRIVATE).edit().remove("hash").apply()
        imgVersion.value = imgVersion.value + 1
    }

    fun refreshRecommended() {
        recJob?.cancel()
        recJob = viewModelScope.launch { runCatching { recommended.value = buildRecommendations(); if (!BuildConfig.IS_TV) current.value?.let { p -> savePicked(p.id, recommended.value) } } }
    }

    /** Back to the "Who's watching?" screen. */
    fun leaveProfile() { current.value = null }

    fun addProfile(name: String, kids: Boolean): Profile {
        val p = Profile("p" + System.currentTimeMillis(), name.trim().ifEmpty { "Profile" }, kids)
        // A new profile starts with a copy of the current profile's settings, addons and collections.
        dataPid?.let { src ->
            val from = prefsForId(src)
            prefsForId(p.id).edit().apply {
                from.getString("pcustom", null)?.let { putString("pcustom", it) }
                from.getString("paddons", null)?.let { putString("paddons", it) }
            }.apply()
            runCatching { colFileFor(src).takeIf { it.exists() }?.copyTo(colFileFor(p.id), overwrite = true) }
        }
        saveProfiles(profiles.value + p)
        Cloud.markProfileAll(p.id)
        return p
    }

    fun renameProfile(p: Profile, name: String, kids: Boolean) {
        val kidsChanged = p.kids != kids
        saveProfiles(profiles.value.map { if (it.id == p.id) it.copy(name = name.trim().ifEmpty { it.name }, kids = kids) else it })
        if (kidsChanged && current.value?.id == p.id) refresh()
    }

    fun deleteProfile(p: Profile) {
        if (profiles.value.size <= 1) return
        avatarFile(p).delete()
        if (p.id != "p1") ctx.deleteSharedPreferences("library_${p.id}")
        else glob.edit().remove("progress").remove("mylist").remove("done").remove("doneMs").remove("pcustom").remove("paddons").apply()
        runCatching { colFileFor(p.id).delete() }
        saveProfiles(profiles.value.filter { it.id != p.id })
        Cloud.deleteProfile(p.id)
        if (current.value?.id == p.id) current.value = null
        if (dataPid == p.id) profiles.value.firstOrNull()?.let { loadProfileData(it.id) }
    }

    fun avatarFile(p: Profile) = File(ctx.filesDir, "avatar_${p.id}")

    /** Copies the picked picture / GIF / video. Returns an error message, or null when it worked. */
    suspend fun setAvatar(p: Profile, uri: Uri): String? = withContext(Dispatchers.IO) {
        val type = ctx.contentResolver.getType(uri).orEmpty()
        val kind = when {
            type.startsWith("video/") -> "video"
            type == "image/gif" -> "gif"
            type.startsWith("image/") -> "img"
            else -> return@withContext "Pick a JPEG, PNG, GIF or a short video"
        }
        if (kind == "video") {
            val ms = runCatching {
                MediaMetadataRetriever().run {
                    setDataSource(ctx, uri)
                    val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    release(); d
                }
            }.getOrDefault(0L)
            if (ms > 15_500) return@withContext "Video must be 15 seconds or shorter"
        }
        val size = runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }.getOrDefault(-1L)
        if (size > 25L * 1024 * 1024) return@withContext "File is too big (max 25 MB)"
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { i -> avatarFile(p).outputStream().use { o -> i.copyTo(o) } }
        } catch (e: Exception) {
            return@withContext "Couldn't read that file"
        }
        glob.edit().remove("avatarUrl_${p.id}").apply()
        withContext(Dispatchers.Main) {
            saveProfiles(profiles.value.map { if (it.id == p.id) it.copy(avatar = kind, ver = it.ver + 1) else it })
            Cloud.markApp("avatar_${p.id}")
        }
        null
    }

    /** Profile picture from a web link (JPEG / PNG / GIF / WebP, or a short MP4). */
    suspend fun setAvatarFromUrl(p: Profile, url: String): String? = withContext(Dispatchers.IO) {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return@withContext "Paste a link that starts with https://"
        val tmp = File(ctx.cacheDir, "avatar_dl")
        try {
            Api.http.newCall(Request.Builder().url(u).header("User-Agent", "Mozilla/5.0").build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext "Couldn't download (error ${r.code})"
                val body = r.body ?: return@withContext "Empty file"
                val len = body.contentLength()
                if (len > 25L * 1024 * 1024) return@withContext "File is too big (max 25 MB)"
                body.byteStream().use { i ->
                    tmp.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024); var total = 0L
                        while (true) {
                            val n = i.read(buf); if (n < 0) break
                            total += n
                            if (total > 25L * 1024 * 1024) return@withContext "File is too big (max 25 MB)"
                            o.write(buf, 0, n)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return@withContext "Couldn't download that link"
        }
        // Work out the type from the file itself, not the link.
        val head = tmp.inputStream().use { i -> ByteArray(12).also { i.read(it) } }
        fun at(i: Int, vararg b: Int) = b.withIndex().all { (k, v) -> (head[i + k].toInt() and 0xFF) == v }
        val kind = when {
            at(0, 0x47, 0x49, 0x46) -> "gif"                               // GIF
            at(0, 0x89, 0x50, 0x4E, 0x47) -> "img"                         // PNG
            at(0, 0xFF, 0xD8, 0xFF) -> "img"                               // JPEG
            at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> "gif" // WebP (may be animated)
            at(4, 0x66, 0x74, 0x79, 0x70) -> "video"                       // MP4 / MOV
            else -> { tmp.delete(); return@withContext "That link isn't an image, GIF or video" }
        }
        if (kind == "video") {
            val ms = runCatching {
                MediaMetadataRetriever().run { setDataSource(tmp.absolutePath); val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L; release(); d }
            }.getOrDefault(0L)
            if (ms > 15_500) { tmp.delete(); return@withContext "Video must be 15 seconds or shorter" }
        }
        tmp.copyTo(avatarFile(p), overwrite = true); tmp.delete()
        // Other devices download the same link, so an animated picture stays animated there too.
        glob.edit().putString("avatarUrl_${p.id}", u).apply()
        withContext(Dispatchers.Main) {
            saveProfiles(profiles.value.map { if (it.id == p.id) it.copy(avatar = kind, ver = it.ver + 1) else it })
            Cloud.markApp("avatar_${p.id}")
        }
        null
    }

    fun clearAvatar(p: Profile) {
        avatarFile(p).delete()
        glob.edit().remove("avatarUrl_${p.id}").apply()
        saveProfiles(profiles.value.map { if (it.id == p.id) it.copy(avatar = "", ver = it.ver + 1) else it })
        Cloud.markApp("avatar_${p.id}")
    }

    // ───────────── Custom texts & colors ─────────────
    init {
        Public.vm = this
        StreamHeaders.init(app); AddonSpeed.init(app); Relay.warmUp()
        // Source ranking: this phone's copy now, the shared one from the cloud in the background.
        AutoPlayRules.init(app); Ranker.init(app); HostStats.init(app)
        if (!BuildConfig.IS_TV) { Buddies.init(app); Invites.start(app); if (Feature.roomRejoin) runCatching { Together.resumeIfRecent(app) } }
        ProfileLock.init(app)
        AppLook.init(app); Call.init(app); Vpn.resume(app)
        Downloads.init(app)
        Tour.init(app)
        Plugins.init(app)
        StreamCache.parallel = isOn(texts.value["parallelDl"] ?: "yes")
        PlayTiming.init(app)
        viewModelScope.launch { runCatching { Ranker.fetchShared() } }
        // Once: send animated profile pictures again in full (older versions sent only a still picture).
        viewModelScope.launch {
            delay(15_000)
            if (!glob.getBoolean("avatarResync1", false) && Cloud.user.value != null) {
                profiles.value.filter { it.avatar == "gif" || it.avatar == "video" }.forEach { Cloud.markApp("avatar_${it.id}") }
                glob.edit().putBoolean("avatarResync1", true).apply()
            }
        }
        applyPalette()
        // DNS: set up after the saved settings are loaded (see the init block with appTexts below).
    }

    fun setDns(provider: String, byUser: Boolean = true) {
        if (byUser) storePut("ownKey_dns", "1")
        setText("dns", provider)
        Api.setDns(provider)
        refresh()
    }

    private fun applyPalette() {
        val m = Defaults + texts.value
        parseColor(m["accent"]!!)?.let { Palette.accent = it }
        parseColor(m["background"]!!)?.let { Palette.bg = it }
        parseColor(m["deckTint"]!!)?.let { Palette.tint = it }
        parseColor(m["accent2"]!!)?.let { Palette.accent2 = it }
        if (isOn(m["amoled"]!!)) Palette.bg = Color.Black
        Palette.glass = m["theme"] == "Glass"  // 0.9.75: phones too
        m["cardCorner"]!!.trim().toIntOrNull()?.let { Palette.corner = it.coerceIn(0, 50) }
        // Buttons: the theme's own button colours, else the accent with readable text.
        Palette.button = parseColor(m["btnColor"].orEmpty()) ?: Palette.accent
        Palette.onButton = parseColor(m["btnText"].orEmpty()) ?: buttonTextFor(Palette.button)
    }

    fun setText(key: String, value: String) {
        if (isAppKey(key)) {
            if (appTexts[key] == value) return
            appTexts = appTexts + (key to value)
            glob.edit().putString("custom", Api.json.encodeToString(appTexts)).apply()
            if (key in AppSyncKeys) Cloud.markApp("appTexts")
        } else {
            if (profTexts[key] == value) return
            profTexts = profTexts + (key to value)
            dataPid?.let { pid ->
                prefsForId(pid).edit().putString("pcustom", Api.json.encodeToString(profTexts)).apply()
                Cloud.markProfile(pid, "set"); Cloud.markProfile(pid, Cloud.DEVICE_FIELD)
            }
        }
        texts.value = appTexts + profTexts
        applyPalette()
    }

    fun resetTexts() {
        appTexts = appTexts.filterKeys { it in AppLocalKeys }
        profTexts = emptyMap()
        glob.edit().putString("custom", Api.json.encodeToString(appTexts)).apply()
        dataPid?.let { pid ->
            prefsForId(pid).edit().remove("pcustom").apply()
            Cloud.markProfile(pid, "set"); Cloud.markProfile(pid, Cloud.DEVICE_FIELD)
        }
        Cloud.markApp("appTexts")
        texts.value = appTexts + profTexts
        applyPalette()
    }

    // ───────────── Watch data (per profile) ─────────────
    fun saveProgress(p: Progress) {
        if (p.positionMs < 10_000) return
        ContinueWidget.update(ctx, p)
        WatchLog.record(ctx, current.value?.id, p)
        if (p.key.contains(":")) {
            // Every episode keeps its own place (newest 300).
            val m = epProgress.value + (p.key to p)
            epProgress.value = if (m.size > 300) m.entries.sortedByDescending { it.value.updated }.take(300).associate { it.key to it.value } else m
            lib.edit().putString("eprog", Api.json.encodeToString(epProgress.value)).apply()
            markCur("eprog")
        }
        // One entry per series (the latest episode). A finished EPISODE stays (at 100%) so Continue Watching can offer
        // the next one / say when it airs; a finished movie leaves the row.
        val base = p.key.substringBefore(":")
        val series = p.key.contains(":")
        val others = progress.value.filter { it.key != p.key && !(series && it.key.contains(":") && it.key.substringBefore(":") == base) }
        // Watched: a movie past 92 % (credits) leaves the row; an episode past 95 % (or at its credits) is replaced by
        // the next episode, shown as Up next.
        val done = p.durationMs > 0 && p.positionMs > p.durationMs * (if (series) 0.95 else 0.92)
        // No limit: every title you started stays (and syncs with your cloud) until it is finished or removed.
        progress.value = (if (done && !series) others else listOf(if (done) p.copy(positionMs = p.durationMs) else p) + others)
        if (done && !series && !BuildConfig.IS_TV) addWatched(p)
        val ed = lib.edit().putString("progress", Api.json.encodeToString(progress.value))
        if (done && p.key !in doneKeys.value) {
            doneKeys.value = doneKeys.value + p.key
            doneMs.value = doneMs.value + p.durationMs
            ed.putStringSet("done", doneKeys.value).putLong("doneMs", doneMs.value)
            markCur("done")
        }
        ed.apply()
        markCur("prog")
        if (done && series && !BuildConfig.IS_TV) viewModelScope.launch { runCatching { sweepFinished() } }
    }

    /**
     * 0.9.72 (from Xstream 0.8.7): a finished episode with NOTHING after it (the last episode out so far) leaves Continue
     * watching and goes to Library › Watched. Phone only.
     */
    suspend fun sweepFinished() {
        if (BuildConfig.IS_TV) return
        val done = progress.value.filter { it.isSeries() && it.finished() }
        for (p in done) {
            val meta = runCatching { seriesMeta(p.key.substringBefore(":")) }.getOrNull() ?: continue
            val eps = meta.videos.orEmpty().filter { (it.season ?: 0) > 0 && it.episode != null }.sortedWith(compareBy({ it.season }, { it.episode }))
            val i = eps.indexOfFirst { it.id == p.key }
            if (i >= 0 && eps.getOrNull(i + 1) == null) {
                progress.value = progress.value.filter { it.key != p.key }
                lib.edit().putString("progress", Api.json.encodeToString(progress.value)).apply()
                markCur("prog")
                addWatched(p)
                ActLog.add("APP", "finished: ${p.title} → Watched (no more episodes)")
            }
        }
    }

    // ───────────── Library › Watched (0.9.72, from Xstream) ─────────────
    val watched = MutableStateFlow<List<Progress>>(emptyList())
    fun loadWatched() { watched.value = runCatching { Api.json.decodeFromString<List<Progress>>(lib.getString("watched", null) ?: "[]") }.getOrDefault(emptyList()) }
    private fun addWatched(p: Progress) {
        loadWatched()
        watched.value = (listOf(p.copy(positionMs = 0, updated = System.currentTimeMillis())) + watched.value.filter { it.key != p.key }).take(300)
        lib.edit().putString("watched", Api.json.encodeToString(watched.value)).apply()
    }
    fun removeWatched(key: String) {
        watched.value = watched.value.filter { it.key != key }
        lib.edit().putString("watched", Api.json.encodeToString(watched.value)).apply()
    }

    fun removeProgress(key: String) {
        // StreamX: removing a title from Continue Watching also takes it out of My Space's charts (and its episodes).
        WatchLog.remove(appContext, current.value?.id, key)
        progress.value = progress.value.filter { it.key != key }
        val ed = lib.edit().putString("progress", Api.json.encodeToString(progress.value))
        // A series: every episode's place and watched mark goes too, so opening it again starts from the beginning.
        val base = key.substringBefore(":")
        val epKeys = epProgress.value.keys.filter { it.substringBefore(":") == base }
        if (epKeys.isNotEmpty()) {
            epProgress.value = epProgress.value - epKeys.toSet()
            ed.putString("eprog", Api.json.encodeToString(epProgress.value))
        }
        val doneEps = doneKeys.value.filter { it == key || (it.contains(":") && it.substringBefore(":") == base) }
        if (doneEps.isNotEmpty()) { doneKeys.value = doneKeys.value - doneEps.toSet(); ed.putStringSet("done", doneKeys.value) }
        (epKeys + key).forEach { ed.remove("link_$it") }
        ed.apply()
        markCur("prog")
        if (epKeys.isNotEmpty()) markCur("eprog")
        if (doneEps.isNotEmpty()) markCur("done")
    }

    /** TV 0.9.10: mark one episode watched / unwatched (episode menus). */
    fun setEpWatched(key: String, on: Boolean) {
        val ed = lib.edit()
        if (on) {
            if (key !in doneKeys.value) { doneKeys.value = doneKeys.value + key; ed.putStringSet("done", doneKeys.value); markCur("done") }
            epProgress.value[key]?.let { p -> if (p.durationMs > 0) { epProgress.value = epProgress.value + (key to p.copy(positionMs = p.durationMs)); ed.putString("eprog", Api.json.encodeToString(epProgress.value)); markCur("eprog") } }
            if (progress.value.any { it.key == key }) {
                progress.value = progress.value.map { if (it.key == key && it.durationMs > 0) it.copy(positionMs = it.durationMs) else it }
                ed.putString("progress", Api.json.encodeToString(progress.value)); markCur("prog")
            }
        } else {
            if (key in doneKeys.value) { doneKeys.value = doneKeys.value - key; ed.putStringSet("done", doneKeys.value); markCur("done") }
            if (key in epProgress.value) { epProgress.value = epProgress.value - key; ed.putString("eprog", Api.json.encodeToString(epProgress.value)); markCur("eprog") }
            if (progress.value.any { it.key == key }) {
                progress.value = progress.value.filter { it.key != key }
                ed.putString("progress", Api.json.encodeToString(progress.value)); markCur("prog")
            }
        }
        ed.apply()
    }

    fun clearHistory() {
        progress.value = emptyList()
        doneKeys.value = emptySet()
        doneMs.value = 0L
        lib.edit().remove("progress").remove("done").remove("doneMs").apply()
        markCur("prog"); markCur("done")
    }

    // TV: a profile's saved data is read and decoded in the background while its circle pulses, so opening it doesn't
    // freeze the stick for seconds. selectProfile() takes the decoded copy when the text is unchanged (else decodes).
    private val warmed = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Any?>>()
    @Suppress("UNCHECKED_CAST")
    private fun <T> warm(key: String, raw: String, decode: (String) -> T): T {
        val c = warmed.remove(key)
        if (c != null && c.first == raw) return c.second as T
        return decode(raw)
    }
    /** TV 0.9.0: another profile's Continue watching, for the profile page (read off the main thread, kept for selectProfile). */
    @Suppress("UNCHECKED_CAST")
    suspend fun progressOf(pid: String): List<Progress> = withContext(Dispatchers.Default) {
        runCatching {
            val r = prefsForId(pid).getString("progress", null) ?: return@runCatching emptyList<Progress>()
            (warmed["p:$pid"]?.takeIf { it.first == r }?.second as? List<Progress>)
                ?: Api.json.decodeFromString<List<Progress>>(r).also { warmed["p:$pid"] = r to it }
        }.getOrDefault(emptyList())
    }

    suspend fun prewarmProfile(pid: String) {
        if (!BuildConfig.IS_TV) return
        withContext(Dispatchers.Default) {
            runCatching {
                val pp = prefsForId(pid)
                pp.getString("paddons", null)?.takeIf { warmed["a:$pid"]?.first != it }?.let { r -> warmed["a:$pid"] = r to Api.json.decodeFromString<List<Addon>>(r) }
                pp.getString("progress", null)?.takeIf { warmed["p:$pid"]?.first != it }?.let { r -> warmed["p:$pid"] = r to Api.json.decodeFromString<List<Progress>>(r) }
                pp.getString("mylist", null)?.takeIf { warmed["l:$pid"]?.first != it }?.let { r -> warmed["l:$pid"] = r to Api.json.decodeFromString<List<Meta>>(r) }
                pp.getString("pcustom", null)
            }
        }
    }

    /** TV Library › Clear all: empties Continue Watching only (finished titles and stats stay). */
    fun clearContinue() {
        progress.value.forEach { WatchLog.remove(appContext, current.value?.id, it.key) }
        progress.value = emptyList()
        lib.edit().remove("progress").apply()
        markCur("prog")
    }

    fun toggleList(m: Meta) {
        val cur = myList.value
        myList.value = (if (cur.any { it.id == m.id }) cur.filter { it.id != m.id } else listOf(m) + cur).distinctBy { it.id }
        lib.edit().putString("mylist", Api.json.encodeToString(myList.value)).apply()
        markCur("list")
        if (m.type == "series") runCatching { EpisodeAlerts.snapshot(ctx, this) }
    }

    /** The profile whose watch data is in [lib] changed [field]: sync it. */
    private fun markCur(field: String) { current.value?.id?.let { Cloud.markProfile(it, field) } }

    // ───────────── Addons & catalogs ─────────────
    // (Addons are loaded per profile — see loadProfileData.)

    /** A profile without addons gets Cinemeta, like a fresh install. */
    private fun ensureDefaultAddons(pid: String) {
        viewModelScope.launch {
            val list = runCatching { listOf(Addon(CINEMETA, Api.fetch<Manifest>("$CINEMETA/manifest.json"))) }.getOrDefault(emptyList())
            if (dataPid == pid && addons.value.isEmpty() && list.isNotEmpty()) {
                setAddons(list)
                if (current.value != null) refresh()
            }
        }
    }

    /** Addons switched on in Help & Settings > Content & Discovery > Addons. */
    private val active: List<Addon> get() {
        val off = texts.value["addonsOff"].orEmpty().split("|").filter { it.isNotBlank() }.toSet()
        return addons.value.filter { it.baseUrl !in off }
    }

    /** Base URLs of the switched-on addons that can describe this title (used by the episode-alert check). */
    fun metaBases(type: String, id: String): List<String> = active.filter { it.supports("meta", type, id) }.map { it.baseUrl }

    fun isAddonOn(a: Addon) = a.baseUrl !in texts.value["addonsOff"].orEmpty().split("|")

    fun toggleAddon(a: Addon) {
        val off = texts.value["addonsOff"].orEmpty().split("|").filter { it.isNotBlank() }.toMutableSet()
        if (!off.add(a.baseUrl)) off.remove(a.baseUrl)
        setText("addonsOff", off.joinToString("|"))
        refresh()
    }

    /** Cinemeta (all the titles' details) and OpenSubtitles always stay at the top; other addons can be pinned too. */
    fun isPinnedFixed(a: Addon) = a.baseUrl == CINEMETA || a.manifest.id.contains("opensubtitles", true) || a.manifest.name.contains("opensubtitles", true)
    fun isPinned(a: Addon) = isPinnedFixed(a) || a.baseUrl in texts.value["addonsPinned"].orEmpty().split("|")
    private fun pinFirst(list: List<Addon>): List<Addon> =
        list.filter { isPinnedFixed(it) } + list.filter { isPinned(it) && !isPinnedFixed(it) } + list.filterNot { isPinned(it) }

    fun togglePin(a: Addon) {
        if (isPinnedFixed(a)) return
        val set = texts.value["addonsPinned"].orEmpty().split("|").filter { it.isNotBlank() }.toMutableSet()
        if (!set.add(a.baseUrl)) set.remove(a.baseUrl)
        setText("addonsPinned", set.joinToString("|"))
        setAddons(addons.value)
        refresh()
    }

    /** The addon's own settings page (Stremio addons: …/configure). */
    fun configureUrl(a: Addon): String = a.baseUrl.trimEnd('/').removeSuffix("/manifest.json") + "/configure"

    fun moveAddon(a: Addon, by: Int) {
        val list = addons.value.toMutableList()
        val i = list.indexOf(a)
        val j = (i + by).coerceIn(0, list.lastIndex)
        if (i < 0 || i == j) return
        list.removeAt(i); list.add(j, a)
        setAddons(list)
        refresh()
    }

    fun reloadAddon(a: Addon) {
        viewModelScope.launch {
            runCatching { Api.fetch<Manifest>("${a.baseUrl}/manifest.json") }.onSuccess { m ->
                setAddons(addons.value.map { if (it.baseUrl == a.baseUrl) Addon(a.baseUrl, m) else it })
                refresh()
            }
        }
    }

    fun resourceCount(a: Addon) = (a.manifest.resources as? JsonArray)?.size ?: 0

    val appVersion: String get() = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${pi.versionName} (${pi.versionCode})"
    }.getOrDefault("")

    private fun setAddons(list: List<Addon>) {
        addons.value = pinFirst(list)
        val pid = dataPid ?: return
        prefsForId(pid).edit().putString("paddons", Api.json.encodeToString(list)).apply()
        Cloud.markProfile(pid, "addons")
    }

    private fun enc(id: String) = Uri.encode(id, ":")

    // ───────────── Addon backup / move (Settings › Addons › Export / Import) ─────────────
    /** Settings that travel with the addons. */
    private val backupKeys = listOf("qsAddons", "qsCs", "qs4k", "srcMode", "qsOn", "qsLangs", "qsQuality", "qsUpgrade", "subLangs", "tagRules", "srcAutoManage",
        "testedOnly", "hideDead", "autoOnlineSubs", "subAutoSync")

    /** Everything about your addons as a small JSON text: links in order, on / off, pins, Quick play and the play settings. */
    /** [withAccounts] false: the quick server accounts (PenguPlay, AIOStreams) are left out. */
    fun exportAddons(withAccounts: Boolean = true): String = buildJsonObject {
        put("app", Brand.NAME); put("v", 1); put("at", System.currentTimeMillis())
        put("addons", buildJsonArray {
            addons.value.filter { a -> withAccounts || !listOf("pengu", "aiostream").any { k -> k in (a.manifest.name + " " + a.baseUrl).lowercase() } }.forEach { a -> add(buildJsonObject {
                put("url", a.baseUrl); put("name", a.manifest.name); put("on", isAddonOn(a)); put("pinned", isPinned(a) && !isPinnedFixed(a))
            }) }
        })
        put("settings", buildJsonObject { backupKeys.forEach { k -> texts.value[k]?.let { put(k, it) } } })
    }.toString()

    /** Short summary of an export (for the preview before importing). */
    fun describeBackup(json: String): String? = runCatching {
        val o = Api.json.parseToJsonElement(json).jsonObject
        val list = o["addons"]!!.jsonArray
        val st = o["settings"]?.jsonObject
        val qs = st?.get("qsAddons")?.jsonPrimitive?.contentOrNull?.split("|")?.filter { it.isNotBlank() }.orEmpty()
        val langs = st?.get("qsLangs")?.jsonPrimitive?.contentOrNull?.split(",")?.joinToString(" → ") { it.trim().replaceFirstChar { c -> c.uppercase() } }
        Public.say(listOfNotNull("${list.size} addons", qs.takeIf { it.isNotEmpty() }?.let { "Quick play: " + it.joinToString(" → ") { n -> Public.serverName(n) } }, langs).joinToString(" · "))
    }.getOrNull()

    /** Puts an export in place: [replace] = your addons become exactly that list; else only the missing ones are added. */
    suspend fun importAddons(json: String, replace: Boolean): String {
        val o = runCatching { Api.json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return "That isn't a ${Brand.NAME} addon backup."
        val items = o["addons"]?.jsonArray?.mapNotNull { it as? JsonObject } ?: return "That isn't a ${Brand.NAME} addon backup."
        val have = addons.value.associateBy { it.baseUrl }
        val got = ArrayList<Addon>(); var failed = 0
        for (it in items) {
            val url = it["url"]?.jsonPrimitive?.contentOrNull ?: continue
            val a = have[url] ?: runCatching { Addon(url, Api.fetch<Manifest>("$url/manifest.json")) }.getOrNull()
            if (a == null) failed++ else got += a
        }
        val list = if (replace) got else addons.value + got.filter { g -> addons.value.none { it.baseUrl == g.baseUrl } }
        val added = list.count { it.baseUrl !in have }
        // On / off and pins of the imported addons.
        val off = texts.value["addonsOff"].orEmpty().split("|").filter { it.isNotBlank() }.toMutableSet()
        val pins = texts.value["addonsPinned"].orEmpty().split("|").filter { it.isNotBlank() }.toMutableSet()
        if (replace) { off.clear(); pins.clear() }
        items.forEach { j ->
            val url = j["url"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            if (j["on"]?.jsonPrimitive?.booleanOrNull == false) off += url else off -= url
            if (j["pinned"]?.jsonPrimitive?.booleanOrNull == true) pins += url
        }
        withContext(Dispatchers.Main) {
            setText("addonsOff", off.joinToString("|")); setText("addonsPinned", pins.joinToString("|"))
            // StreamX: addons that came from someone's backup are "borrowed" — their settings page (with that person's
            // account in the link) stays hidden.
            got.filter { it.baseUrl !in have }.forEach { setText("addonBorrowed:${it.baseUrl}", "yes") }
            if (replace) o["settings"]?.jsonObject?.forEach { (k, v) -> if (k in backupKeys) v.jsonPrimitive.contentOrNull?.let { setText(k, it) } }
            setAddons(list)
            refresh()
        }
        return (if (replace) "Imported · your addons now match (${list.size})" else "Merged · $added new addon${if (added == 1) "" else "s"} added") +
            (if (failed > 0) " · $failed couldn't be reached" else "")
    }

    /** Switched-on addons that keep loading on their server (CNCVerse): asked again every 5 s (SlowAddons.kt). */
    fun slowStreamAddons(type: String, id: String): List<Addon> = if (pluginMode()) emptyList() else active.filter { it.supports("stream", type, id) && SlowAddons.isSlow(it) }
    fun streamUrl(a: Addon, type: String, id: String) = "${a.baseUrl}/stream/$type/${enc(id)}.json"

    /** Puts the addons in this order (by name; the ones not named keep their place after them). Source Test uses it. */
    fun reorderAddonsByNames(order: List<String>): Boolean {
        val list = addons.value
        val sorted = list.sortedBy { a -> order.indexOf(a.label()).let { if (it < 0) Int.MAX_VALUE else it } }
        if (sorted.map { it.baseUrl } == list.map { it.baseUrl }) return false
        setAddons(sorted)
        return true
    }

    private fun typeLabel(type: String) = when (type) {
        "movie" -> "Movies"
        "series" -> "Series"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    /**
     * The poster link pattern from Settings › Layout › Poster Card Style (Posters+, RPDB…), like Nuvio's poster override:
     * {imdb} / {imdb_id}, {tmdb} / {tmdb_id} and {type} are filled in. Titles without such an id keep their own poster.
     */
    fun posterFromPattern(m: Meta): Meta {
        val pat = texts.value["posterPattern"]?.trim().orEmpty()
        if (!pat.startsWith("http")) return m
        val id = m.id.substringBefore(':')
        val imdb = m.id.takeIf { it.startsWith("tt") }?.substringBefore(':')
        // "tmdb:movie:299536" or "tmdb:299536" → 299536 (it used to give "movie": broken poster links in collections).
        val tmdb = m.id.takeIf { it.startsWith("tmdb:") }?.split(':')?.lastOrNull()?.takeIf { it.all(Char::isDigit) }
        if (("{imdb" in pat && imdb == null) || ("{tmdb" in pat && tmdb == null)) return m
        val url = pat.replace("{imdb_id}", imdb ?: id).replace("{imdb}", imdb ?: id).replace("{tmdb_id}", tmdb ?: "").replace("{tmdb}", tmdb ?: "")
            .replace("{type}", m.type).replace("{id}", m.id)
        return m.copy(poster = url)
    }

    private var refreshJob: Job? = null

    /** Rows the developer pushed to every account (texts["homeRows"], sent with the developer's defaults). */
    fun pushedRows(): List<PushedRow> = runCatching { Api.json.decodeFromString<List<PushedRow>>(texts.value["homeRows"].orEmpty()) }.getOrDefault(emptyList())

    /** Addon error placeholders shown as titles ("[❌] CNCVerse Bridge · 404 - Not Found"). */
    private fun isErrorCard(m: Meta) = m.id.startsWith("aiostreamserror") || m.name.startsWith("[❌]") || m.name.startsWith("[\u274C]")

    fun refresh() {
        if (!BuildConfig.IS_TV) viewModelScope.launch { delay(4_000); runCatching { sweepFinished() } }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            loading.value = true
            // Home posters only from catalogue addons (Cinemeta, TMDB, Posters+…), never from streaming addons
            // (CNCVerse Bridge / AIOStreams put error cards like "[❌] … 404" in their lists). If every addon streams,
            // they are used after all so Home is never empty.
            val catalogOnly = active.filter { !it.streams && it.manifest.catalogs.isNotEmpty() }.ifEmpty { active }
            // TV 0.9.14: every Home catalog in ONE request through StreamX's Cloudflare Worker (it fetches them all at once
            // over Cloudflare's fast network); any it couldn't get is fetched directly as before.
            // 0.9.75: phones too (one request instead of a dozen slow catalog calls, e.g. Posters+ ~2 s each).
            // 0.9.76: sites that always refuse the Worker (BingeCat: 6 of 33 missed every time) are fetched directly at
            // once instead of waiting for the bundle first.
            fun bundleSkip(u: String) = runCatching { java.net.URI(u).host.orEmpty() }.getOrDefault("").let { h -> h == "bingecat.com" || h.endsWith(".bingecat.com") }
            val bundleJob = async { if (current.value?.kids != true) {
                val urls = catalogOnly.flatMap { a -> a.manifest.catalogs.filter { c -> c.extra.orEmpty().none { it.isRequired } }
                    .map { c -> "${a.baseUrl}/catalog/${c.type}/${enc(c.id)}.json" } }.take(48).filterNot { bundleSkip(it) }
                // TV 0.9.15: the saved Home is already on screen, so the refresh waits until the remote is quiet.
                if (BuildConfig.IS_TV && shelves.value.isNotEmpty()) TvIdle.waitQuiet()
                // 0.9.79: the phone too, briefly (at most 6 s) — Home's refresh doesn't land in the middle of a scroll.
                else if (!BuildConfig.IS_TV && shelves.value.isNotEmpty()) TvIdle.waitQuiet(1_200, 6_000)
                HomeBundle.fetch(urls)
            } else emptyMap() }
            // TV 0.9.15: catalogs the Worker didn't return are fetched directly, at most 3 at a time.
            val gate = kotlinx.coroutines.sync.Semaphore(if (BuildConfig.IS_TV) 3 else 64)
            val result = if (current.value?.kids == true) kidsShelves() else coroutineScope {
                catalogOnly.flatMap { a ->
                    a.manifest.catalogs
                        .filter { c -> c.extra.orEmpty().none { it.isRequired } }
                        .map { c ->
                            async {
                                runCatching {
                                    // A catalog can declare its card shape (Posters+ trending rows: landscape): its cards
                                    // take it when they don't say themselves.
                                    val cu = "${a.baseUrl}/catalog/${c.type}/${enc(c.id)}.json"
                                    val bundle: Map<String, String> = if (bundleSkip(cu)) emptyMap() else bundleJob.await()
                                    val items = (bundle[cu]?.let { t -> runCatching { Api.json.decodeFromString<CatalogResponse>(t) }.getOrNull() }
                                        ?: gate.withPermit { Api.fetch<CatalogResponse>(cu) }).metas
                                        .map { m -> if (m.posterShape == null && c.posterShape != null) m.copy(posterShape = c.posterShape) else m }
                                    Shelf("${c.name ?: c.id} ${typeLabel(c.type)}", items, src = "${a.baseUrl}/catalog/${c.type}/${enc(c.id)}.json")
                                }.getOrNull()
                            }
                        }
                // Every addon's rows (it stopped at the first 14 catalogs, so an addon added later — Posters+ — never
                // showed); 48 at most.
                }.take(48).awaitAll()
            }
            // The developer's published rows (Home Layout › Publish): fetched for every account, shown first and locked.
            val pushed = if (current.value?.kids == true || BuildConfig.IS_TV || Public.dev) emptyList() else pushedRows().map { r ->
                async { runCatching {
                    val items = Api.fetch<CatalogResponse>(r.src).metas.map { m -> if (m.posterShape == null && r.shape != null) m.copy(posterShape = r.shape) else m }
                    Shelf(r.title, items, src = r.src, pinned = true)
                }.getOrNull() }
            }.awaitAll().filterNotNull()
            val pushedSrc = pushed.mapNotNull { it.src }.toSet()
            // "Top on Netflix / Prime Video / JioHotstar" (TMDB, India, English + Hindi): after the first three rows.
            val svc = if (BuildConfig.IS_TV || current.value?.kids == true || texts.value["svcRows"] == "off") emptyList()
                else kotlinx.coroutines.withTimeoutOrNull(5_000) { runCatching { ServiceRows.rows() }.getOrNull() }.orEmpty()
            // 0.8: Home is managed by the developer. When a layout is published, every other account shows only those rows
            // (its own catalogue addons no longer add rows); the developer still sees everything.
            val own = if (pushed.isNotEmpty() && !Public.dev) emptyList() else result.filterNotNull()
            shelves.value = (pushed + own.filter { it.src == null || it.src !in pushedSrc }).let { l -> if (svc.isEmpty()) l else l.take(3) + svc + l.drop(3) }
                .filter { it.items.isNotEmpty() }.map { s -> s.copy(items = s.items.filter { !isErrorCard(it) }.map { posterFromPattern(it) }.distinctBy { it.type + it.id }) }
            loading.value = false
            // Films only in cinemas / not out yet: checked right away (while "Who's watching?" is up), so Home never
            // shows them and never removes them in front of you.
            if (!BuildConfig.IS_TV) launch(Dispatchers.IO) { runCatching { CinemaOnly.load(appContext); CinemaOnly.check(this@AppViewModel, shelves.value.flatMap { it.items } + recommended.value) } }
            if (!BuildConfig.IS_TV) runCatching { warmHome(shelves.value) }
            // Remembered for the next start, so Home shows its cards at once (then refreshes).
            val pid = dataPid
            val snap = shelves.value
            if (pid != null && current.value?.kids != true && snap.isNotEmpty()) launch(Dispatchers.IO) {
                runCatching {
                    val slim = snap.map { s -> s.copy(items = s.items.take(24).map { it.copy(videos = null, cast = null, director = null, writer = null, trailerStreams = null) }) }
                    File(ctx.filesDir, "shelves_$pid.json").writeText(Api.json.encodeToString(slim))
                }
            }
        }
    }

    // Kids profile: only animation & family catalogs from Cinemeta.
    private suspend fun kidsShelves(): List<Shelf?> = coroutineScope {
        listOf(
            Triple("movie", "Animation", "Animated Movies"),
            Triple("series", "Animation", "Cartoons & Animated Series"),
            Triple("movie", "Family", "Family Movies"),
        ).map { (type, genre, title) ->
            async {
                runCatching {
                    Shelf(title, Api.fetch<CatalogResponse>("$CINEMETA/catalog/$type/top/genre=$genre.json").metas)
                }.getOrNull()
            }
        }.awaitAll()
    }

    fun addAddon(input: String) {
        viewModelScope.launch {
            addError.value = null
            val base = Api.normalize(input)
            if (addons.value.any { it.baseUrl == base }) { addError.value = "Already installed"; return@launch }
            try {
                val m = Api.fetch<Manifest>("$base/manifest.json")
                setAddons(addons.value + Addon(base, m))
                refresh()
            } catch (e: Exception) {
                addError.value = "Couldn't load addon: ${e.message}"
            }
        }
    }

    /**
     * Installs an addon from code (Connect PenguPlay, built-in servers). [family]: addons whose name / link contains it are
     * replaced by this one. Null = done, else why not.
     */
    suspend fun installAddon(input: String, family: String? = null): String? {
        val base = Api.normalize(input)
        if (addons.value.any { it.baseUrl == base }) return null
        val m = runCatching { Api.fetch<Manifest>("$base/manifest.json") }.getOrElse { return "Couldn't load it (${it.javaClass.simpleName})" }
        val keep = if (family == null) addons.value else addons.value.filterNot { a -> family in (a.manifest.name + " " + a.baseUrl + " " + a.manifest.id).lowercase() }
        setAddons(keep + Addon(base, m))
        refresh()
        return null
    }

    fun removeAddon(a: Addon) {
        setAddons(addons.value - a)
        refresh()
    }

    private val searchCache = object : LinkedHashMap<String, List<Meta>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Meta>>?) = size > 20
    }

    /** Search every addon catalog; results show as soon as the first addon answers (not after the slowest one). */
    fun search(q: String) {
        searchJob?.cancel()
        if (q.length < 2) { searchResults.value = emptyList(); return }
        val ck = q.trim().lowercase()
        synchronized(searchCache) { searchCache[ck] }?.let { searchResults.value = it; return }
        searchJob = viewModelScope.launch {
            delay(250)
            val jobs = active.flatMap { a ->
                a.manifest.catalogs.filter { c -> c.extra.orEmpty().any { it.name == "search" } }.map { c -> a to c }
            }
            val parts = arrayOfNulls<List<Meta>>(jobs.size)
            val lock = Any()
            fun publish() {
                val all = synchronized(lock) { parts.filterNotNull().flatten() }
                searchResults.value = all.distinctBy { it.type + it.id }
                if (!BuildConfig.IS_TV) runCatching { Posters.warm(appContext, searchResults.value.take(40).map { it.poster }) }
            }
            coroutineScope {
                jobs.forEachIndexed { i, (a, c) ->
                    launch {
                        val r = runCatching {
                            Api.fetch<CatalogResponse>("${a.baseUrl}/catalog/${c.type}/${enc(c.id)}/search=${Uri.encode(q)}.json").metas
                        }.getOrDefault(emptyList())
                        synchronized(lock) { parts[i] = r }
                        publish()
                    }
                }
            }
            publish()
            synchronized(searchCache) { searchCache[ck] = searchResults.value }
        }
    }

    suspend fun fullMeta(m: Meta): Meta? {
        if (BuildConfig.IS_TV) return fullMetaTv(m)
        // 0.9.71 (from Xstream 0.8.9/0.8.12): one request per title at a time, kept 10 min; shows count only with episodes,
        // and their episode list is saved on the phone (shown at once next time, also offline).
        val k = m.type + ":" + m.id
        metaDone[k]?.let { (at, v) -> if (System.currentTimeMillis() - at < 600_000) return v }
        val d = metaRunning.getOrPut(k) { viewModelScope.async(Dispatchers.IO) { runCatching { fullMetaNow(m) }.getOrNull() } }
        val r = try { d.await() } finally { if (d.isCompleted) metaRunning.remove(k, d) }
        val full = r != null && (m.type != "series" || !r.videos.isNullOrEmpty())
        if (full) { metaDone[k] = System.currentTimeMillis() to r!!; if (m.type == "series") saveShow(r) }
        else if (m.type == "series") savedShow(m.id)?.let { return it }
        return r
    }
    private val metaRunning = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Meta?>>()
    private val metaDone = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Meta>>()
    private fun showPrefs() = appContext.getSharedPreferences("shows", android.content.Context.MODE_PRIVATE)
    private fun saveShow(m: Meta) { runCatching { showPrefs().edit().putString(m.id.substringBefore(":"), Api.json.encodeToString(Meta.serializer(), m)).apply() } }
    fun savedShow(id: String): Meta? = if (BuildConfig.IS_TV) null else
        runCatching { showPrefs().getString(id.substringBefore(":"), null)?.let { Api.json.decodeFromString(Meta.serializer(), it) } }.getOrNull()

    /** Phone: Cinemeta first for IMDb titles, then your addons all at once (first in your order within 4 s, shows 12 s). */
    private suspend fun fullMetaNow(m0: Meta): Meta? {
        val m = if (m0.id.startsWith("tmdb:")) resolveForOpen(m0) ?: return null else m0
        val series = m.type == "series"
        fun good(x: Meta?) = x != null && !x.isAddonError() && x.name.isNotBlank() && (!series || !x.videos.isNullOrEmpty())
        if (m.id.startsWith("tt")) kotlinx.coroutines.withTimeoutOrNull(if (series) 12_000 else 6_000) { runCatching { Api.fetch<MetaResponse>("$CINEMETA/meta/${m.type}/${enc(m.id)}.json").meta }.getOrNull() }
            ?.takeIf { good(it) }?.let { return it }
        val list = active.filter { it.supports("meta", m.type, m.id) }
        if (list.isEmpty()) return null
        val got = coroutineScope {
            val jobs = list.map { a -> async { runCatching { Api.fetch<MetaResponse>("${a.baseUrl}/meta/${m.type}/${enc(m.id)}.json").meta }.getOrNull()?.takeIf { good(it) } } }
            val end = System.currentTimeMillis() + if (series) 12_000 else 4_000
            var res: Meta? = null
            for (j in jobs) {
                val left = end - System.currentTimeMillis()
                val r = if (left > 0) kotlinx.coroutines.withTimeoutOrNull(left) { j.await() } else if (j.isCompleted) j.await() else null
                if (r != null) { res = r; break }
            }
            jobs.forEach { it.cancel() }
            res
        }
        // Nothing complete: the old way (first addon that answers at all), so a title never loses its details.
        return got ?: fullMetaTv(m)
    }

    /** TV (unchanged): the first addon in your order that answers. */
    private suspend fun fullMetaTv(m: Meta): Meta? {
        for (a in active.filter { it.supports("meta", m.type, m.id) }) {
            val r = runCatching { Api.fetch<MetaResponse>("${a.baseUrl}/meta/${m.type}/${enc(m.id)}.json").meta }.getOrNull()
            if (r != null) return r
        }
        return null
    }

    suspend fun streams(type: String, id: String): List<StreamItem> = coroutineScope {
        active.filter { it.supports("stream", type, id) }.map { a ->
            async {
                runCatching {
                    Api.fetch<StreamResponse>("${a.baseUrl}/stream/$type/${enc(id)}.json").streams
                        .filter { !it.url.isNullOrBlank() }.map { StreamItem(a.label(), it).also { si -> StreamHeaders.remember(si) } }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    /** Streams arriving addon by addon: emits (list so far in addon order, finished?) so the page fills in fast. */
    fun streamsFlow(type: String, id: String, only: Set<String>? = null, except: Set<String>? = null): Flow<Pair<List<StreamItem>, Boolean>> {
        val base = if (pluginMode()) PluginSources.modeStreams(this, type, id, only, except) else addonStreamsFlow(type, id, only, except)
        // Live server (64-bit app): its own mode, or added to Turbo / Classic (on by default). It counts as a quick
        // server, so Quick Play and the title page's instant start get its links too.
        val live = LiveServer.enabled(appContext) && (except == null || LiveServer.NAME !in except)
        return when {
            // Each way of watching asks only its own sources: Live = the Live server alone; Turbo (plugins) and
            // Classic (addons) never get Live server links mixed in.
            liveMode() -> if (live) liveStreamsFlow(type, id) else kotlinx.coroutines.flow.flowOf(emptyList<StreamItem>() to true)
            else -> base
        }
    }

    /** The Live server's links alone, as one answer. */
    private fun liveStreamsFlow(type: String, id: String): Flow<Pair<List<StreamItem>, Boolean>> = kotlinx.coroutines.flow.flow {
        val l = runCatching { LiveServer.streams(appContext, type, id) }.getOrDefault(emptyList())
        emit(l.filterNot { TitleMatch.wrong(it, type, id) } to true)
    }

    /** [base] with the Live server's links added as soon as they arrive; done when both are done. */
    private fun withLive(base: Flow<Pair<List<StreamItem>, Boolean>>, type: String, id: String): Flow<Pair<List<StreamItem>, Boolean>> = channelFlow {
        val lock = Any()
        var others: List<StreamItem> = emptyList(); var othersDone = false
        var mine: List<StreamItem> = emptyList(); var mineDone = false
        suspend fun push() { val snap = synchronized(lock) { (mine + others).distinctBy { it.stream.url } to (othersDone && mineDone) }; send(snap) }
        launch {
            val l = runCatching { LiveServer.streams(appContext, type, id) }.getOrDefault(emptyList()).filterNot { TitleMatch.wrong(it, type, id) }
            synchronized(lock) { mine = l; mineDone = true }
            push()
        }
        base.collect { (l, done) -> synchronized(lock) { others = l; othersDone = done }; push() }
    }

    // 0.9.1: with Feature.cloudFind on (Xstream) the cloud link finder asks every addon first; off = StreamX's own flow below, unchanged.
    internal fun addonStreamsFlow(type: String, id: String, only: Set<String>?, except: Set<String>?): Flow<Pair<List<StreamItem>, Boolean>> =
        if (Feature.cloudFind && !BuildConfig.IS_TV) cloudStreamsFlow(type, id, only, except) else directStreamsFlow(type, id, only, except)

    private fun directStreamsFlow(type: String, id: String, only: Set<String>?, except: Set<String>?): Flow<Pair<List<StreamItem>, Boolean>> = channelFlow {
        val addons = active.filter { it.supports("stream", type, id) && (only == null || it.label() in only) && (except == null || it.label() !in except) }
        // Plugins (Settings › Content & Discovery › Plugins) are asked alongside the addons.
        val plugins = if (BuildConfig.IS_TV) emptyList() else Plugins.enabled().filter { type in it.types && (only == null || it.name in only) && (except == null || it.name !in except) }
        if (addons.isEmpty() && plugins.isEmpty()) { send(emptyList<StreamItem>() to true); return@channelFlow }
        val results = arrayOfNulls<List<StreamItem>>(addons.size + plugins.size)
        val total = addons.size + plugins.size
        // A film's year (for dropping links to other titles with the same name), looked up alongside.
        val yearJob = async { TitleMatch.ensureYear(type, id) }
        suspend fun right(l: List<StreamItem>): List<StreamItem> {
            kotlinx.coroutines.withTimeoutOrNull(1500) { yearJob.await() }
            return l.filterNot { TitleMatch.wrong(it, type, id) || it.isInstantDownload() }
        }
        val lock = Any()
        var done = 0
        addons.forEachIndexed { i, a ->
            launch {
                val t0 = android.os.SystemClock.elapsedRealtime()
                // One shared request per addon + title (title page, player, upgrade and Sources all ask the same thing —
                // asking a server several times a second is what gets us "too many requests"); an empty answer or a
                // refusal from the quick-play addon is asked once more after a short pause.
                val res = runCatching {
                    AddonCalls.streams("${a.baseUrl}/stream/$type/${enc(id)}.json", retry = a.label() in qsAddons())
                        .filter { !it.url.isNullOrBlank() }.map { StreamItem(a.label(), it).also { si -> StreamHeaders.remember(si) } }
                }
                if (res.exceptionOrNull() !is kotlinx.coroutines.CancellationException) PlayTiming.mark("${a.label()}: " + res.fold({ "${it.size} links" }, { "no answer (${it.message?.take(40)})" }) +
                    " in ${"%.1f".format((android.os.SystemClock.elapsedRealtime() - t0) / 1000f)} s", once = "addon:${a.label()}")
                AddonSpeed.record(a.label(), android.os.SystemClock.elapsedRealtime() - t0, res.isSuccess)
                val r = right(res.getOrDefault(emptyList()))
                val snapshot = synchronized(lock) {
                    results[i] = r; done++
                    results.filterNotNull().flatten() to (done == total)
                }
                send(snapshot)
            }
        }
        plugins.forEachIndexed { j, pl ->
            launch {
                val r = right(Plugins.streams(pl, type, id))
                val snapshot = synchronized(lock) {
                    results[addons.size + j] = r; done++
                    results.filterNotNull().flatten() to (done == total)
                }
                send(snapshot)
            }
        }
    }

    /** 0.9.1 (Xstream 0.8.18–0.8.20): the cloud link finder first (1.5 s head start); an addon the cloud answered isn't asked again. */
    private fun cloudStreamsFlow(type: String, id: String, only: Set<String>?, except: Set<String>?): Flow<Pair<List<StreamItem>, Boolean>> = channelFlow {
        val addons = active.filter { it.supports("stream", type, id) && (only == null || it.label() in only) && (except == null || it.label() !in except) }
        // Plugins (Settings › Content & Discovery › Plugins) are asked alongside the addons.
        val plugins = if (BuildConfig.IS_TV) emptyList() else Plugins.enabled().filter { type in it.types && (only == null || it.name in only) && (except == null || it.name !in except) }
        if (addons.isEmpty() && plugins.isEmpty()) { send(emptyList<StreamItem>() to true); return@channelFlow }
        val results = arrayOfNulls<List<StreamItem>>(addons.size + plugins.size)
        val total = addons.size + plugins.size
        // A film's year (for dropping links to other titles with the same name), looked up alongside.
        val yearJob = async { TitleMatch.ensureYear(type, id) }
        suspend fun right(l: List<StreamItem>): List<StreamItem> {
            kotlinx.coroutines.withTimeoutOrNull(1500) { yearJob.await() }
            return l.filterNot { TitleMatch.wrong(it, type, id) || it.isInstantDownload() }
        }
        val lock = Any()
        var done = 0
        // 0.8.18: the Cloudflare link finder asks all addons at once; an addon's cloud answer is shown until its own
        // direct answer arrives (which then replaces it). Counted as answered on whichever comes first.
        val answered = BooleanArray(addons.size)
        // 0.8.20: an addon whose cloud answer has links is not asked a second time. started = its direct call was
        // launched; skipDirect = it never will be (cloud answered); cloudN = addons answered by the cloud.
        val started = BooleanArray(addons.size)
        val skipDirect = BooleanArray(addons.size)
        var cloudN = 0
        val bases = addons.map { CloudFind.baseOf(it.baseUrl) }
        val cloud: kotlinx.coroutines.Deferred<Map<String, List<StreamInfo>>>? = if (addons.isEmpty() || BuildConfig.IS_TV) null else async {
            runCatching { CloudFind.find(type, id, bases) }.getOrDefault(emptyMap<String, List<StreamInfo>>())
        }
        // 0.8.20: always asked directly: quick-play addons and CNCVerse (links can be tied to the device), and addons
        // whose cloud links were refused earlier this session.
        fun mustAsk(i: Int): Boolean = addons[i].label() in qsAddons() || addons[i].label().contains("CNCVerse", ignoreCase = true) || bases[i] in CloudFind.directOnly

        fun startDirect(i: Int) {
            val a = addons[i]
            if (synchronized(lock) { if (started[i]) true else { started[i] = true; false } }) return
            launch {
                val t0 = android.os.SystemClock.elapsedRealtime()
                // One shared request per addon + title (title page, player, upgrade and Sources all ask the same thing —
                // asking a server several times a second is what gets us "too many requests"); an empty answer or a
                // refusal from the quick-play addon is asked once more after a short pause.
                val res = runCatching {
                    AddonCalls.streams("${a.baseUrl}/stream/$type/${enc(id)}.json", retry = a.label() in qsAddons())
                        .filter { !it.url.isNullOrBlank() }.map { StreamItem(a.label(), it).also { si -> StreamHeaders.remember(si) } }
                }
                if (res.exceptionOrNull() !is kotlinx.coroutines.CancellationException) PlayTiming.mark("${a.label()}: " + res.fold({ "${it.size} links" }, { "no answer (${it.message?.take(40)})" }) +
                    " in ${"%.1f".format((android.os.SystemClock.elapsedRealtime() - t0) / 1000f)} s", once = "addon:${a.label()}")
                AddonSpeed.record(a.label(), android.os.SystemClock.elapsedRealtime() - t0, res.isSuccess)
                val r = right(res.getOrDefault(emptyList()))
                // 0.8.20: links the addon itself gave are no longer "cloud links" (a refusal of them is a real refusal).
                r.forEach { si -> si.stream.url?.let { u -> CloudFind.cloudUrls.remove(u) } }
                val snapshot = synchronized(lock) {
                    // A cloud answer already counted: the direct one replaces it (kept if the direct one is empty).
                    if (answered[i]) { if (r.isNotEmpty()) results[i] = r } else { results[i] = r; answered[i] = true; done++ }
                    results.filterNotNull().flatten().distinctBy { it.stream.url } to (done == total)
                }
                send(snapshot)
            }
        }

        /** Cloud answers go to addons that haven't answered yet. early = the first look (within 1500 ms): addons that can skip their direct call do. */
        suspend fun applyCloud(m: Map<String, List<StreamInfo>>, early: Boolean) {
            if (m.isEmpty()) return
            val pending = addons.indices.filter { i -> synchronized(lock) { !answered[i] } }
            for (i in pending) {
                val a = addons[i]
                val l = m[bases[i]].orEmpty()
                if (l.isEmpty()) continue
                val r = right(l.map { StreamItem(a.label(), it).also { si -> StreamHeaders.remember(si) } })
                if (r.isEmpty()) continue
                // Marked before it is shown, so a refusal of one of these links is always recognised as a cloud refusal.
                CloudFind.markCloud(bases[i], r.map { it.stream.url })
                val snapshot = synchronized(lock) {
                    if (answered[i]) null else {
                        results[i] = r; answered[i] = true; done++; cloudN++
                        if (early && !mustAsk(i)) skipDirect[i] = true
                        results.filterNotNull().flatten().distinctBy { it.stream.url } to (done == total)
                    }
                }
                if (snapshot != null) send(snapshot)
            }
        }

        if (cloud == null) {
            // TV, or nothing to ask the cloud about: exactly as before.
            addons.indices.forEach { startDirect(it) }
        } else {
            // The ones that are always asked directly don't wait for the cloud.
            addons.indices.filter { mustAsk(it) }.forEach { startDirect(it) }
            launch {
                val early = kotlinx.coroutines.withTimeoutOrNull(1500) { cloud.await() }
                if (early != null) applyCloud(early, true)
                // Cloud empty / failed / slow: everything that hasn't answered is asked directly now.
                synchronized(lock) { addons.indices.filter { !skipDirect[it] && !started[it] } }.forEach { startDirect(it) }
                val line = synchronized(lock) {
                    "addons: $cloudN from the cloud, ${started.count { it }} asked directly (${skipDirect.count { it }} skipped — cloud already answered)"
                }
                ActLog.add("NET", line)
                // Slower than 1500 ms: its answer still fills addons that are pending (the old behaviour).
                if (early == null) applyCloud(cloud.await(), false)
            }
        }
        plugins.forEachIndexed { j, pl ->
            launch {
                val r = right(Plugins.streams(pl, type, id))
                val snapshot = synchronized(lock) {
                    results[addons.size + j] = r; done++
                    results.filterNotNull().flatten() to (done == total)
                }
                send(snapshot)
            }
        }
    }

    /** 0.8.20: one addon asked directly (a cloud link of it was refused). Empty when the addon is gone or has nothing. */
    suspend fun askAddonDirect(type: String, id: String, base: String): List<StreamItem> {
        val a = active.firstOrNull { CloudFind.baseOf(it.baseUrl) == base } ?: return emptyList()
        val l = try {
            AddonCalls.streams("${a.baseUrl}/stream/$type/${enc(id)}.json", retry = a.label() in qsAddons())
                .filter { !it.url.isNullOrBlank() }.map { StreamItem(a.label(), it).also { si -> StreamHeaders.remember(si) } }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList<StreamItem>()
        }
        kotlinx.coroutines.withTimeoutOrNull(1500) { TitleMatch.ensureYear(type, id) }
        val r = l.filterNot { TitleMatch.wrong(it, type, id) || it.isInstantDownload() }
        r.forEach { si -> si.stream.url?.let { u -> CloudFind.cloudUrls.remove(u) } }
        return r
    }

    // ───────────── Last working link per title (Playback > Reuse last link) ─────────────
    @Serializable data class LastLink(val url: String, val source: String? = null, val addon: String? = null, val at: Long = 0)

    fun lastLink(key: String): LastLink? = lib.getString("link_$key", null)?.let { runCatching { Api.json.decodeFromString<LastLink>(it) }.getOrNull() }
        ?.takeIf { !isLocalUrl(it.url) }
        ?.takeIf { System.currentTimeMillis() - it.at < 6L * 3600 * 1000 } // provider links expire; reuse for 6 hours (a refused link is refreshed automatically)
    /** The app's own local relay (127.0.0.1:port): its port changes every start, so it is never saved for Continue Watching. */
    private fun isLocalUrl(u: String) = Regex("^https?://(127\\.0\\.0\\.1|localhost)(:|/)", RegexOption.IGNORE_CASE).containsMatchIn(u)

    fun rememberLink(key: String, url: String, source: String?, addon: String?) {
        if (key.isBlank() || url.isBlank() || isLocalUrl(url)) return
        lib.edit().putString("link_$key", Api.json.encodeToString(LastLink(url, source, addon, System.currentTimeMillis()))).apply()
    }

    fun forgetLink(key: String) { lib.edit().remove("link_$key").apply() }

    // ───────────── Poster wall behind "Who's watching?" ─────────────
    val wallPosters = MutableStateFlow<List<String>>(emptyList())
    fun loadWallPosters() {
        if (wallPosters.value.isNotEmpty()) return
        viewModelScope.launch {
            val list = runCatching {
                coroutineScope {
                    listOf("movie", "series").map { t -> async { Api.fetch<CatalogResponse>("$CINEMETA/catalog/$t/top.json").metas } }.awaitAll()
                }.let { (a, b) -> a.zip(b).flatMap { listOf(it.first, it.second) } }.mapNotNull { it.poster }
            }.getOrDefault(emptyList())
            wallPosters.value = list.take(30)
        }
    }

    // ───────────── Player choices remembered per profile (aspect, audio language, subtitle language) ─────────────
    /** The title playing now (IMDb id): subtitles and audio language are remembered for it ("Remember per title"). */
    @Volatile var titleScope: String? = null
    private val perTitlePrefs = setOf("subs", "audio")
    fun playerPref(k: String): String {
        val t = titleScope
        if (t != null && k in perTitlePrefs) lib.getString("pt_${t}_$k", null)?.let { return it }
        return lib.getString("pp_$k", "") ?: ""
    }
    /** Only this title's own value (no fallback to the last title's). */
    fun titlePref(k: String): String? = titleScope?.let { lib.getString("pt_${it}_$k", null) }?.takeIf { it.isNotBlank() }
    fun setPlayerPref(k: String, value: String) {
        val e = lib.edit().putString("pp_$k", value)
        titleScope?.takeIf { k in perTitlePrefs }?.let { e.putString("pt_${it}_$k", value) }
        e.apply()
    }

    // ───────────── Badge pack (Layout > Streams) ─────────────
    val badgePack = MutableStateFlow<List<PackBadge>>(emptyList())
    private var packUrl: String? = null

    /** Why the last badge link loaded nothing (shown under the field). */
    val badgePackError = MutableStateFlow<String?>(null)

    /**
     * Reads badge packs in any of the usual shapes: {"pattern": "imageUrl"}, [{"pattern","image","name"}],
     * {"badges": [...]}, groups of badges, and Fusion / XPerience style packs (regex / match / keywords + image / url /
     * icon / logo, nested anywhere). Plain words that aren't valid regexes are matched as text.
     */
    fun loadBadgePack(url: String, force: Boolean = false) {
        val u = url.trim()
        if (u == packUrl && !force) return
        packUrl = u
        badgePackError.value = null
        if (u.isBlank()) { badgePack.value = emptyList(); return }
        viewModelScope.launch(Dispatchers.IO) {
            val body = runCatching { Api.get(u) }.onFailure { badgePackError.value = "Couldn't download it: ${it.message?.take(80)}" }.getOrNull() ?: return@launch
            val list = runCatching { parseBadgePack(Api.json.parseToJsonElement(body)) }
                .onFailure { badgePackError.value = "Not a badge list (JSON couldn't be read)." }.getOrDefault(emptyList())
            if (list.isEmpty() && badgePackError.value == null) badgePackError.value = "The file has no pattern + image pairs we recognise."
            ActLog.add("APP", "badge pack: ${list.size} badge(s) from $u")
            badgePack.value = list
        }
    }

    private fun parseBadgePack(root: JsonElement): List<PackBadge> {
        val out = ArrayList<PackBadge>()
        val patKeys = listOf("pattern", "regex", "regexp", "match", "matcher", "expression", "filter", "keywords", "keyword", "terms", "contains", "test")
        val imgKeys = listOf("image", "imageUrl", "image_url", "url", "icon", "iconUrl", "logo", "logoUrl", "src", "img", "badge", "badgeUrl", "png", "svg")
        fun rxOf(p: String): Regex? = runCatching { Regex(p, RegexOption.IGNORE_CASE) }.getOrNull() ?: runCatching { Regex(Regex.escape(p), RegexOption.IGNORE_CASE) }.getOrNull()
        fun str(e: JsonElement?): String? = when (e) {
            is JsonPrimitive -> e.contentOrNull?.takeIf { it.isNotBlank() }
            is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }.takeIf { it.isNotEmpty() }
                ?.joinToString("|") { w -> if (w.any { c -> c in "\\^$.[]()|?*+{}" }) w else Regex.escape(w) }
            is JsonObject -> null
            else -> null
        }
        fun isImg(s: String) = s.startsWith("http") || s.startsWith("data:image")
        fun walk(e: JsonElement, depth: Int) {
            if (depth > 6) return
            when (e) {
                is JsonArray -> e.forEach { walk(it, depth + 1) }
                is JsonObject -> {
                    val pat = patKeys.firstNotNullOfOrNull { k -> str(e[k]) }
                    val img = imgKeys.firstNotNullOfOrNull { k -> (e[k] as? JsonPrimitive)?.contentOrNull?.takeIf(::isImg) }
                    if (pat != null && img != null) {
                        rxOf(pat)?.let { out += PackBadge(it, img, (e["name"] ?: e["label"] ?: e["title"] ?: e["text"])?.let { n -> (n as? JsonPrimitive)?.contentOrNull } ?: "") }
                        return
                    }
                    // {"pattern": "imageUrl"} maps
                    var mapped = 0
                    e.forEach { (k, v) ->
                        val vs = (v as? JsonPrimitive)?.contentOrNull
                        if (vs != null && isImg(vs) && k !in imgKeys) { rxOf(k)?.let { out += PackBadge(it, vs, ""); mapped++ } }
                    }
                    e.values.forEach { v -> if (v is JsonObject || v is JsonArray) walk(v, depth + 1) }
                }
                else -> {}
            }
        }
        walk(root, 0)
        return out.distinctBy { it.re.pattern + it.image }
    }

    suspend fun subtitles(type: String, id: String): List<Subtitle> = coroutineScope {
        active.filter { it.supports("subtitles", type, id) }.map { a ->
            async {
                runCatching { Api.fetch<SubsResponse>("${a.baseUrl}/subtitles/$type/${enc(id)}.json").subtitles }
                    .getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    /**
     * More subtitles for the player's "Find subtitles online": your subtitle addons plus OpenSubtitles (through its
     * free Stremio addon, no account needed). Same file listed twice is shown once.
     */
    /** TV 0.9.4: the online subtitle list is kept 20 minutes (it's fetched ahead while the video plays, so Audio &
     *  Subtitles opens with it ready). */
    private val subsListCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Subtitle>>>()
    suspend fun onlineSubtitles(type: String, id: String): List<Subtitle> {
        if (BuildConfig.IS_TV) subsListCache["$type|$id"]?.takeIf { System.currentTimeMillis() - it.first < 20 * 60_000L }?.let { return it.second }
        return onlineSubtitlesFresh(type, id).also { if (BuildConfig.IS_TV && it.isNotEmpty()) { if (subsListCache.size > 30) subsListCache.clear(); subsListCache["$type|$id"] = System.currentTimeMillis() to it } }
    }
    private suspend fun onlineSubtitlesFresh(type: String, id: String): List<Subtitle> = coroutineScope {
        val mine = async { runCatching { subtitles(type, id) }.getOrDefault(emptyList()) }
        val os = async {
            runCatching { Api.fetch<SubsResponse>("https://opensubtitles-v3.strem.io/subtitles/$type/${enc(id)}.json").subtitles }
                .getOrDefault(emptyList())
        }
        (mine.await() + os.await()).filter { it.url.startsWith("http") }.distinctBy { it.url }
    }

    // ───────────── App logo (Developer's Corner) ─────────────
    fun logoFile() = File(ctx.filesDir, "app_logo.img")

    fun setLogo(uri: Uri) {
        runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use { i -> logoFile().outputStream().use { o -> i.copyTo(o) } }
        }
        imgVersion.value = imgVersion.value + 1
        Cloud.markApp("logo")
    }

    fun clearLogo() { logoFile().delete(); imgVersion.value = imgVersion.value + 1; Cloud.markApp("logo") }

    // ───────────── Developer's Corner password (salted hash, never stored as plain text) ─────────────
    private fun hashPw(salt: String, pw: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var d = (salt + pw).toByteArray()
        repeat(5000) { d = md.digest(d) }
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun newSalt() = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun ensureDevPassword() {
        if (dev.contains("hash")) return
        val salt = newSalt()
        dev.edit().putString("salt", salt).putString("hash", hashPw(salt, "Anish@2007")).apply()
    }

    fun checkDevPassword(pw: String): Boolean {
        ensureDevPassword()
        return hashPw(dev.getString("salt", "") ?: "", pw) == dev.getString("hash", "")
    }

    fun changeDevPassword(old: String, new: String): Boolean {
        if (!checkDevPassword(old)) return false
        val salt = newSalt()
        dev.edit().putString("salt", salt).putString("hash", hashPw(salt, new)).apply()
        return true
    }

    // ───────────── Live TV (M3U playlist) ─────────────
    val channels = MutableStateFlow<List<Channel>>(emptyList())
    val tvUrl = MutableStateFlow(glob.getString("m3u", "") ?: "")
    val tvLoading = MutableStateFlow(false)
    val tvError = MutableStateFlow<String?>(null)

    fun loadChannels(url: String) {
        viewModelScope.launch {
            tvLoading.value = true
            tvError.value = null
            try {
                val list = parseM3u(Api.get(url.trim()))
                if (list.isEmpty()) {
                    tvError.value = "No channels found in that playlist"
                } else {
                    channels.value = list
                    tvUrl.value = url.trim()
                    glob.edit().putString("m3u", url.trim()).apply()
                }
            } catch (e: Exception) {
                tvError.value = "Couldn't load playlist: ${e.message}"
            }
            tvLoading.value = false
        }
    }

    fun clearChannels() {
        channels.value = emptyList()
        tvUrl.value = ""
        tvError.value = null
        glob.edit().remove("m3u").apply()
    }

    init { if (tvUrl.value.isNotBlank()) loadChannels(tvUrl.value) }

    // ───────────── Voice assistant brain (works offline, no API key) ─────────────
    suspend fun searchNow(q: String): List<Meta> = coroutineScope {
        active.flatMap { a ->
            a.manifest.catalogs.filter { c -> c.extra.orEmpty().any { it.name == "search" } }.map { c ->
                async {
                    // A slow addon doesn't hold the results back (4 s each at most).
                    runCatching {
                        kotlinx.coroutines.withTimeoutOrNull(4_000) {
                            Api.fetch<CatalogResponse>("${a.baseUrl}/catalog/${c.type}/${enc(c.id)}/search=${Uri.encode(q)}.json").metas
                        }
                    }.getOrNull().orEmpty()
                }
            }
        }.awaitAll().flatten().distinctBy { it.type + it.id }
    }

    /**
     * TV: the same addon search, but light enough for a 1 GB Fire TV Stick: at most 2 search catalogs per addon, 3 requests
     * at a time, 30 results per catalog. (Asking every catalog at once ran the stick out of memory.)
     */
    suspend fun searchNowLight(q: String): List<Meta> = coroutineScope {
        val gate = kotlinx.coroutines.sync.Semaphore(3)
        active.flatMap { a ->
            a.manifest.catalogs.filter { c -> c.extra.orEmpty().any { it.name == "search" } && !c.id.contains("dummy", true) }.take(2).map { c ->
                async {
                    gate.acquire()
                    try {
                        runCatching {
                            kotlinx.coroutines.withTimeoutOrNull(5_000) {
                                Api.fetch<CatalogResponse>("${a.baseUrl}/catalog/${c.type}/${enc(c.id)}/search=${Uri.encode(q)}.json").metas.take(30)
                            }
                        }.getOrNull().orEmpty()
                    } finally { gate.release() }
                }
            }
        }.awaitAll().flatten().distinctBy { it.type + it.id }
    }

    suspend fun genreTitles(genres: List<String>, types: List<String>): List<Meta> = coroutineScope {
        val lists = genres.take(3).flatMap { g ->
            types.map { type ->
                async {
                    runCatching { Api.fetch<CatalogResponse>("$CINEMETA/catalog/$type/top/genre=${Uri.encode(g)}.json").metas }
                        .getOrDefault(emptyList())
                }
            }
        }.awaitAll()
        val out = ArrayList<Meta>()
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { l -> l.getOrNull(i)?.let { out.add(it) } }
        out.distinctBy { it.id }.take(40)
    }

    suspend fun interpret(heard: String): VoiceAnswer {
        val text = heard.lowercase().trim()
        val types = when {
            Regex("\\b(movie|movies|film|films)\\b").containsMatchIn(text) -> listOf("movie")
            Regex("\\b(series|tv shows?|shows|seasons?|episodes?)\\b").containsMatchIn(text) -> listOf("series")
            else -> listOf("movie", "series")
        }
        val command = Regex("^(please )?(play|show me|show|search for|search|find|look for|i want to watch|i wanna watch|put on|watch|open|start)\\s+(.*)$").find(text)
        val genre = Voice.genres.entries.firstOrNull { (k, _) -> Regex("\\b$k\\b").containsMatchIn(text) }?.value
        val mood = Voice.moods.firstOrNull { m -> m.words.any { Regex("\\b$it\\b").containsMatchIn(text) } }
        return when {
            genre != null && (command != null || text.split(" ").size <= 4) -> {
                val items = genreTitles(listOf(genre), types)
                VoiceAnswer("Here are some ${genre.lowercase()} picks.", "$genre ${Voice.typeWord(types)}", items)
            }
            mood != null && (command == null || Regex("\\b(something|anything|feel|feeling|mood)\\b").containsMatchIn(text)) -> {
                val items = genreTitles(mood.genres, types)
                VoiceAnswer(mood.reply, mood.title, items)
            }
            else -> {
                var q = (command?.groupValues?.get(3) ?: text)
                    .replace(Regex("\\b(the )?(movies|movie|films|film|series|shows|show|please|for me|some)\\b"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                if (q.isBlank()) q = text
                val items = searchNow(q).let { r -> if (types.size == 1) r.filter { it.type == types[0] }.ifEmpty { r } else r }
                if (items.isEmpty()) VoiceAnswer("Sorry, I couldn't find anything for $q. Try saying it another way.", "No results for \"$q\"", emptyList())
                else VoiceAnswer("Here's what I found for $q.", "Results for \"$q\"", items)
            }
        }
    }

    // ───────────── Card trailers ─────────────
    private val trailerCache = HashMap<String, String?>()

    /** YouTube id of the title's trailer (from Cinemeta), or null. */
    suspend fun trailerId(m: Meta): String? {
        if (trailerCache.containsKey(m.id)) return trailerCache[m.id]
        val full = if (m.trailerStreams != null || m.trailers != null) m else fullMeta(m)
        val id = full?.trailerStreams?.firstOrNull { !it.ytId.isNullOrBlank() }?.ytId
            ?: full?.trailers?.firstOrNull { it.type.equals("Trailer", true) && !it.source.isNullOrBlank() }?.source
            ?: full?.trailers?.firstOrNull { !it.source.isNullOrBlank() }?.source
            // The catalog addon may not include trailers: ask Cinemeta directly, then TMDB (when a key is set).
            ?: runCatching { Api.fetch<MetaResponse>("$CINEMETA/meta/${m.type}/${enc(m.id.substringBefore(":"))}.json").meta }.getOrNull()
                ?.let { c -> c.trailerStreams?.firstOrNull { !it.ytId.isNullOrBlank() }?.ytId ?: c.trailers?.firstOrNull { !it.source.isNullOrBlank() }?.source }
            ?: runCatching { tmdbInfo(m.id.substringBefore(":"), m.type)?.videos?.firstOrNull { it.type == "Trailer" }?.key }.getOrNull()
        trailerCache[m.id] = id
        return id
    }

    // ───────────── Player helpers ─────────────
    /** Intro / recap / outro times for a movie ("tt123") or episode ("tt123:1:2") from introdb.app. */
    suspend fun segments(key: String): Segments? {
        val parts = key.split(":")
        val imdb = parts[0]
        if (!imdb.startsWith("tt")) return null
        val url = if (parts.size >= 3) "https://api.introdb.app/segments?imdb_id=$imdb&season=${parts[1]}&episode=${parts[2]}"
        else "https://api.introdb.app/segments?imdb_id=$imdb"
        // TV 0.9.14: at most 4 s (it once hung 20 s and the episode had no skip times); SkipDB is the backup.
        val r = kotlinx.coroutines.withTimeoutOrNull(4_000) { runCatching { Api.fetch<Segments>(url) }.getOrNull() } ?: return null
        // Only trust data for exactly this episode / movie.
        if (parts.size >= 3) {
            if (r.season != null && r.season != parts[1].toIntOrNull()) return null
            if (r.episode != null && r.episode != parts[2].toIntOrNull()) return null
        } else if (r.is_movie == false) return null
        // Drop doubtful or impossible ranges (too short, too long, low confidence).
        fun ok(sg: Seg?) = sg?.takeIf { it.end_ms - it.start_ms in 5_000..600_000 && it.start_ms >= 0 && it.confidence >= 0.5 }
        return Segments(ok(r.intro), ok(r.recap), r.outro?.takeIf { it.start_ms > 0 && it.confidence >= 0.5 }, r.is_movie, r.season, r.episode)
    }

    private val skipDbCache = java.util.concurrent.ConcurrentHashMap<String, Segments>()
    /**
     * TV 0.9.14: SkipDB (skipdb.tv) — skip times for exactly this copy's length: we send our file's duration and it shifts
     * its times to fit (a 25 fps MovieBox copy runs ~4 % faster, so fixed times landed early). Null = nothing there.
     */
    suspend fun skipDb(key: String, durationMs: Long): Segments? {
        val parts = key.split(":")
        val imdb = parts[0]
        if (!imdb.startsWith("tt") || durationMs <= 60_000) return null
        val sec = durationMs / 1000
        val ck = "$key|${sec / 5}"
        skipDbCache[ck]?.let { return it }
        val url = "https://api.skipdb.tv/api/segments?imdb_id=$imdb" + (if (parts.size >= 3) "&season=${parts[1]}&episode=${parts[2]}" else "") + "&duration=$sec"
        val body = kotlinx.coroutines.withTimeoutOrNull(5_000) { runCatching { Api.get(url) }.getOrNull() } ?: return null
        val segs = runCatching { (Api.json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject)["segments"] as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return null
        fun seg(vararg names: String): Seg? = names.firstNotNullOfOrNull { n ->
            runCatching {
                val j = segs[n] as? kotlinx.serialization.json.JsonObject ?: return@runCatching null
                fun num(k: String) = (j[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
                val st = num("start_ms") ?: return@runCatching null; val en = num("end_ms") ?: return@runCatching null
                Seg(st, en, 1.0).takeIf { en > st && st >= 0 && en - st <= 600_000 }
            }.getOrNull()
        }
        val out = Segments(seg("intro"), seg("recap"), seg("outro", "credits"), parts.size < 3,
            parts.getOrNull(1)?.toIntOrNull(), parts.getOrNull(2)?.toIntOrNull())
        if (out.intro == null && out.recap == null && out.outro == null) return null
        skipDbCache[ck] = out
        return out
    }

    private val guideCache = HashMap<String, List<Pair<String, String>>>()

    /**
     * Content advisory (Violence, Profanity...) read from the title's public IMDb parental guide page.
     * Best effort: returns an empty list if the page can't be read or has no ratings.
     */
    suspend fun parentalGuide(imdbId: String): List<Pair<String, String>> {
        if (!imdbId.startsWith("tt")) return emptyList()
        guideCache[imdbId]?.let { return it }
        val html = runCatching {
            Api.getWith(
                "https://www.imdb.com/title/$imdbId/parentalguide",
                mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
                    "Accept-Language" to "en-US,en;q=0.9",
                ),
            )
        }.getOrNull() ?: return emptyList()
        val cats = listOf(
            "Violence" to listOf("Violence & Gore", "Violence &amp; Gore"),
            "Profanity" to listOf("Profanity"),
            "Frightening" to listOf("Frightening & Intense Scenes", "Frightening &amp; Intense Scenes"),
            "Nudity" to listOf("Sex & Nudity", "Sex &amp; Nudity"),
            "Alcohol/Drugs" to listOf("Alcohol, Drugs & Smoking", "Alcohol, Drugs &amp; Smoking"),
        )
        val sev = Regex("\\b(None|Mild|Moderate|Severe)\\b")
        val out = cats.mapNotNull { (label, heads) ->
            val at = heads.map { html.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return@mapNotNull null
            val level = sev.find(html.substring(at, minOf(html.length, at + 4000)))?.value ?: return@mapNotNull null
            if (level == "None") null else label to level
        }
        guideCache[imdbId] = out
        return out
    }

    /** Picks a stream automatically according to Help & Settings > Playback > Auto Stream Selection. */
    /**
     * Auto Stream Selection. [list] should already be filtered by the tag rules.
     * "first" = top source; "regex" = pattern 1, then fallback patterns in order, then the top source.
     */
    fun autoPick(list: List<StreamItem>, mode: String, pattern: String, preferAddon: String? = null): StreamItem? {
        if (list.isEmpty()) return null
        preferAddon?.let { a -> list.firstOrNull { it.stream.name == a }?.let { return it } }
        preferAddon?.let { a -> list.firstOrNull { it.addonName == a }?.let { return it } }
        return when (mode) {
            "first" -> list.first()
            "regex" -> matchChain(list, regexList(pattern))?.first ?: list.first()
            else -> null
        }
    }

    /** Streams with the user's tag rules applied (Content & Discovery > Stream Filters). */
    /** Your stream filters; dead links (Source Test: web pages, "not found", expired signed links) are hidden too. */
    fun filtered(list: List<StreamItem>): Filtered {
        val noDead = if (isOn(texts.value["hideDead"] ?: "yes")) list.filterNot { Ranker.isDead(it) }.ifEmpty { list } else list
        // Tested sources only: groups the Source Test saw failing again and again are hidden; passed or untested ones stay.
        val alive = if (isOn(texts.value["testedOnly"] ?: "yes")) noDead.filterNot { Ranker.keepsFailing(it) }.ifEmpty { noDead } else noDead
        // Source Test arranging on: inside each addon, its most reliable providers first (your stream filters still apply after).
        val ranked = if (isOn(texts.value["srcAutoManage"] ?: "yes") && Ranker.file.entries.isNotEmpty()) {
            val order = addons.value.map { it.label() }
            alive.sortedWith(compareBy<StreamItem> { order.indexOf(it.addonName).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenByDescending { Ranker.score(it) })
        } else alive
        // Your own regex on top: lines that must NOT match are hidden (unless nothing would be left), lines you prefer go first.
        val out = regexList(texts.value["userRegexOut"].orEmpty()).mapNotNull { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
        val pref = regexList(texts.value["userRegexIn"].orEmpty()).mapNotNull { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
        fun txt(s: StreamItem) = listOfNotNull(s.stream.name, s.stream.title, s.stream.description, s.stream.behaviorHints?.filename).joinToString(" ")
        val rx = (if (out.isEmpty()) ranked else ranked.filterNot { s -> out.any { it.containsMatchIn(txt(s)) } }.ifEmpty { ranked })
            .let { l -> if (pref.isEmpty()) l else l.sortedBy { s -> pref.indexOfFirst { it.containsMatchIn(txt(s)) }.let { i -> if (i < 0) Int.MAX_VALUE else i } } }
        return applyTagRules(dropCams(rx), effectiveRules(texts.value["devTagRules"], texts.value["tagRules"]))
    }

    /**
     * A title that already has proper releases (WEB-DL / WEBRip / BluRay / HDRip): cinema recordings (CAM, HDCAM, TS,
     * Telesync, TC, Telecine, DVDScr, PreDVD) are dropped, and DVDRip too when an HD release exists. Nothing is dropped
     * when the cam copies are all there is.
     */
    private fun dropCams(l: List<StreamItem>): List<StreamItem> {
        // The formatter's exact release type (q = WEB-DL, BluRay, CAM…) first, else the names.
        fun t(s: StreamItem) = (listOfNotNull(s.stream.name, s.stream.title, s.stream.description, s.stream.behaviorHints?.filename) + listOfNotNull(s.sx()["q"])).joinToString(" ").lowercase()
        val camRx = Regex("(^|[^a-z])(hd-?cam|cam-?rip|camrip|cam|hd-?ts|telesync|ts-?rip|hd-?tc|telecine|tc-?rip|dvd-?scr|pre-?dvd|hq-?cam)([^a-z]|$)")
        val goodRx = Regex("web-?dl|web-?rip|webrip|blu-?ray|bdrip|brrip|hdrip|(^|[^a-z])web([^a-z]|$)|amzn|nf[ ._-]web|dsnp")
        val good = l.filter { goodRx.containsMatchIn(t(it)) && !camRx.containsMatchIn(t(it)) }
        if (good.isEmpty()) return l
        val hd = good.any { it.effectiveRes() >= 720 }
        return l.filterNot { s -> val x = t(s); camRx.containsMatchIn(x) || (hd && Regex("dvd-?rip").containsMatchIn(x)) }
    }

    // ───────────── Per-profile data + cloud sync ─────────────

    // In a companion object: settings are written while the ViewModel is still being built (the one-time DNS move on a
    // fresh install), before members declared down here exist — as instance fields they were still null (crash).
    companion object {
        /** Settings that belong to this device / the app, not to a profile. */
        private val AppLocalKeys = setOf("lastProfile", "rememberProfile", "autoProfileSecs", "splash", "engineMigrated", "dns",
            "streamMode", "eliteWifiOnly", "eliteWifiStrict", "guideDone", "searchHistory", "notifyOn")
        /** App-wide texts (app name, Developer's Corner labels): the same for every profile, synced as one field. */
        private val AppSyncKeys = setOf(
            "appName", "showBrand", "forYou", "badge", "newOn", "watchNow", "continueWatching", "recommendedFor", "myList",
            "pillLeft", "pillRight", "pillSports", "navHome", "navMySpace", "pillLive", "helpSettings", "whoWatching",
            "navSearch", "navLive", "navLibrary", "navProfile", "searchHint", "playBtn", "chooseSource", "noStreams",
            "emptyMsg", "addonsTitle", "addonsHint", "addonUrlHint", "installBtn", "aiLogoUrl", "legalAcceptedAt", "legalVersion", "legalAcceptedUid",
        )
    }
    private fun isAppKey(k: String) = k in AppLocalKeys || k in AppSyncKeys

    private var appTexts: Map<String, String> = emptyMap()
    private var profTexts: Map<String, String> = emptyMap()
    /** The profile whose settings, addons and collections are loaded. */
    private var dataPid: String? = null

    private fun decodeMap(s: String?): Map<String, String>? = s?.let { runCatching { Api.json.decodeFromString<Map<String, String>>(it) }.getOrNull() }

    /** Settings keys kept separately for phones and TVs (Same on all devices / Only on this device type). */
    fun deviceOnlyKeys(m: Map<String, String> = profTexts): Set<String> =
        (m["devOnly"] ?: DefaultDeviceKeys.joinToString("|")).split("|").filter { it.isNotBlank() }.toSet()

    /** v0.9.8: settings, addons and collections move from the app into every profile (each gets today's copy). */
    private fun migratePerProfile() {
        if (glob.getBoolean("perProfile1", false)) return
        val all = texts.value
        val prof = all.filterKeys { !isAppKey(it) }
        val addonsJson = sp.getString("list", null)
        val colOld = File(ctx.filesDir, "collections.json")
        profiles.value.forEach { p ->
            prefsForId(p.id).edit().apply {
                putString("pcustom", Api.json.encodeToString(prof))
                if (addonsJson != null) putString("paddons", addonsJson)
            }.apply()
            if (colOld.exists()) runCatching { colOld.copyTo(colFileFor(p.id), overwrite = true) }
        }
        glob.edit().putString("custom", Api.json.encodeToString(all.filterKeys { isAppKey(it) })).putBoolean("perProfile1", true).apply()
    }

    private fun loadProfileData(pid: String) {
        // Each profile has its own watch mode (Turbo / Classic) and sources: nothing found for the previous profile
        // is reused (links, prepared files, plugin answers), so a Classic profile's add-ons never show in a Turbo one.
        if (dataPid != null && dataPid != pid) { forgetAllStreams(); runCatching { Prepare.cancel(); Prepare.state.value = null }; PluginSources.forgetAll(); AddonCalls.forgetAll() }
        dataPid = pid
        val pp = prefsForId(pid)
        profTexts = decodeMap(pp.getString("pcustom", null)) ?: emptyMap()
        texts.value = appTexts + profTexts
        applyPalette()
        val saved = pp.getString("paddons", null)?.let { runCatching { warm("a:$pid", it) { r -> Api.json.decodeFromString<List<Addon>>(r) } }.getOrNull() }
        addons.value = pinFirst(saved ?: emptyList())
        if (saved.isNullOrEmpty()) ensureDefaultAddons(pid)
        switchCollections(pid)
        // Last start's Home rows, shown straight away while the fresh ones load.
        viewModelScope.launch(Dispatchers.IO) {
            val cached = runCatching { File(ctx.filesDir, "shelves_$pid.json").takeIf { it.exists() }?.readText()?.let { Api.json.decodeFromString<List<Shelf>>(it) } }.getOrNull()
            if (!cached.isNullOrEmpty()) withContext(Dispatchers.Main) { if (dataPid == pid && (shelves.value.isEmpty() || loading.value)) shelves.value = cached.map { s -> s.copy(items = s.items.distinctBy { it.type + it.id }) } }
            // 0.9.72 (from Xstream): the saved rows' pictures are fetched while the profile page shows, so Home opens drawn.
            if (!cached.isNullOrEmpty() && !BuildConfig.IS_TV) runCatching { warmHome(cached) }
        }
    }

    /**
     * 0.9.72 (from Xstream's warmHome): Home's pictures to the disk cache — the first 12 posters of every row, the first
     * card's big pictures in every row, and the big cards of the first 3 rows. Tall Top 10 posters only when already known
     * (no extra lookups — less data).
     */
    fun warmHome(rows: List<Shelf>) {
        // 0.9.79: only the rows you see first (4); the rest are fetched one row ahead as you scroll (Home).
        val n = 4
        val urls = rows.take(n).flatMap { s -> s.items.take(12).map { hiResArt(it.poster) } } +
            rows.take(n).mapNotNull { s -> s.items.firstOrNull() }.flatMap { listOf(hdPoster(it), hiResArt(backdropOf(it) ?: it.poster)) } +
            rows.take(3).flatMap { s -> s.items.take(10).flatMap { listOf(hdPoster(it), backdropOf(it)) } } +
            rows.take(n).flatMap { it.items.take(10) }.mapNotNull { TmdbPosters.cached(it) }
        Posters.warm(appContext, urls)
    }

    init {
        migratePerProfile()
        appTexts = (decodeMap(glob.getString("custom", null)) ?: emptyMap()).filterKeys { isAppKey(it) }
        val last = appTexts["lastProfile"]
        val start = profiles.value.firstOrNull { it.id == last } ?: profiles.value.firstOrNull()
        start?.let { loadProfileData(it.id) }
        // (Runs here, after the saved settings are loaded: setText needs them.) Cloudflare (1.1.1.1) is the default. Phones still on the old default ("System") move to it once; a DNS the
        // person picks by hand is theirs from then on (the developer's published DNS never replaces it).
        if (glob.getBoolean("dnsMigrated1", false).not()) {
            if (store("ownKey_dns") != "1" && (appTexts["dns"] ?: "system") == "system") setText("dns", "cloudflare")
            glob.edit().putBoolean("dnsMigrated1", true).apply()
        }
        Api.setDns(appTexts["dns"] ?: "cloudflare")
        Cloud.init(ctx)
        Cloud.attach(this)
        // StreamX: a friend's recommendation lands in your watchlist.
        Recs.init(ctx)
        Recs.addToList = { m -> viewModelScope.launch(Dispatchers.Main) { if (myList.value.none { it.id == m.id }) toggleList(m) } }
    }

    override fun onCleared() {
        Cloud.detach(this)
        super.onCleared()
    }

    // CloudHost — what gets uploaded, and how a change from another device is applied here.

    override fun cloudProfileIds(): List<String> = profiles.value.map { it.id }

    private fun settingsOf(pid: String): Map<String, String> =
        if (pid == dataPid) profTexts else decodeMap(prefsForId(pid).getString("pcustom", null)) ?: emptyMap()

    override fun cloudRead(scope: String, pid: String?, field: String): String? {
        if (scope == "app") return when {
            field == "profiles" -> Api.json.encodeToString(profiles.value)
            field == "appTexts" -> Api.json.encodeToString(appTexts.filterKeys { it in AppSyncKeys })
            field == "logo" -> fileB64(logoFile(), 900_000)
            field.startsWith("avatar_") -> avatarPayload(field.removePrefix("avatar_"))
            else -> null
        }
        val id = pid ?: return null
        val pp = prefsForId(id)
        return when (field) {
            "set" -> settingsOf(id).let { m -> val dev = deviceOnlyKeys(m); Api.json.encodeToString(m.filterKeys { it !in dev }) }
            Cloud.DEVICE_FIELD -> settingsOf(id).let { m -> val dev = deviceOnlyKeys(m); Api.json.encodeToString(m.filterKeys { it in dev }) }
            "addons" -> pp.getString("paddons", null)
            "cols" -> runCatching { colFileFor(id).takeIf { it.exists() }?.readText() }.getOrNull()
            "list" -> pp.getString("mylist", null)
            "prog" -> pp.getString("progress", null)
            "eprog" -> pp.getString("eprog", null)
            "done" -> Api.json.encodeToString(mapOf(
                "k" to (pp.getStringSet("done", emptySet()) ?: emptySet()).joinToString("\n"),
                "ms" to pp.getLong("doneMs", 0L).toString(),
            ))
            "votes" -> pp.getString("votes", null)
            else -> null
        }
    }

    override fun cloudApply(scope: String, pid: String?, field: String, value: String?) {
        if (scope == "app") { applyApp(field, value); return }
        val id = pid ?: return
        if (profiles.value.none { it.id == id }) return
        val pp = prefsForId(id)
        val isData = id == dataPid
        val isCur = id == current.value?.id
        when (field) {
            "set", Cloud.DEVICE_FIELD -> {
                val remote = decodeMap(value) ?: emptyMap()
                val local = settingsOf(id)
                val merged = if (field == "set") {
                    // 0.9.78 (phone): the look arriving from another device (a TV's theme) never replaces this phone's.
                    val dev = deviceOnlyKeys(remote) + (if (BuildConfig.IS_TV) emptySet() else LookDeviceKeys)
                    local.filterKeys { it in dev } + remote.filterKeys { it !in dev }
                } else {
                    val dev = deviceOnlyKeys(local)
                    local.filterKeys { it !in dev } + remote.filterKeys { it in dev }
                }
                pp.edit().putString("pcustom", Api.json.encodeToString(merged)).apply()
                if (isData) {
                    val addonsChanged = merged["addonsOff"] != profTexts["addonsOff"]
                    profTexts = merged
                    texts.value = appTexts + profTexts
                    applyPalette()
                    if (addonsChanged && current.value != null) refresh()
                }
            }
            "addons" -> {
                val list = value?.let { runCatching { Api.json.decodeFromString<List<Addon>>(it) }.getOrNull() } ?: return
                pp.edit().putString("paddons", value).apply()
                if (isData) { addons.value = pinFirst(list); if (current.value != null) refresh() }
            }
            "cols" -> {
                runCatching { if (value == null) colFileFor(id).delete() else colFileFor(id).writeText(value) }
                if (isData) switchCollections(id)
            }
            "list" -> {
                pp.edit().putString("mylist", value).apply()
                if (isCur) myList.value = (value?.let { runCatching { Api.json.decodeFromString<List<Meta>>(it) }.getOrNull() } ?: emptyList()).distinctBy { it.id }
            }
            "eprog" -> {
                pp.edit().putString("eprog", value).apply()
                if (isCur) epProgress.value = value?.let { runCatching { Api.json.decodeFromString<Map<String, Progress>>(it) }.getOrNull() } ?: emptyMap()
            }
            "prog" -> {
                pp.edit().putString("progress", value).apply()
                if (isCur) progress.value = value?.let { runCatching { Api.json.decodeFromString<List<Progress>>(it) }.getOrNull() } ?: emptyList()
            }
            "done" -> {
                val m = decodeMap(value) ?: emptyMap()
                val keys = m["k"].orEmpty().split("\n").filter { it.isNotBlank() }.toSet()
                val ms = m["ms"]?.toLongOrNull() ?: 0L
                pp.edit().putStringSet("done", keys).putLong("doneMs", ms).apply()
                if (isCur) { doneKeys.value = keys; doneMs.value = ms }
            }
            "votes" -> {
                pp.edit().putString("votes", value).apply()
                if (isCur) votes.value = value?.let { runCatching { Api.json.decodeFromString<Map<String, Vote>>(it) }.getOrNull() } ?: emptyMap()
            }
        }
    }

    private fun applyApp(field: String, value: String?) {
        when {
            field == "profiles" -> {
                val list = value?.let { runCatching { Api.json.decodeFromString<List<Profile>>(it) }.getOrNull() }
                if (list.isNullOrEmpty()) return
                val gone = profiles.value.filter { old -> list.none { it.id == old.id } }
                // Keep this device's picture state (kind / version) when only the file differs.
                saveProfiles(list, mark = false)
                gone.forEach { p ->
                    avatarFile(p).delete()
                    if (p.id != "p1") ctx.deleteSharedPreferences("library_${p.id}")
                    runCatching { colFileFor(p.id).delete() }
                }
                if (current.value != null && list.none { it.id == current.value?.id }) current.value = null
                if (dataPid != null && list.none { it.id == dataPid }) loadProfileData(list.first().id)
            }
            field == "appTexts" -> {
                val remote = decodeMap(value) ?: emptyMap()
                appTexts = appTexts.filterKeys { it !in AppSyncKeys } + remote.filterKeys { it in AppSyncKeys }
                glob.edit().putString("custom", Api.json.encodeToString(appTexts)).apply()
                texts.value = appTexts + profTexts
            }
            field == "logo" -> {
                if (value == null) logoFile().delete() else runCatching { logoFile().writeBytes(android.util.Base64.decode(value, android.util.Base64.NO_WRAP)) }
                imgVersion.value = imgVersion.value + 1
            }
            field.startsWith("avatar_") -> {
                val id = field.removePrefix("avatar_")
                val p = profiles.value.firstOrNull { it.id == id } ?: return
                if (value != null && value.startsWith("url:")) {
                    // "url:<kind>:<link>": download the original (animated GIF / video) from its link.
                    val k = value.removePrefix("url:").substringBefore(':')
                    val link = value.removePrefix("url:").substringAfter(':')
                    glob.edit().putString("avatarUrl_$id", link).apply()
                    viewModelScope.launch(Dispatchers.IO) {
                        val ok = runCatching {
                            Api.http.newCall(Request.Builder().url(link).header("User-Agent", "Mozilla/5.0").build()).execute().use { r ->
                                val body = r.body
                                if (!r.isSuccessful || body == null) false
                                else { body.byteStream().use { i -> avatarFile(p).outputStream().use { o -> i.copyTo(o) } }; true }
                            }
                        }.getOrDefault(false)
                        if (ok) withContext(Dispatchers.Main) {
                            saveProfiles(profiles.value.map { if (it.id == id) it.copy(avatar = k.ifEmpty { "gif" }, ver = it.ver + 1) else it }, mark = false)
                        }
                    }
                    return
                }
                val kind = value?.substringBefore(':', "")?.takeIf { it in setOf("img", "gif", "video") }
                if (value == null || kind == null) {
                    avatarFile(p).delete()
                    saveProfiles(profiles.value.map { if (it.id == id) it.copy(avatar = "", ver = it.ver + 1) else it }, mark = false)
                } else {
                    glob.edit().remove("avatarUrl_$id").apply()
                    runCatching { avatarFile(p).writeBytes(android.util.Base64.decode(value.substringAfter(':'), android.util.Base64.NO_WRAP)) }
                    saveProfiles(profiles.value.map { if (it.id == id) it.copy(avatar = kind, ver = it.ver + 1) else it }, mark = false)
                }
            }
        }
    }

    private fun fileB64(f: File, max: Long): String? = runCatching {
        if (!f.exists() || f.length() > max) null else android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
    }.getOrNull()

    /** "kind:base64". Big pictures / GIFs / videos are sent as a small JPEG still so the cloud stays light. */
    private fun avatarPayload(pid: String): String? {
        val p = profiles.value.firstOrNull { it.id == pid } ?: return null
        val f = avatarFile(p)
        if (p.avatar.isEmpty() || !f.exists()) return null
        // Picked from a link: send the link, so the other devices get the original (animated) picture.
        glob.getString("avatarUrl_$pid", null)?.takeIf { it.startsWith("http") }?.let { return "url:${p.avatar}:$it" }
        if (f.length() <= 1_200_000L) fileB64(f, 1_200_000L)?.let { return "${p.avatar}:$it" }
        // Animated pictures (GIF / short video) up to 6 MB go as they are, so they stay animated on your other devices.
        if (p.avatar != "img" && f.length() <= 6_000_000L) fileB64(f, 6_000_000L)?.let { return "${p.avatar}:$it" }
        val bmp = runCatching {
            if (p.avatar == "video") MediaMetadataRetriever().run { setDataSource(f.absolutePath); val b = getFrameAtTime(0); release(); b }
            else android.graphics.BitmapFactory.decodeFile(f.absolutePath)
        }.getOrNull() ?: return null
        val scale = 320f / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
        val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
        val out = java.io.ByteArrayOutputStream()
        small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
        return "img:" + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    // Open the last profile straight away when "Remember last profile" is on.
    init {
        if (isOn(texts.value["rememberProfile"] ?: "no")) {
            // A locked profile is never opened by itself: "Who's watching?" shows and asks for its PIN.
            profiles.value.firstOrNull { it.id == texts.value["lastProfile"] }?.takeIf { !ProfileLock.hasPin(it.id) }?.let { selectProfile(it) }
        }
    }
}

private val logoRe = Regex("tvg-logo=\"([^\"]*)\"")
private val groupRe = Regex("group-title=\"([^\"]*)\"")

fun parseM3u(text: String): List<Channel> {
    val out = ArrayList<Channel>()
    var name: String? = null
    var logo: String? = null
    var group: String? = null
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.startsWith("#EXTINF")) {
            name = line.substringAfterLast(",").trim()
            logo = logoRe.find(line)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            group = groupRe.find(line)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        } else if (line.isNotEmpty() && !line.startsWith("#") && name != null) {
            out.add(Channel(name.ifBlank { line }, logo, group, line))
            name = null
        }
    }
    return out
}

/** Cinemeta's own image host gives wide backdrops and title logos for any IMDb id. */
fun backdropOf(m: Meta): String? = m.background
    ?: if (m.id.startsWith("tt")) "https://images.metahub.space/background/medium/${m.id.substringBefore(':')}/img" else m.poster

fun logoOf(m: Meta): String? = m.logo
    ?: if (m.id.startsWith("tt")) "https://images.metahub.space/logo/medium/${m.id.substringBefore(':')}/img" else null

/** X's voice settings: saved with the account's settings. */
private val VoiceKeys = setOf("aiVoice", "aiVoiceEn", "aiVoiceHi", "aiVoiceRate", "aiVoiceOut")
