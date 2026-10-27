package com.example.streamnest

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/*
 * Support Xstream: the developer fills in how people can donate (Developer's Corner › Payments & donations: UPI ID,
 * PayPal / Ko-fi links, the monthly goal and what came in) and publishes it to xshared/donate. After an hour with the
 * app open (watching counts) a big bubble asks once, at most every few days: in India with UPI (QR code and the
 * UPI apps), elsewhere with the international links; the goal is shown in rupees or dollars.
 */

@Serializable
data class DonateInfo(
    val on: Boolean = false,
    val upiId: String = "", val upiName: String = "",
    val paypal: String = "", val kofi: String = "", val other: String = "", val otherLabel: String = "",
    val goalInr: Int = 0, val gotInr: Int = 0, val goalUsd: Int = 0, val gotUsd: Int = 0,
    val note: String = "If you enjoy Xstream, a small gift keeps the servers running and new features coming.",
    val everyDays: Int = 4,
    val at: Long = 0,
)

object Donate {
    val info = kotlinx.coroutines.flow.MutableStateFlow(DonateInfo())
    /** Show the bubble now (set by the 1-hour timer, or from My Space). */
    val ask = kotlinx.coroutines.flow.MutableStateFlow(false)

    suspend fun load() = withContext(Dispatchers.IO) {
        runCatching { XNs.read("donate")?.let { Api.json.decodeFromString<DonateInfo>(it) } }.getOrNull()?.let { info.value = it }
    }

    suspend fun publish(d: DonateInfo): String? = withContext(Dispatchers.IO) {
        val t = Cloud.token() ?: return@withContext "Sign in first."
        runCatching {
            Api.http.newCall(Request.Builder().url(XNs.url("donate", t)).put(Api.json.encodeToString(d.copy(at = System.currentTimeMillis())).toRequestBody("application/json".toMediaType())).build())
                .execute().use { r -> if (r.isSuccessful) { info.value = d; null } else if (r.code == 401 || r.code == 403) "Not allowed: add the Firebase rule for xshared/donate (write: owner only, read: everyone)." else "HTTP ${r.code}" }
        }.getOrElse { "Couldn't reach the cloud." }
    }

    /** India: UPI. Elsewhere: international links. */
    fun india(ctx: Context): Boolean {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val c = (runCatching { tm?.simCountryIso }.getOrNull()?.takeIf { it.isNotBlank() } ?: java.util.Locale.getDefault().country).uppercase()
        return c == "IN"
    }

    fun upiUri(d: DonateInfo, amount: Int?): Uri = Uri.parse("upi://pay").buildUpon()
        .appendQueryParameter("pa", d.upiId).appendQueryParameter("pn", d.upiName.ifBlank { "Xstream" })
        .appendQueryParameter("tn", "Support Xstream").appendQueryParameter("cu", "INR")
        .apply { if (amount != null) appendQueryParameter("am", amount.toString()) }.build()

    fun qr(text: String, size: Int = 640): Bitmap? = runCatching { com.journeyapps.barcodescanner.BarcodeEncoder().encodeBitmap(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size) }.getOrNull()

    /** Counts time with the app open; after an hour (and not asked recently) the bubble shows. */
    suspend fun timer(ctx: Context) {
        val p = ctx.getSharedPreferences("donate", Context.MODE_PRIVATE)
        var used = 0L
        while (true) {
            delay(60_000)
            used += 60_000
            val d = info.value
            if (!d.on || BuildConfig.IS_TV) continue
            val last = p.getLong("askedAt", 0L)
            if (used >= 60 * 60_000L && System.currentTimeMillis() - last > d.everyDays.coerceAtLeast(1) * 86_400_000L) {
                p.edit().putLong("askedAt", System.currentTimeMillis()).apply()
                ask.value = true
                used = 0
            }
        }
    }
}

