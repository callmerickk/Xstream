package com.example.streamnest

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import java.security.MessageDigest
import java.security.SecureRandom

const val CINEMETA = "https://v3-cinemeta.strem.io"

class AppViewModel(app: Application) : AndroidViewModel(app), CloudHost {
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
            // v0.7 rename: drop the old default name so "Xstream" shows
            .filterNot { it.key == "appName" && (it.value.trim().equals("streamnest", ignoreCase = true) || it.value.trim().equals("streamx", ignoreCase = true)) }
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
    /** Xstream: where you are in EVERY episode you started (Continue Watching keeps only the latest one per series). */
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

    private fun prefsFor(p: Profile): SharedPreferences =
        if (p.id == "p1") glob else ctx.getSharedPreferences("library_${p.id}", Context.MODE_PRIVATE)

    fun selectProfile(p: Profile) {
        if (texts.value["lastProfile"] != p.id) setText("lastProfile", p.id)
        val wasKids = current.value?.kids
        // Each profile has its own settings, addons and collections.
        val switched = dataPid != p.id
        if (switched) loadProfileData(p.id)
        current.value = p
        lib = prefsFor(p)
        progress.value = lib.getString("progress", null)?.let { runCatching { warm("p:${p.id}", it) { r -> Api.json.decodeFromString<List<Progress>>(r) } }.getOrNull() } ?: emptyList()
        epProgress.value = lib.getString("eprog", null)?.let { runCatching { Api.json.decodeFromString<Map<String, Progress>>(it) }.getOrNull() } ?: emptyMap()
        WatchLog.load(ctx, p.id)
        myList.value = lib.getString("mylist", null)?.let { runCatching { warm("l:${p.id}", it) { r -> Api.json.decodeFromString<List<Meta>>(r) } }.getOrNull() } ?: emptyList()
        doneKeys.value = lib.getStringSet("done", emptySet())?.toSet() ?: emptySet()
        doneMs.value = lib.getLong("doneMs", 0L)
        votes.value = lib.getString("votes", null)?.let { runCatching { Api.json.decodeFromString<Map<String, Vote>>(it) }.getOrNull() } ?: emptyMap()
        recommended.value = emptyList()
        if (switched || wasKids != p.kids || shelves.value.isEmpty()) refresh()
        refreshRecommended()
        // New-episode alerts: remember this profile's shows (after the addons are ready).
        viewModelScope.launch { delay(8000); runCatching { EpisodeAlerts.snapshot(ctx, this@AppViewModel) } }
        // Look up the streams of your top Continue Watching titles early, so their title pages prepare instantly.
        viewModelScope.launch {
            delay(5000)
            progress.value.sortedByDescending { it.updated }.take(2).forEach { pr ->
                val type = if (pr.key.contains(":")) "series" else "movie"
                runCatching { if (qsActive()) quickStreams(type, pr.key) else allStreams(type, pr.key) }
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
        // On the developer's phone the AI logo goes to every Xstream account (shared/appLook).
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
        val data = runCatching { android.util.Base64.decode(payload.substringAfter(':'), android.util.Base64.DEFAULT) }.getOrNull() ?: return
        runCatching { aiLogoFile().writeBytes(data) }.onSuccess {
            prefs.edit().putInt("hash", payload.hashCode()).apply()
            imgVersion.value = imgVersion.value + 1
        }
    }

    /** This phone's AI logo, ready for the cloud ("" = none). */
    fun aiLogoNow(): String = runCatching { aiLogoFile().takeIf { it.exists() }?.let { aiLogoPayload(it) } }.getOrNull().orEmpty()

    fun clearAiLogo() {
        aiLogoFile().delete(); setText("aiLogoData", "")
        ctx.getSharedPreferences("ai_logo", Context.MODE_PRIVATE).edit().remove("hash").apply()
        imgVersion.value = imgVersion.value + 1
    }

    fun refreshRecommended() {
        recJob?.cancel()
        recJob = viewModelScope.launch { runCatching { recommended.value = buildRecommendations() } }
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
        if (!BuildConfig.IS_TV) { Buddies.init(app); Invites.start(app) }
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
        Api.setDns(texts.value["dns"] ?: "system")
    }

    fun setDns(provider: String) {
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
        m["cardCorner"]!!.trim().toIntOrNull()?.let { Palette.corner = it.coerceIn(0, 50) }
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
        val done = p.durationMs > 0 && p.positionMs > p.durationMs * 0.95
        progress.value = (if (done && !series) others else listOf(if (done) p.copy(positionMs = p.durationMs) else p) + others).take(20)
        val ed = lib.edit().putString("progress", Api.json.encodeToString(progress.value))
        if (done && p.key !in doneKeys.value) {
            doneKeys.value = doneKeys.value + p.key
            doneMs.value = doneMs.value + p.durationMs
            ed.putStringSet("done", doneKeys.value).putLong("doneMs", doneMs.value)
            markCur("done")
        }
        ed.apply()
        markCur("prog")
    }

    fun removeProgress(key: String) {
        progress.value = progress.value.filter { it.key != key }
        lib.edit().putString("progress", Api.json.encodeToString(progress.value)).apply()
        markCur("prog")
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
    suspend fun prewarmProfile(pid: String) {
        if (!BuildConfig.IS_TV) return
        withContext(Dispatchers.Default) {
            runCatching {
                val pp = prefsForId(pid)
                pp.getString("paddons", null)?.let { r -> warmed["a:$pid"] = r to Api.json.decodeFromString<List<Addon>>(r) }
                pp.getString("progress", null)?.let { r -> warmed["p:$pid"] = r to Api.json.decodeFromString<List<Progress>>(r) }
                pp.getString("mylist", null)?.let { r -> warmed["l:$pid"] = r to Api.json.decodeFromString<List<Meta>>(r) }
                pp.getString("pcustom", null)
            }
        }
    }

    /** TV Library › Clear all: empties Continue Watching only (finished titles and stats stay). */
    fun clearContinue() {
        progress.value = emptyList()
        lib.edit().remove("progress").apply()
        markCur("prog")
    }

    fun toggleList(m: Meta) {
        val cur = myList.value
        myList.value = if (cur.any { it.id == m.id }) cur.filter { it.id != m.id } else listOf(m) + cur
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
    private val backupKeys = listOf("qsAddons", "qsOn", "qsLangs", "qsQuality", "qsUpgrade", "subLangs", "tagRules", "srcAutoManage",
        "testedOnly", "hideDead", "autoOnlineSubs", "subAutoSync")

    /** Everything about your addons as a small JSON text: links in order, on / off, pins, Quick play and the play settings. */
    /** [withAccounts] false: the quick server accounts (PenguPlay, AIOStreams) are left out. */
    fun exportAddons(withAccounts: Boolean = true): String = buildJsonObject {
        put("app", "Xstream"); put("v", 1); put("at", System.currentTimeMillis())
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
        val o = runCatching { Api.json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return "That isn't a Xstream addon backup."
        val items = o["addons"]?.jsonArray?.mapNotNull { it as? JsonObject } ?: return "That isn't a Xstream addon backup."
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
            if (replace) o["settings"]?.jsonObject?.forEach { (k, v) -> if (k in backupKeys) v.jsonPrimitive.contentOrNull?.let { setText(k, it) } }
            setAddons(list)
            refresh()
        }
        return (if (replace) "Imported · your addons now match (${list.size})" else "Merged · $added new addon${if (added == 1) "" else "s"} added") +
            (if (failed > 0) " · $failed couldn't be reached" else "")
    }

    /** Switched-on addons that keep loading on their server (CNCVerse): asked again every 5 s (SlowAddons.kt). */
    fun slowStreamAddons(type: String, id: String): List<Addon> = active.filter { it.supports("stream", type, id) && SlowAddons.isSlow(it) }
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

    private var refreshJob: Job? = null

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            loading.value = true
            val result = if (current.value?.kids == true) kidsShelves() else coroutineScope {
                active.flatMap { a ->
                    a.manifest.catalogs
                        .filter { c -> c.extra.orEmpty().none { it.isRequired } }
                        .map { c ->
                            async {
                                runCatching {
                                    val items = Api.fetch<CatalogResponse>("${a.baseUrl}/catalog/${c.type}/${enc(c.id)}.json").metas
                                    Shelf("${c.name ?: c.id} ${typeLabel(c.type)}", items)
                                }.getOrNull()
                            }
                        }
                }.take(14).awaitAll()
            }
            shelves.value = result.filterNotNull().filter { it.items.isNotEmpty() }
            loading.value = false
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
    fun streamsFlow(type: String, id: String, only: Set<String>? = null, except: Set<String>? = null): Flow<Pair<List<StreamItem>, Boolean>> = channelFlow {
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
            return l.filterNot { TitleMatch.wrong(it, type, id) }
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

    // ───────────── Last working link per title (Playback > Reuse last link) ─────────────
    @Serializable data class LastLink(val url: String, val source: String? = null, val addon: String? = null, val at: Long = 0)

    fun lastLink(key: String): LastLink? = lib.getString("link_$key", null)?.let { runCatching { Api.json.decodeFromString<LastLink>(it) }.getOrNull() }
        ?.takeIf { System.currentTimeMillis() - it.at < 6L * 3600 * 1000 } // provider links expire; reuse for 6 hours (a refused link is refreshed automatically)

    fun rememberLink(key: String, url: String, source: String?, addon: String?) {
        if (key.isBlank() || url.isBlank()) return
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
    fun playerPref(k: String): String = lib.getString("pp_$k", "") ?: ""
    fun setPlayerPref(k: String, value: String) { lib.edit().putString("pp_$k", value).apply() }

    // ───────────── Badge pack (Layout > Streams) ─────────────
    val badgePack = MutableStateFlow<List<PackBadge>>(emptyList())
    private var packUrl: String? = null

    /** Accepts {"pattern": "imageUrl"}, [{"pattern","image","name"}] or {"badges": [...]}. */
    fun loadBadgePack(url: String) {
        val u = url.trim()
        if (u == packUrl) return
        packUrl = u
        if (u.isBlank()) { badgePack.value = emptyList(); return }
        viewModelScope.launch(Dispatchers.IO) {
            val list = runCatching {
                val el = Api.json.parseToJsonElement(Api.get(u))
                val arr = (el as? JsonArray) ?: (el as? JsonObject)?.get("badges") as? JsonArray
                if (arr != null) arr.mapNotNull { e ->
                    val o = e.jsonObject
                    val pat = o["pattern"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val img = (o["image"] ?: o["url"])?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    PackBadge(Regex(pat, RegexOption.IGNORE_CASE), img, o["name"]?.jsonPrimitive?.contentOrNull ?: "")
                } else (el as JsonObject).mapNotNull { (k, v) ->
                    val img = v.jsonPrimitive.contentOrNull ?: return@mapNotNull null
                    runCatching { PackBadge(Regex(k, RegexOption.IGNORE_CASE), img, "") }.getOrNull()
                }
            }.getOrDefault(emptyList())
            badgePack.value = list
        }
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
    suspend fun onlineSubtitles(type: String, id: String): List<Subtitle> = coroutineScope {
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
        val r = runCatching { Api.fetch<Segments>(url) }.getOrNull() ?: return null
        // Only trust data for exactly this episode / movie.
        if (parts.size >= 3) {
            if (r.season != null && r.season != parts[1].toIntOrNull()) return null
            if (r.episode != null && r.episode != parts[2].toIntOrNull()) return null
        } else if (r.is_movie == false) return null
        // Drop doubtful or impossible ranges (too short, too long, low confidence).
        fun ok(sg: Seg?) = sg?.takeIf { it.end_ms - it.start_ms in 5_000..600_000 && it.start_ms >= 0 && it.confidence >= 0.5 }
        return Segments(ok(r.intro), ok(r.recap), r.outro?.takeIf { it.start_ms > 0 && it.confidence >= 0.5 }, r.is_movie, r.season, r.episode)
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
        return applyTagRules(dropCams(ranked), parseTagRules(texts.value["tagRules"] ?: ""))
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

    /** Settings that belong to this device / the app, not to a profile. */
    private val AppLocalKeys = setOf("lastProfile", "rememberProfile", "autoProfileSecs", "splash", "engineMigrated", "dns",
        "streamMode", "eliteWifiOnly", "guideDone", "searchHistory", "notifyOn")
    /** App-wide texts (app name, Developer's Corner labels): the same for every profile, synced as one field. */
    private val AppSyncKeys = setOf(
        "appName", "showBrand", "forYou", "badge", "newOn", "watchNow", "continueWatching", "recommendedFor", "myList",
        "pillLeft", "pillRight", "pillSports", "navHome", "navMySpace", "pillLive", "helpSettings", "whoWatching",
        "navSearch", "navLive", "navLibrary", "navProfile", "searchHint", "playBtn", "chooseSource", "noStreams",
        "emptyMsg", "addonsTitle", "addonsHint", "addonUrlHint", "installBtn", "aiLogoUrl",
    )
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
            if (!cached.isNullOrEmpty()) withContext(Dispatchers.Main) { if (dataPid == pid && (shelves.value.isEmpty() || loading.value)) shelves.value = cached }
        }
    }

    init {
        migratePerProfile()
        appTexts = (decodeMap(glob.getString("custom", null)) ?: emptyMap()).filterKeys { isAppKey(it) }
        val last = appTexts["lastProfile"]
        val start = profiles.value.firstOrNull { it.id == last } ?: profiles.value.firstOrNull()
        start?.let { loadProfileData(it.id) }
        Cloud.init(ctx)
        Cloud.attach(this)
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
                    val dev = deviceOnlyKeys(remote)
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
                if (isCur) myList.value = value?.let { runCatching { Api.json.decodeFromString<List<Meta>>(it) }.getOrNull() } ?: emptyList()
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
