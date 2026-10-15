package com.example.streamnest

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Addon backups in the Xstream cloud: an export is stored under a code the app makes up (like K7Q-4ZP), valid for 7
 * days; anyone with the code can import it. Needs the Firebase rule for "shares" (read for everyone, write when signed in).
 */
object AddonShare {
    private const val ABC = "ABCDEFGHJKMNPQRSTUVWXYZ23456789" // no 0/O, 1/I/L
    private const val DAYS_MS = 7L * 24 * 3600 * 1000

    fun newCode(): String {
        val r = java.security.SecureRandom()
        val s = (1..6).map { ABC[r.nextInt(ABC.length)] }.joinToString("")
        return s.take(3) + "-" + s.drop(3)
    }

    private fun key(code: String) = code.uppercase().filter { it.isLetterOrDigit() }

    /** Uploads the backup; returns the code, or null with [error] set. */
    suspend fun upload(json: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val t = Cloud.token() ?: return@withContext null to "Sign in to Xstream (Settings › Account) to send to the cloud."
        val code = newCode()
        val body = buildJsonObject { put("data", json); put("at", System.currentTimeMillis()); put("by", Cloud.user.value?.uid ?: "") }
        val ok = runCatching {
            Api.http.newCall(Request.Builder().url("${CloudConfig.DB_URL}/shares/${key(code)}.json?auth=$t")
                .put(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        if (ok) code to null else null to "The cloud didn't accept it. The Firebase rule for sharing may not be set yet — use Save to phone meanwhile."
    }

    /** Downloads a backup by its code; (json, error). */
    suspend fun download(code: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val k = key(code)
        if (k.length < 6) return@withContext null to "Type the 6-letter code (like K7Q-4ZP)."
        val auth = Cloud.token()?.let { "?auth=$it" } ?: ""
        val txt = runCatching { Api.get("${CloudConfig.DB_URL}/shares/$k.json$auth") }.getOrNull()
            ?: return@withContext null to "Couldn't reach the cloud. Check your connection."
        val o = runCatching { Api.json.parseToJsonElement(txt).jsonObject }.getOrNull() ?: return@withContext null to "No backup with that code."
        val at = o["at"]?.jsonPrimitive?.longOrNull ?: 0L
        if (System.currentTimeMillis() - at > DAYS_MS) return@withContext null to "That code has expired (codes last 7 days)."
        (o["data"]?.jsonPrimitive?.contentOrNull ?: return@withContext null to "No backup with that code.") to null
    }
}

/** Settings › Addons › Backup & move: Export (save to phone / cloud code) and Import (file / cloud code). */
@Composable
fun AddonBackupCard(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<String?>(null) } // export, code, import
    var code by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<String?>(null) } // backup to import
    var codeIn by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf(true) }
    var fromCloud by remember { mutableStateOf(true) }
    var withAccounts by remember { mutableStateOf(true) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(vm.exportAddons(withAccounts).toByteArray()) } }
            .onSuccess { sheet = null; Snack.show("Saved · Xstream-addons.json") }.onFailure { error = "Couldn't save the file." }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val txt = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            if (txt != null && vm.describeBackup(txt) != null) { pending = txt; error = null } else error = "That file isn't a Xstream addon backup."
        }
    }
    fun copy(text: String) {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Xstream code", text))
        Snack.show("Code copied")
    }

    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(Triple("export", "Export", "Save to phone, or send to the cloud with a code"), Triple("import", "Import", "From a file or a cloud code")).forEach { (k, t, d) ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Color(0xFF16161B)).border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(18.dp))
                .clickable { error = null; pending = null; code = null; sheet = k }.padding(16.dp)) {
                Text(t, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(d, fontSize = 12.sp, color = Color(0xFF8A94A8), modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    if (sheet != null) AlertDialog(
        onDismissRequest = { if (!busy) sheet = null },
        containerColor = Color(0xFF121318),
        title = { Text(when (sheet) { "export" -> "Export addons"; "code" -> "Your code"; else -> "Import addons" }, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (sheet) {
                    "export" -> {
                        Text("Your addons, their order and pins, Quick play addons and order, and your language, quality and filter settings.", fontSize = 13.sp, color = Color(0xFFC9CED9))
                        Row(Modifier.fillMaxWidth().clickable { withAccounts = !withAccounts }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Include my quick server account", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(if (withAccounts) "They play on your account" else "Left out — they can connect their own", fontSize = 12.sp, color = Color(0xFF8A94A8))
                            }
                            Switch(withAccounts, { withAccounts = it })
                        }
                        if (withAccounts) Text("Whoever imports this uses your accounts — share only with people you trust.",
                            Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x14FFB43C)).padding(10.dp), fontSize = 12.sp, color = Color(0xFFFFD28A))
                    }
                    "code" -> {
                        Text("Type this in Import › From cloud on the other phone. Valid for 7 days.", fontSize = 13.sp, color = Color(0xFFC9CED9))
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x29E50914)).border(1.dp, Color(0x99E50914), RoundedCornerShape(16.dp)).padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(code.orEmpty(), Modifier.weight(1f), fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 4.sp, textAlign = TextAlign.Center)
                            TextButton({ code?.let { copy(it) } }) { Text("Copy") }
                        }
                    }
                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(fromCloud, { fromCloud = true; pending = null; error = null }, { Text("From cloud") })
                            FilterChip(!fromCloud, { fromCloud = false; pending = null; error = null }, { Text("From file") })
                        }
                        if (fromCloud) OutlinedTextField(codeIn, { codeIn = it.uppercase().take(8) }, Modifier.fillMaxWidth().tvOkKeyboard(), singleLine = true,
                            placeholder = { Text("K7Q-4ZP") }, label = { Text("Code") }, keyboardOptions = tvKeyboard())
                        else OutlinedButton({ pick.launch(arrayOf("application/json", "text/plain", "*/*")) }, Modifier.fillMaxWidth()) { Text("Pick Xstream-addons.json") }
                        pending?.let { p ->
                            Text("Found: " + (vm.describeBackup(p) ?: ""), fontSize = 13.sp, color = Color(0xFFD5D8DF),
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF1A1B21)).padding(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(replace, { replace = true }); Text("Replace — your addons become exactly this", fontSize = 13.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(!replace, { replace = false }); Text("Merge — add the new ones, keep yours", fontSize = 13.sp)
                            }
                        }
                    }
                }
                error?.let { Text(it, fontSize = 12.sp, color = Color(0xFFFF6B74)) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color(0xFFE50914))
            }
        },
        confirmButton = {
            when (sheet) {
                "export" -> Button({
                    busy = true; error = null
                    scope.launch {
                        val (c, e) = AddonShare.upload(vm.exportAddons(withAccounts))
                        busy = false
                        if (c != null) { code = c; sheet = "code" } else error = e
                    }
                }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))) { Text("Send to cloud · get a code") }
                "code" -> Button({ code?.let { copy(it) } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))) { Text("Copy code") }
                else -> if (pending == null) Button({
                    if (fromCloud) { busy = true; error = null; scope.launch {
                        val (j, e) = AddonShare.download(codeIn)
                        busy = false
                        if (j != null && vm.describeBackup(j) != null) pending = j else error = e ?: "That isn't a Xstream addon backup."
                    } }
                }, enabled = !busy && fromCloud && codeIn.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))) { Text("Find") }
                else Button({
                    val j = pending ?: return@Button
                    busy = true
                    scope.launch {
                        val msg = vm.importAddons(j, replace)
                        busy = false; sheet = null; pending = null
                        Snack.show(msg)
                    }
                }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))) { Text(if (replace) "Import" else "Merge") }
            }
        },
        dismissButton = {
            when (sheet) {
                "export" -> TextButton({ save.launch("Xstream-addons.json") }, enabled = !busy) { Text("Save to phone") }
                "code" -> TextButton({ sheet = null }) { Text("Done") }
                else -> TextButton({ sheet = null }, enabled = !busy) { Text("Cancel") }
            }
        },
    )
}
