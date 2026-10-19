package com.example.streamnest

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.io.File

// ───────────── AI button logo (user's own transparent image) ─────────────

fun AppViewModel.aiLogoFile(): File = File(appContext.filesDir, "ai_logo.img")

/** The logo shown inside the AI button: a picked image, a link, or null (built-in sparkle). */
@Composable
fun AiLogo(vm: AppViewModel, modifier: Modifier) {
    val ctx = LocalContext.current
    val texts by vm.texts.collectAsStateWithLifecycle()
    val ver by vm.imgVersion.collectAsStateWithLifecycle()
    // A logo set on another phone arrives with your settings: saved here once.
    val shared by AppLook.aiLogo.collectAsState()
    val synced = shared.ifBlank { texts["aiLogoData"].orEmpty() }
    LaunchedEffect(synced) { if (synced.isNotBlank()) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.syncAiLogo(synced) } }
    val file = remember(ver) { vm.aiLogoFile().takeIf { it.exists() } }
    val url = texts["aiLogoUrl"].orEmpty().trim()
    when {
        file != null -> AsyncImage(
            ImageRequest.Builder(ctx).data(file).memoryCacheKey("ai_logo_$ver").diskCachePolicy(CachePolicy.DISABLED).build(),
            null, modifier, contentScale = ContentScale.Fit,
        )
        url.startsWith("http") -> AsyncImage(url, null, modifier, contentScale = ContentScale.Fit)
        else -> Icon(Icons.Default.AutoAwesome, null, modifier, tint = Color.White)
    }
}

// ───────────── Help & Settings > Integrations ─────────────

