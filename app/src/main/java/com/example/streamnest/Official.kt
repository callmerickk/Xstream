package com.example.streamnest

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The developer's official add-ons and plugins (Developer's Corner › Sources & plugins › Official set): only what is
 * published here reaches other accounts (shared/devAddons/official). Add-ons or extensions anyone adds on their own phone
 * stay theirs — a backup that plays when nothing official does, or when picked by hand.
 */
@Serializable
data class OfficialSet(
    val addons: List<String> = emptyList(),
    val csRepos: List<String> = emptyList(),
    val csEnabled: List<String> = emptyList(),
    val qsCs: String = "",
    val qs4k: String = "",
    val at: Long = 0L,
)

object Official {
    private const val PATH = "shared/devAddons/official.json"

    suspend fun fetch(): OfficialSet? = withContext(Dispatchers.IO) {
        runCatching { Api.json.decodeFromString<OfficialSet>(Api.get("${CloudConfig.DB_URL}/$PATH")) }.getOrNull()?.takeIf { it.at > 0 }
    }

    /** This phone's current set-up (the developer's): add-ons, CloudStream repositories, plugins on, Quick Play / 4K picks. */
    fun fromDevice(vm: AppViewModel): OfficialSet = OfficialSet(
        addons = vm.addons.value.map { it.baseUrl },
        csRepos = CsPlugins.repos.value.map { it.url },
        csEnabled = CsPlugins.enabled.value.toList(),
        qsCs = vm.texts.value["qsCs"].orEmpty(),
        qs4k = vm.texts.value["qs4k"].orEmpty(),
        at = System.currentTimeMillis(),
    )

    suspend fun publish(s: OfficialSet): String? = withContext(Dispatchers.IO) {
        val t = Cloud.token() ?: return@withContext "Sign in first."
        runCatching {
            Api.http.newCall(Request.Builder().url("${CloudConfig.DB_URL}/$PATH?auth=$t")
                .put(Api.json.encodeToString(OfficialSet.serializer(), s.copy(at = System.currentTimeMillis())).toRequestBody("application/json".toMediaType())).build())
                .execute().use { r -> if (r.isSuccessful) null else if (r.code == 401 || r.code == 403) "Not allowed: the Firebase rule for shared/devAddons, and the owner's account." else "HTTP ${r.code}" }
        }.getOrElse { "Couldn't reach the cloud." }
    }

    /** Every other account: the published set is applied when a profile opens (the developer's own phone is left alone). */
    suspend fun apply(vm: AppViewModel, ctx: Context) {
        if (BuildConfig.IS_TV || Public.dev) return
        val s = fetch() ?: return
        for (u in s.addons) if (vm.addons.value.none { it.baseUrl == Api.normalize(u) }) runCatching { vm.installAddon(u) }
        CsPlugins.init(ctx)
        val missing = s.csRepos.filter { r -> CsPlugins.repos.value.none { it.url == r } }
        missing.forEach { CsPlugins.addRepo(it) }
        if (missing.isNotEmpty() || CsPlugins.catalog.value.isEmpty()) runCatching { CsPlugins.refresh() }
        withContext(Dispatchers.Main) {
            if (s.csEnabled.isNotEmpty() && s.csEnabled.toSet() != CsPlugins.enabled.value) CsPlugins.setEnabledSet(s.csEnabled.toSet())
            if (s.qsCs.isNotBlank()) vm.setText("qsCs", s.qsCs)
            if (s.qs4k.isNotBlank()) vm.setText("qs4k", s.qs4k)
        }
    }
}

