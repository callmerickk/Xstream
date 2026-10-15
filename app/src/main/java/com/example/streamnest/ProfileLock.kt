package com.example.streamnest

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Profile lock: any profile can have a PIN (kept only on this device, stored as a hash). Opening a locked profile asks
 * for the PIN — or your fingerprint, when "Fingerprint unlock" is on in Developer's Corner.
 */
object ProfileLock {
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) { if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("profile_locks", Context.MODE_PRIVATE) }

    private fun hash(id: String, pin: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest("streamx:$id:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun hasPin(id: String): Boolean = prefs?.contains("pin_$id") == true
    fun setPin(id: String, pin: String) { prefs?.edit()?.putString("pin_$id", hash(id, pin))?.apply() }
    fun clear(id: String) { prefs?.edit()?.remove("pin_$id")?.apply() }
    fun check(id: String, pin: String): Boolean = prefs?.getString("pin_$id", null) == hash(id, pin)

    var fingerprint: Boolean
        get() = prefs?.getBoolean("fingerprint", false) == true
        set(v) { prefs?.edit()?.putBoolean("fingerprint", v)?.apply() }

    /** Open Developer's Corner with a fingerprint (password still works). On by default. */
    var devFingerprint: Boolean
        get() = prefs?.getBoolean("devFingerprint", true) != false
        set(v) { prefs?.edit()?.putBoolean("devFingerprint", v)?.apply() }

    class Ask(val profile: Profile, val onOk: () -> Unit)
    val pending = MutableStateFlow<Ask?>(null)

    /** Runs [onOk] at once for an unlocked profile; a locked one asks for its PIN / fingerprint first. */
    fun ask(p: Profile, onOk: () -> Unit) {
        if (!hasPin(p.id)) onOk() else pending.value = Ask(p, onOk)
    }

    /** Can this phone check a fingerprint (or face) right now? */
    fun biometricReady(ctx: Context): Boolean = runCatching {
        androidx.biometric.BiometricManager.from(ctx).canAuthenticate(
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK,
        ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)

    fun askBiometric(ctx: Context, title: String, negative: String = "Use PIN", onOk: () -> Unit) {
        val act = ctx as? androidx.fragment.app.FragmentActivity ?: return
        runCatching {
            val prompt = androidx.biometric.BiometricPrompt(act, androidx.core.content.ContextCompat.getMainExecutor(act),
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) { onOk() }
                })
            prompt.authenticate(
                androidx.biometric.BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle("Use your fingerprint")
                    .setNegativeButtonText(negative)
                    .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    .build(),
            )
        }
    }
}