@Composable
internal fun IntegrationsPage(vm: AppViewModel, onBack: () -> Unit) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    fun v(k: String) = texts[k] ?: Defaults[k] ?: ""
    fun yn(b: Boolean) = if (b) "yes" else "no"
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val provider = vm.aiProvider()
    var keyText by remember(provider.id) { mutableStateOf(vm.aiKey(provider)) }
    var modelText by remember(provider.id) { mutableStateOf(texts["aiModel_${provider.id}"] ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var forgetAsk by remember { mutableStateOf(false) }
    var votesAsk by remember { mutableStateOf(false) }
    var logoUrl by remember { mutableStateOf(v("aiLogoUrl")) }
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) scope.launch { vm.setAiLogo(u) }
    }

    Column(Modifier.fillMaxSize().pageScroll().navigationBarsPadding().padding(bottom = 40.dp)) {
        PageHeader("Integrations", onBack)

        SectionLabel("AI assistant")
        SettingsCard {
            ToggleRow("AI assistant", "Use an AI for the voice assistant and your recommendations. Without a key the offline assistant is used.", isOn(v("aiOn"))) { vm.setText("aiOn", yn(it)) }
            RowDivider(20.dp)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text("Provider", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiProviders.forEach { p ->
                        val on = p.id == provider.id
                        Text(p.label, Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Accent else Color(0xFF2A2A30))
                            .clickable { vm.setText("aiProvider", p.id); testResult = null }.padding(horizontal = 12.dp, vertical = 9.dp),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) OnAccent else Color.White, maxLines = 1, softWrap = false)
                    }
                }
                Text(provider.note, Modifier.padding(top = 8.dp), fontSize = 13.sp, color = Muted)

                OutlinedTextField(
                    keyText, { keyText = it.trim(); testResult = null }, Modifier.fillMaxWidth().padding(top = 12.dp).tvOkKeyboard(top = 8.dp),
                    singleLine = true, shape = RoundedCornerShape(12.dp), label = { Text("${provider.label} API key") },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = tvKeyboard(KeyboardOptions(keyboardType = KeyboardType.Password)),
                    trailingIcon = {
                        IconButton({ showKey = !showKey }) { Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show key") }
                    })
                OutlinedTextField(
                    modelText, { modelText = it.trim() }, Modifier.fillMaxWidth().padding(top = 8.dp).tvOkKeyboard(top = 8.dp),
                    singleLine = true, shape = RoundedCornerShape(12.dp), label = { Text("Model (optional)") }, placeholder = { Text(provider.model) }, keyboardOptions = tvKeyboard())
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button({
                        vm.setText("aiKey_${provider.id}", keyText); vm.setText("aiModel_${provider.id}", modelText)
                        testing = true; testResult = null
                        scope.launch {
                            testResult = vm.aiTest(provider, keyText) ?: "ok"; testing = false
                            modelText = vm.texts.value["aiModel_${provider.id}"] ?: modelText // may have been switched to a model your key can use
                        }
                    }, enabled = keyText.isNotBlank() && !testing, shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)) {
                        Text(if (testing) "Testing…" else "Save & test", fontWeight = FontWeight.SemiBold)
                    }
                    TextButton({ runCatching { uri.openUri(provider.keyPage) } }) { Text("Get a free key", color = Color.White) }
                }
                testResult?.let {
                    Text(if (it == "ok") "✓ Connected. Your AI assistant is ready." else "✕ $it", Modifier.padding(top = 8.dp),
                        fontSize = 14.sp, color = if (it == "ok") Color(0xFF5CD98A) else Color(0xFFFF6B6B))
                }
                Text("If a model isn't available for your key, Xstream switches to one that is.", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Muted)
                Text("Your key is stored only on this phone and sent only to ${provider.label}.", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = Muted)
            }
            RowDivider(20.dp)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text("AI button logo", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("Pick a transparent PNG (or paste a link) to show inside the AI button. Leave empty for the built-in sparkle.", fontSize = 13.sp, color = Muted)
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF1F3B8A), Color(0xFF4A1E6E)))), Alignment.Center) {
                        AiLogo(vm, Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    TextButton({ pickLogo.launch(arrayOf("image/*")) }) { Text("Choose image") }
                    TextButton({ vm.clearAiLogo(); logoUrl = ""; vm.setText("aiLogoUrl", "") }) { Text("Remove", color = Color(0xFFFF6B6B)) }
                }
                OutlinedTextField(
                    logoUrl, { logoUrl = it.trim() }, Modifier.fillMaxWidth().padding(top = 6.dp).tvOkKeyboard(), singleLine = true, shape = RoundedCornerShape(12.dp),
                    placeholder = { Text("https://…/logo.png") },
                    trailingIcon = { if (logoUrl != v("aiLogoUrl")) TextButton({ vm.clearAiLogo(); vm.setText("aiLogoUrl", logoUrl) }) { Text("Save") } }, keyboardOptions = tvKeyboard())
            }
            if (!BuildConfig.IS_TV) { RowDivider(20.dp); AiVoicePicker(vm) }
            RowDivider(20.dp)
            SettingRow(null, "Forget AI memory", "Clears what the assistant remembers from your past requests (this profile).") { forgetAsk = true }
        }

        SectionLabel("Recommendations")
        SettingsCard {
            ToggleRow("Recommended for you", "Show a row on Home built from what you liked.", isOn(v("showRecs"))) { vm.setText("showRecs", yn(it)) }
            RowDivider(20.dp)
            ToggleRow("Ask \"How was it?\"", "After watching, ask Go for it / Skip / Just surfing.", isOn(v("askVote"))) { vm.setText("askVote", yn(it)) }
            RowDivider(20.dp)
            SettingRow(null, "Reset my votes", "Start your taste profile from scratch (this profile).") { votesAsk = true }
        }

        SectionLabel("TMDB")
        SettingsCard { KeyField(vm, "tmdbKey", "TMDB API key (v3)", "Sharper backdrops and title logos on detail pages.", "https://www.themoviedb.org/settings/api") }
        SectionLabel("MDBList ratings")
        SettingsCard { KeyField(vm, "mdbKey", "MDBList API key", "Rotten Tomatoes, Metacritic, TMDB and Letterboxd ratings on detail pages.", "https://mdblist.com/preferences/") }
        SectionLabel("GIPHY")
        SettingsCard { KeyField(vm, "giphyKey", "GIPHY API key", "Search GIFs for profile pictures (My Space › Edit profile › GIFs).", "https://developers.giphy.com/dashboard/") }
        SectionLabel("Trakt")
        SettingsCard { KeyField(vm, "traktClientId", "Trakt client ID", "Free from trakt.tv/oauth/applications — used for Trakt lists in Collections.", "https://trakt.tv/oauth/applications") }
    }

    if (forgetAsk) PremiumAlert(
        onDismissRequest = { forgetAsk = false },
        title = { Text("Forget AI memory?") },
        text = { Text("The assistant will no longer remember your past requests on this profile. Your votes stay.") },
        confirmButton = { TextButton({ vm.forgetAiMemory(); forgetAsk = false }) { Text("Forget", color = Color(0xFFFF6B6B)) } },
        dismissButton = { TextButton({ forgetAsk = false }) { Text("Cancel") } },
    )
    if (votesAsk) PremiumAlert(
        onDismissRequest = { votesAsk = false },
        title = { Text("Reset your votes?") },
        text = { Text("All Go for it / Skip / Just surfing answers on this profile will be removed.") },
        confirmButton = { TextButton({ vm.resetVotes(); votesAsk = false }) { Text("Reset", color = Color(0xFFFF6B6B)) } },
        dismissButton = { TextButton({ votesAsk = false }) { Text("Cancel") } },
    )
}

@Composable
private fun KeyField(vm: AppViewModel, key: String, label: String, desc: String, link: String) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    var text by remember { mutableStateOf(texts[key] ?: "") }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(desc, fontSize = 13.sp, color = Muted)
        OutlinedTextField(
            text, { text = it.trim() }, Modifier.fillMaxWidth().padding(top = 10.dp).tvOkKeyboard(top = 8.dp), singleLine = true, shape = RoundedCornerShape(12.dp),
            label = { Text(label) }, visualTransformation = PasswordVisualTransformation(),
            trailingIcon = { if (text != (texts[key] ?: "")) TextButton({ vm.setText(key, text) }) { Text("Save") } }, keyboardOptions = tvKeyboard())
        TextButton({ runCatching { uri.openUri(link) } }) { Text("Get a free key", color = Color.White) }
    }
}