/** Developer's Corner › Official set: what every account gets. */
@Composable
fun OfficialSetPage(vm: AppViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var set by remember { mutableStateOf(OfficialSet()) }
    var loaded by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf("") }
    val addons by vm.addons.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { set = Official.fetch() ?: OfficialSet(); loaded = true }
    Column(Modifier.fillMaxSize().background(Bg)) {
        PageHeader("Official set", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 80.dp)) {
            Text("Only what is published here reaches other accounts. Add-ons and plugins added anywhere else are for testing: they play when you pick them, or as a last backup.",
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 13.sp, color = Muted)
            if (!loaded) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            SectionLabel("Official add-ons (${set.addons.size})")
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    set.addons.forEach { u ->
                        val name = addons.firstOrNull { it.baseUrl == Api.normalize(u) }?.label()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name ?: u.substringAfter("//").substringBefore('/'), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(u, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton({ set = set.copy(addons = set.addons - u) }) { Icon(Icons.Default.Close, "Remove", tint = Color(0xFFFF8A80)) }
                        }
                    }
                    OutlinedTextField(link, { link = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp),
                        placeholder = { Text("Paste an add-on link (manifest.json)") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ val b = Api.normalize(link); if (b !in set.addons) set = set.copy(addons = set.addons + b); link = "" }, enabled = link.startsWith("http")) { Text("Add", color = Color.White) }
                        OutlinedButton({ set = set.copy(addons = addons.map { it.baseUrl }) }) { Text("Use all my add-ons", color = Color.White) }
                    }
                }
            }
            SectionLabel("Official plugins")
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${set.csRepos.size} repositories · ${set.csEnabled.size} plugins on", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Quick Play: " + qsCsParse(set.qsCs).joinToString(", ") { it.substringAfter('#') }.ifBlank { "—" }, fontSize = 12.5.sp, color = Muted)
                    Text("Upgrade to 4K: " + qsCsParse(set.qs4k).joinToString(", ") { it.substringAfter('#') }.ifBlank { "—" }, fontSize = 12.5.sp, color = Muted)
                    OutlinedButton({ val d = Official.fromDevice(vm); set = set.copy(csRepos = d.csRepos, csEnabled = d.csEnabled, qsCs = d.qsCs, qs4k = d.qs4k) }) {
                        Text("Use my current plugin set-up", color = Color.White)
                    }
                    Text("Set the plugins up on this phone first (Developer's Corner › CloudStream plugins, Quick Start), then tap this.", fontSize = 12.sp, color = Muted)
                }
            }
        }
        // Always on screen (it used to sit at the very bottom, easy to miss).
        PublishBar("Publish the official set for everyone") { Official.publish(set) }
    }
}

/**
 * Developer's Corner publishing: one section at a time, or everything at once. Xstream and StreamX (phone and TV)
 * read the same cloud, so one publish reaches every account on every device.
 */
object PublishAll {
    /** This phone's add-ons into the official set (plugins as published). */
    suspend fun addons(vm: AppViewModel): String? { val cur = Official.fetch() ?: OfficialSet(); return Official.publish(cur.copy(addons = Official.fromDevice(vm).addons)) }
    /** This phone's CloudStream repositories, plugins on, Quick Play and Upgrade-to-4K picks into the official set. */
    suspend fun plugins(vm: AppViewModel): String? {
        val cur = Official.fetch() ?: OfficialSet(); val d = Official.fromDevice(vm)
        return Official.publish(cur.copy(csRepos = d.csRepos, csEnabled = d.csEnabled, qsCs = d.qsCs, qs4k = d.qs4k))
    }
    /** Code names, Home rows, filters, keys (the developer's defaults). */
    suspend fun defaults(vm: AppViewModel): String? = DevDefaults.publish(vm)
    suspend fun everything(vm: AppViewModel): List<Pair<String, String?>> = listOf(
        "Add-ons, plugins, Quick Play & 4K picks" to Official.publish(Official.fromDevice(vm)),
        "Code names, Home rows, filters & keys" to DevDefaults.publish(vm),
        "Logo, animation & AI look" to AppLook.publish(AppLook.logo.value, AppLook.anim.value),
    )
}

/** A Publish button with its result line (Developer's Corner pages). */
@Composable
fun PublishBar(label: String, modifier: Modifier = Modifier, action: suspend () -> String?) {
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(Color(0xF20E0E12)).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Button({ busy = true; msg = "Publishing…"; scope.launch { msg = action() ?: "Published ✓ — every account (phone and TV) gets it the next time a profile opens."; busy = false } },
            Modifier.fillMaxWidth().height(50.dp), enabled = !busy, shape = RoundedCornerShape(25.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914), contentColor = Color.White)) { Text(label, fontWeight = FontWeight.Bold) }
        msg?.let { Text(it, Modifier.padding(top = 6.dp), fontSize = 12.5.sp, color = Muted) }
    }
}