/** The big "Support Xstream" bubble. */
@Composable
fun DonateBubble(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val d by Donate.info.collectAsState()
    val india = remember { Donate.india(ctx) }
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.68f, stiffness = 260f)) }
    var amount by remember { mutableIntStateOf(if (india) 99 else 5) }
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(18.dp).fillMaxWidth().widthIn(max = 460.dp).graphicsLayer { val v = pop.value; scaleX = 0.7f + 0.3f * v; scaleY = scaleX; alpha = v.coerceIn(0f, 1f) }
                .clip(RoundedCornerShape(30.dp)).background(Brush.verticalGradient(listOf(Color(0xFF2A0A10), Color(0xFF120608))))
                .border(1.dp, Color(0x80FF5A64), RoundedCornerShape(30.dp)).verticalScroll(rememberScrollState()).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFFE50914)), Alignment.Center) { Icon(Icons.Default.Favorite, null, tint = Color.White) }
                Text("Support Xstream", Modifier.weight(1f).padding(start = 12.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                IconButton(onClose) { Icon(Icons.Default.Close, "Close") }
            }
            Text(d.note, Modifier.padding(top = 12.dp), fontSize = 15.sp, lineHeight = 21.sp, color = Color(0xFFE9DADC))
            // This month's goal
            val goal = if (india) d.goalInr else d.goalUsd
            val got = if (india) d.gotInr else d.gotUsd
            val cur = if (india) "₹" else "$"
            if (goal > 0) {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Row { Text("This month", Modifier.weight(1f), fontSize = 13.sp, color = Color(0xFFB9A6AB)); Text("$cur$got of $cur$goal", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0x33FFFFFF))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((got.toFloat() / goal).coerceIn(0.02f, 1f)).background(Brush.horizontalGradient(listOf(Color(0xFFFF5A64), Color(0xFFE50914)))))
                    }
                }
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (if (india) listOf(49, 99, 199, 499) else listOf(2, 5, 10, 25)).forEach { a ->
                    Text("$cur$a", Modifier.clip(RoundedCornerShape(50)).background(if (a == amount) Color.White else Color(0x22FFFFFF)).clickable { amount = a }
                        .padding(horizontal = 14.dp, vertical = 8.dp), fontWeight = FontWeight.Bold, color = if (a == amount) Color.Black else Color.White)
                }
            }
            if (india && d.upiId.isNotBlank()) {
                val bmp = remember(d.upiId, amount) { Donate.qr(Donate.upiUri(d, amount).toString()) }
                bmp?.let { Image(it.asImageBitmap(), "UPI QR code", Modifier.padding(top = 16.dp).size(200.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(8.dp)) }
                Text("Scan with any UPI app, or", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = Color(0xFFB9A6AB))
                Button({ runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Donate.upiUri(d, amount)), "Pay with")) }.onFailure { Snack.show("No UPI app found") } },
                    Modifier.padding(top = 8.dp).fillMaxWidth().height(50.dp), shape = RoundedCornerShape(25.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914), contentColor = Color.White)) { Text("Pay ₹$amount with a UPI app", fontWeight = FontWeight.Bold) }
                Text("UPI ID: ${d.upiId}", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Color(0xFFB9A6AB))
            }
            val links = buildList {
                if (d.paypal.isNotBlank()) add("PayPal" to d.paypal)
                if (d.kofi.isNotBlank()) add("Ko-fi" to d.kofi)
                if (d.other.isNotBlank()) add(d.otherLabel.ifBlank { "Other" } to d.other)
            }
            if (links.isNotEmpty()) Text(if (india) "From outside India" else "Ways to give", Modifier.padding(top = 16.dp), fontSize = 13.sp, color = Color(0xFFB9A6AB))
            links.forEach { (label, url) ->
                Button({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                    Modifier.padding(top = 8.dp).fillMaxWidth().height(48.dp), shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (!india) Color(0xFFE50914) else Color(0x33FFFFFF), contentColor = Color.White)) { Text(label, fontWeight = FontWeight.Bold) }
            }
            Text("Maybe later", Modifier.padding(top = 14.dp).clickable { onClose() }.padding(8.dp), fontSize = 14.sp, color = Color(0xFFB9A6AB), textAlign = TextAlign.Center)
        }
    }
}

/** Developer's Corner › Payments & donations. */
@Composable
fun DonatePage(vm: AppViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val cur by Donate.info.collectAsState()
    LaunchedEffect(Unit) { Donate.load() }
    var d by remember(cur.at) { mutableStateOf(cur) }
    var msg by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler { onBack() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(bottom = 120.dp)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Payments & donations", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ask for support", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("After an hour of use, at most every ${d.everyDays} days.", fontSize = 13.sp, color = Color(0xFF9AA3B5))
                }
                Switch(d.on, { d = d.copy(on = it) })
            }
            @Composable fun field(label: String, v: String, set: (String) -> Unit) =
                OutlinedTextField(v, set, Modifier.fillMaxWidth().padding(top = 10.dp), singleLine = true, label = { Text(label) }, shape = RoundedCornerShape(12.dp))
            @Composable fun num(label: String, v: Int, set: (Int) -> Unit) =
                OutlinedTextField(if (v == 0) "" else v.toString(), { set(it.filter(Char::isDigit).take(9).toIntOrNull() ?: 0) }, Modifier.fillMaxWidth().padding(top = 10.dp), singleLine = true, label = { Text(label) }, shape = RoundedCornerShape(12.dp))
            Text("India (UPI)", Modifier.padding(top = 18.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            field("UPI ID (name@bank)", d.upiId) { d = d.copy(upiId = it.trim()) }
            field("Name shown in UPI apps", d.upiName) { d = d.copy(upiName = it) }
            Text("Everywhere else", Modifier.padding(top = 18.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            field("PayPal link (paypal.me/…)", d.paypal) { d = d.copy(paypal = it.trim()) }
            field("Ko-fi / Buy Me a Coffee link", d.kofi) { d = d.copy(kofi = it.trim()) }
            field("Other link", d.other) { d = d.copy(other = it.trim()) }
            field("Other link's name", d.otherLabel) { d = d.copy(otherLabel = it) }
            Text("This month", Modifier.padding(top = 18.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { num("Goal ₹", d.goalInr) { d = d.copy(goalInr = it) } }
                Box(Modifier.weight(1f)) { num("Received ₹", d.gotInr) { d = d.copy(gotInr = it) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { num("Goal $", d.goalUsd) { d = d.copy(goalUsd = it) } }
                Box(Modifier.weight(1f)) { num("Received $", d.gotUsd) { d = d.copy(gotUsd = it) } }
            }
            OutlinedTextField(d.note, { d = d.copy(note = it.take(300)) }, Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text("Message") }, shape = RoundedCornerShape(12.dp))
            num("Ask again after (days)", d.everyDays) { d = d.copy(everyDays = it.coerceIn(1, 60)) }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ msg = "Publishing…"; scope.launch { msg = Donate.publish(d) ?: "Published ✓ — every account sees it on its next start" } },
                    Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(25.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914), contentColor = Color.White)) { Text("Publish", fontWeight = FontWeight.Bold) }
                Button({ Donate.info.value = d; preview = true }, Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(25.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF), contentColor = Color.White)) { Text("Preview") }
            }
            msg?.let { Text(it, Modifier.padding(top = 8.dp), fontSize = 13.sp, color = Color(0xFF9AA3B5)) }
            Text("Card payments need a payment provider account (Razorpay, Stripe); add its payment link as \"Other link\".",
                Modifier.padding(top = 14.dp), fontSize = 12.sp, color = Color(0xFF9AA3B5))
        }
    }
    if (preview) DonateBubble { preview = false }
}