/** The PIN pad shown when a locked profile is opened (placed once, over the whole app). */
@Composable
fun ProfileLockDialog() {
    val ask by ProfileLock.pending.collectAsStateWithLifecycle()
    val a = ask ?: return
    val ctx = LocalContext.current
    var pin by remember(a) { mutableStateOf("") }
    var wrong by remember(a) { mutableIntStateOf(0) }
    val shake = remember { Animatable(0f) }
    fun done() { ProfileLock.pending.value = null; a.onOk() }
    val bio = ProfileLock.fingerprint && ProfileLock.biometricReady(ctx)
    LaunchedEffect(a) { if (bio) ProfileLock.askBiometric(ctx, "Open ${a.profile.name}") { done() } }
    LaunchedEffect(pin) {
        if (pin.length < 4) return@LaunchedEffect
        if (ProfileLock.check(a.profile.id, pin)) { done(); return@LaunchedEffect }
        // Longer PINs (5–6 digits): wait a moment for more digits before calling it wrong.
        delay(if (pin.length < 6) 700 else 150)
        if (ProfileLock.check(a.profile.id, pin)) return@LaunchedEffect
        wrong++
        pin = ""
        for (x in listOf(-14f, 14f, -9f, 9f, -4f, 0f)) shake.animateTo(x, tween(55))
    }
    PremiumAlert(
        onDismissRequest = { ProfileLock.pending.value = null },
        confirmButton = { TextButton({ ProfileLock.pending.value = null }) { Text("Cancel") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("${a.profile.name} is locked")
            }
        },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (wrong > 0) "Wrong PIN — try again" else "Enter the PIN", color = if (wrong > 0) Color(0xFFFF8A80) else Color(0xFFC9CDD6), fontSize = 14.sp)
                Row(Modifier.padding(vertical = 18.dp).graphicsLayer { translationX = shake.value * density }, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    repeat(maxOf(4, pin.length)) { i ->
                        Box(Modifier.size(14.dp).clip(CircleShape).background(if (i < pin.length) Color.White else Color.Transparent).border(2.dp, Color.White, CircleShape))
                    }
                }
                val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", if (bio) "bio" else "", "0", "del")
                keys.chunked(3).forEach { row ->
                    Row(Modifier.padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { k ->
                            Box(
                                Modifier.size(62.dp).clip(CircleShape).background(if (k.isEmpty()) Color.Transparent else Color(0x1FFFFFFF))
                                    .then(if (k.isEmpty()) Modifier else Modifier.clickable {
                                        when (k) {
                                            "del" -> pin = pin.dropLast(1)
                                            "bio" -> ProfileLock.askBiometric(ctx, "Open ${a.profile.name}") { done() }
                                            else -> if (pin.length < 6) pin += k
                                        }
                                    }),
                                Alignment.Center,
                            ) {
                                when (k) {
                                    "del" -> Icon(Icons.Default.Backspace, "Delete", Modifier.size(22.dp))
                                    "bio" -> Icon(Icons.Default.Fingerprint, "Fingerprint", Modifier.size(28.dp))
                                    else -> Text(k, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

/** In the profile editor: set, change or remove this profile's PIN (changing / removing asks for the current PIN). */
@Composable
fun ProfileLockRow(p: Profile) {
    var has by remember(p.id) { mutableStateOf(ProfileLock.hasPin(p.id)) }
    var setting by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Profile lock", fontWeight = FontWeight.Medium)
            Text(if (has) "On — a PIN is needed to open this profile." else "Off — anyone can open this profile.", fontSize = 12.sp, color = Color(0xFF9AA3B5))
        }
        if (has) TextButton({ ProfileLock.ask(p) { ProfileLock.clear(p.id); has = false } }) { Text("Remove") }
        TextButton({ if (has) ProfileLock.ask(p) { setting = true } else setting = true }) { Text(if (has) "Change" else "Set PIN") }
    }
    if (setting) {
        var first by remember { mutableStateOf("") }
        var pin by remember { mutableStateOf("") }
        val confirming = first.isNotEmpty()
        PremiumAlert(
            onDismissRequest = { setting = false },
            title = { Text(if (confirming) "Enter it again" else "New PIN for ${p.name}") },
            text = {
                Column {
                    Text("4 to 6 digits.", fontSize = 13.sp, color = Color(0xFF9AA3B5))
                    androidx.compose.material3.OutlinedTextField(
                        pin, { v -> pin = v.filter { it.isDigit() }.take(6) }, Modifier.fillMaxWidth().padding(top = 12.dp), singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    )
                    if (confirming && pin.length >= 4 && pin != first && pin.length >= first.length) Text("The PINs don't match.", Modifier.padding(top = 8.dp), color = Color(0xFFFF8A80), fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton({
                    if (!confirming) { first = pin; pin = "" }
                    else if (pin == first) { ProfileLock.setPin(p.id, pin); has = true; setting = false; Snack.show("${p.name} is now locked") }
                }, enabled = pin.length >= 4) { Text(if (confirming) "Lock profile" else "Next") }
            },
            dismissButton = { TextButton({ setting = false }) { Text("Cancel") } },
        )
    }
}
