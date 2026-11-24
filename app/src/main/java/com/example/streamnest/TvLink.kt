package com.example.streamnest

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
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
 * StreamX TV 0.8.6 — sign in on the TV with your phone (QR or 6-digit code), no typing.
 *
 *  TV:    makes a short-lived link account, puts "TV<code>" → its id on the cloud, shows the code and a QR, and waits.
 *  Phone: Settings › Account › Link a TV → scan / type the code → Approve: the phone hands its sign-in to that TV
 *         (through the TV's own inbox, which only that TV can read).
 *  TV:    signs in with it, then deletes the link account and the code. Codes are renewed every 10 minutes.
 */
object TvLink {
    /** [code] = the 6-digit code, or null when the cloud doesn't take codes (then the QR alone links the TV). */
    class Session(val code: String?, val uid: String, val token: String, val at: Long) {
        /** What the QR holds: the TV's link id itself, so the phone needs no lookup. */
        val qr: String get() = "streamx://tvlink/u/$uid" + (code?.let { "/c/$it" } ?: "")
    }

    private val JSONT = "application/json".toMediaType()
    private fun key(code: String) = "TV" + code.filter { it.isDigit() }

    /** TV: a new code. */
    suspend fun open(): Session = withContext(Dispatchers.IO) {
        val (uid, tok) = Cloud.linkAccount()
        val code = (100000 + java.security.SecureRandom().nextInt(900000)).toString()
        val body = buildJsonObject { put("data", "tvlink:$uid"); put("at", System.currentTimeMillis()); put("by", uid) }
        // 0.8.8: a new (not yet verified) account may not be allowed to save the 6-digit code; the QR then carries the
        // TV's id itself and works without it.
        val ok = runCatching { Api.http.newCall(Request.Builder().url("${CloudConfig.DB_URL}/shares/${key(code)}.json?auth=$tok")
            .put(body.toString().toRequestBody(JSONT)).build()).execute().use { r -> if (!r.isSuccessful) ActLog.add("SYNC", "TV link: code not saved (HTTP ${r.code}) — QR only"); r.isSuccessful } }.getOrDefault(false)
        ActLog.add("SYNC", "TV link: ${if (ok) "QR and code" else "QR"} shown")
        Session(if (ok) code else null, uid, tok, System.currentTimeMillis())
    }

    /** TV: the sign-in a phone approved for this code, or null (not yet). */
    suspend fun approved(s: Session): String? = withContext(Dispatchers.IO) {
        val txt = runCatching { Api.get("${CloudConfig.DB_URL}/invites/${s.uid}.json?auth=${s.token}") }.getOrNull() ?: return@withContext null
        if (txt.trim() == "null") return@withContext null
        val all = runCatching { Api.json.decodeFromString<Map<String, Invite>>(txt) }.getOrDefault(emptyMap())
        all.values.firstOrNull { it.title == "tvlink" && it.code.isNotBlank() }?.code
    }

    /** TV: removes the code, the inbox and the link account. */
    suspend fun close(s: Session) = withContext(Dispatchers.IO) {
        s.code?.let { c -> runCatching { Api.http.newCall(Request.Builder().url("${CloudConfig.DB_URL}/shares/${key(c)}.json?auth=${s.token}").delete().build()).execute().close() } }
        runCatching { Api.http.newCall(Request.Builder().url("${CloudConfig.DB_URL}/invites/${s.uid}.json?auth=${s.token}").delete().build()).execute().close() }
        runCatching { Cloud.dropLinkAccount(s.token) }
    }

    /** Phone: the TV waiting with [code] (its link id), or an error text. */
    suspend fun findTv(code: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val k = key(code)
        if (k.length != 8) return@withContext null to "Type the 6-digit code shown on the TV."
        val t = Cloud.token() ?: return@withContext null to "Sign in on this phone first."
        val txt = runCatching { Api.get("${CloudConfig.DB_URL}/shares/$k.json?auth=$t") }.getOrNull() ?: return@withContext null to "Couldn't reach the cloud."
        val o = runCatching { Api.json.parseToJsonElement(txt).jsonObject }.getOrNull() ?: return@withContext null to "No TV is showing that code."
        val at = o["at"]?.jsonPrimitive?.longOrNull ?: 0L
        val data = o["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!data.startsWith("tvlink:") || System.currentTimeMillis() - at > 15 * 60_000L) return@withContext null to "That code has expired — the TV shows a new one."
        data.removePrefix("tvlink:") to null
    }

    /** Phone: hands this phone's sign-in to the TV; null = done, else an error text. */
    suspend fun approve(tvUid: String): String? = withContext(Dispatchers.IO) {
        val t = Cloud.token() ?: return@withContext "Sign in on this phone first."
        val rt = Cloud.handoverToken() ?: return@withContext "Sign in on this phone first."
        val u = Cloud.user.value ?: return@withContext "Sign in on this phone first."
        val inv = Invite(u.uid, u.email, "tvlink", rt, System.currentTimeMillis())
        runCatching {
            Api.http.newCall(Request.Builder().url("${CloudConfig.DB_URL}/invites/$tvUid.json?auth=$t")
                .post(Api.json.encodeToString(inv).toRequestBody(JSONT)).build()).execute().use { r -> if (r.isSuccessful) null else "The cloud said no (HTTP ${r.code})" }
        }.getOrElse { "Couldn't reach the cloud." }.also { ActLog.add("SYNC", if (it == null) "Linked a TV" else "Link a TV failed: $it") }
    }

    fun qr(text: String, px: Int): Bitmap {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, px, px,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565)
        val row = IntArray(m.width)
        for (y in 0 until m.height) { for (x in 0 until m.width) row[x] = if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(); bmp.setPixels(row, 0, m.width, 0, y, m.width, 1) }
        return bmp
    }

    fun digitsOf(scanned: String): String = Regex("/c/(\\d{6})").find(scanned)?.groupValues?.get(1)
        ?: Regex("^\\D*(\\d{6})\\D*$").find(scanned)?.groupValues?.get(1) ?: scanned.filter { it.isDigit() }.take(6)

    /** The TV's link id inside a scanned QR (streamx://tvlink/u/<id>), if it's there. */
    fun uidOf(scanned: String): String? = Regex("tvlink/u/([A-Za-z0-9]{20,40})").find(scanned)?.groupValues?.get(1)
}

/** TV first screen: sign in with your phone (QR / code), or with email, or skip. */
@Composable
fun TvLinkGate(onDone: () -> Unit, onEmail: () -> Unit, allowSkip: Boolean = true) {
    var session by remember { mutableStateOf<TvLink.Session?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var signing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val firstFr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        while (isActive) {
            val s = runCatching { TvLink.open() }.onFailure { e ->
                err = "Couldn't make a code — check the internet. Retrying…"
                ActLog.add("SYNC", "TV link failed: ${e.javaClass.simpleName}: ${e.message}".take(200))
            }.getOrNull()
            if (s == null) { delay(8_000); continue }
            err = null; session = s
            var rt: String? = null
            while (isActive && rt == null && System.currentTimeMillis() - s.at < 10 * 60_000L) { delay(2_000); rt = runCatching { TvLink.approved(s) }.getOrNull() }
            if (rt != null) {
                signing = true
                val ok = runCatching { Cloud.signInWithHandover(rt) }.onFailure { err = it.message ?: "Couldn't sign in" }.isSuccess
                runCatching { TvLink.close(s) }
                if (ok) { ActLog.add("SYNC", "TV signed in with the phone"); onDone(); return@LaunchedEffect }
                signing = false
            } else runCatching { TvLink.close(s) }
        }
    }
    DisposableEffect(Unit) { onDispose { session?.let { s -> kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { runCatching { TvLink.close(s) } } } } }
    LaunchedEffect(Unit) { repeat(6) { if (runCatching { firstFr.requestFocus() }.isSuccess) return@LaunchedEffect; delay(80) } }
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B10)), Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(56.dp)) {
            Column(Modifier.width(470.dp)) {
                Text("Sign in to ${Brand.NAME}", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Text("Use your phone — no typing on the TV.", Modifier.padding(top = 6.dp), fontSize = 16.sp, color = Color(0xFFBDB7C3))
                Column(Modifier.padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("Open ${Brand.NAME} on your phone", "Settings › Account › Link a TV", if (session?.code != null) "Scan this QR code, or type the code" else "Scan this QR code", "Tap Approve").forEachIndexed { i, s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFE50914)), Alignment.Center) { Text("${i + 1}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                            Text(s, Modifier.padding(start = 12.dp), fontSize = 17.sp, color = Color.White)
                        }
                    }
                }
                Row(Modifier.padding(top = 30.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TxButton(Modifier.focusRequester(firstFr), onClick = onEmail) { _ -> Text("Sign in with email", Modifier.padding(horizontal = 10.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
                    if (allowSkip) TxButton(onClick = { Cloud.skip(); onDone() }) { _ -> Text("Skip for now", Modifier.padding(horizontal = 10.dp), fontSize = 15.sp, color = Color(0xFFBDB7C3)) }
                }
                err?.let { Text(it, Modifier.padding(top = 14.dp), fontSize = 14.sp, color = Color(0xFFFF8A80)) }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val s = session
                Box(Modifier.size(280.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(18.dp)), Alignment.Center) {
                    if (s != null && !signing) {
                        val bmp = remember(s.uid) { runCatching { TvLink.qr(s.qr, 512).asImageBitmap() }.getOrNull() }
                        if (bmp != null) Image(bmp, "QR code", Modifier.size(250.dp), filterQuality = FilterQuality.None)
                    } else androidx.compose.material3.CircularProgressIndicator(Modifier.size(56.dp), color = Color(0xFFE50914))
                }
                when {
                    signing -> Text("Signing in…", Modifier.padding(top = 18.dp), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    s?.code != null -> Text(s.code.take(3) + " " + s.code.drop(3), Modifier.padding(top = 18.dp),
                        fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp, color = Color.White)
                    s != null -> Text("Scan with ${Brand.NAME} on your phone", Modifier.padding(top = 18.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    else -> Text("…", Modifier.padding(top = 18.dp), fontSize = 28.sp, color = Color.White)
                }
                Text("The code changes every 10 minutes", Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Color(0xFF8E8A96))
            }
        }
    }
}

/** Phone: Settings › Account › Link a TV (scan or type the TV's code, then Approve). */
@Composable
fun LinkTvDialog(onClose: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var tvUid by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun look(c: String) { if (busy) return; busy = true; msg = null; scope.launch { val (u, e) = TvLink.findTv(c); tvUid = u; msg = e; busy = false } }
    val scan = rememberLauncherForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { r ->
        r.contents?.let { txt ->
            val u = TvLink.uidOf(txt)
            if (u != null) { tvUid = u; msg = null; code = TvLink.digitsOf(txt).takeIf { c -> c.length == 6 }.orEmpty() }
            else { code = TvLink.digitsOf(txt); if (code.length == 6) look(code) }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Color(0xFF1A1720),
        title = { Text(if (done) "TV linked" else "Link a TV", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                when {
                    done -> Text("Your TV is signing in to this account now.", color = Color(0xFFBDB7C3))
                    tvUid != null -> Text(if (code.length == 6) "A TV is waiting with code ${code.take(3)} ${code.drop(3)}. Approve to sign it in to your account."
                        else "Your TV is waiting. Approve to sign it in to your account.", color = Color(0xFFBDB7C3))
                    else -> {
                        Text("On the TV, ${Brand.NAME} shows a QR code and a 6-digit code.", color = Color(0xFFBDB7C3))
                        OutlinedTextField(code, { v -> code = v.filter { it.isDigit() }.take(6); if (code.length == 6) look(code) }, Modifier.padding(top = 12.dp).fillMaxWidth(),
                            label = { Text("6-digit code") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        TextButton({ scan.launch(com.journeyapps.barcodescanner.ScanOptions().setPrompt("Scan the code on your TV").setBeepEnabled(false).setOrientationLocked(true).setCaptureActivity(PortraitScanActivity::class.java)) }) { Text("Scan QR code") }
                    }
                }
                msg?.let { Text(it, Modifier.padding(top = 8.dp), color = Color(0xFFFF8A80), fontSize = 13.sp) }
            }
        },
        confirmButton = {
            if (done) TextButton(onClose) { Text("Done") }
            else if (tvUid != null) TextButton({
                if (busy) return@TextButton
                busy = true
                scope.launch { val e = TvLink.approve(tvUid!!); busy = false; if (e == null) done = true else msg = e }
            }) { Text(if (busy) "Approving…" else "Approve", color = Color(0xFFFF4D57), fontWeight = FontWeight.Bold) }
        },
        dismissButton = { if (!done) TextButton(onClose) { Text("Cancel", color = Color.White) } },
    )
}
