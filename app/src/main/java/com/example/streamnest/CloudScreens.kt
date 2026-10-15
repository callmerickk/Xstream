package com.example.streamnest

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Phone only: the Google account picker → a Google ID token for Firebase. */
private suspend fun googleIdToken(activity: Activity): String {
    val opt = GetSignInWithGoogleOption.Builder(CloudConfig.WEB_CLIENT_ID).build()
    val req = GetCredentialRequest.Builder().addCredentialOption(opt).build()
    val res = try {
        CredentialManager.create(activity).getCredential(activity, req)
    } catch (e: GetCredentialCancellationException) {
        throw Cloud.CloudError("Cancelled")
    } catch (e: GetCredentialException) {
        throw Cloud.CloudError("Google sign-in isn't available: ${e.message ?: e.type}")
    }
    val c = res.credential
    if (c is CustomCredential && c.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        return GoogleIdTokenCredential.createFrom(c.data).idToken
    }
    throw Cloud.CloudError("Google sign-in didn't return an account")
}

// ───────────── Sign-in gateway (first start, and from Account) ─────────────

/**
 * Full-screen "Sign in to Xstream". Sign in / Create account (email + password, verified email required for sync),
 * Continue with Google (phone), Forgot password, and Skip for now. Works with touch and with the TV remote.
 */
@Composable
fun CloudGate(onDone: () -> Unit, allowSkip: Boolean = true) {
    val user by Cloud.user.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(if (user != null && user?.verified == false) "verify" else "signin") }
    var email by remember { mutableStateOf(user?.email ?: "") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current

    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; note = null
        focus.clearFocus()
        scope.launch {
            try { block() } catch (e: Cloud.CloudError) { if (e.message != "Cancelled") error = e.message }
            catch (e: Exception) { error = "Couldn't reach the server. Check your internet and try again." }
            busy = false
        }
    }

    BackHandler(enabled = mode != "signin" && mode != "verify") { mode = "signin"; error = null; note = null }
    // TV: start on the Email field (it only opens the keyboard when OK is pressed on it).
    val mainFr = remember { FocusRequester() }
    LaunchedEffect(mode) {
        if (!BuildConfig.IS_TV) return@LaunchedEffect
        withFrameNanos { }
        repeat(4) { if (mainFr.tryFocus()) return@LaunchedEffect; delay(60) }
    }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF17080E), Color(0xFF0B0B10), Color(0xFF07080C)))),
        Alignment.TopCenter,
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = if (BuildConfig.IS_TV) 26.dp else 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(AppLook.logoRes(AppLook.logo.value)), null, Modifier.size(40.dp))
                Spacer(Modifier.width(10.dp))
                Wordmark("Xstream", 28.sp)
            }
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = 22.dp)) {
                val title = when (mode) {
                    "signup" -> "Create your account"
                    "reset" -> "Reset your password"
                    "verify" -> "Verify your email"
                    else -> "Sign in"
                }
                Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    when (mode) {
                        "verify" -> "We sent a link to ${user?.email ?: email}. Open it, then come back and press \"I've verified\". Check your spam folder too."
                        "reset" -> "Enter your email and we'll send you a link to set a new password."
                        else -> "Your profiles, addons, settings and watch progress follow you to every phone and TV — each profile keeps its own."
                    },
                    Modifier.padding(top = 6.dp), fontSize = 14.sp, lineHeight = 20.sp, color = Muted,
                )
                Spacer(Modifier.height(20.dp))

                when (mode) {
                    "verify" -> {
                        GateButton("I've verified", primary = true, busy = busy, fr = mainFr) {
                            act { if (Cloud.recheckVerified()) onDone() else error = "Not verified yet. Open the link in the email first." }
                        }
                        Spacer(Modifier.height(10.dp))
                        GateButton("Resend email") { act { Cloud.sendVerification(); note = "Sent again. It can take a minute." } }
                        Spacer(Modifier.height(10.dp))
                        GateButton("Continue — sync starts once verified") { onDone() }
                        Spacer(Modifier.height(6.dp))
                        GateLink("Use a different account") { Cloud.signOut(); mode = "signin" }
                    }
                    "reset" -> {
                        GateField(email, { email = it }, "Email", KeyboardType.Email, ImeAction.Done, fr = mainFr) { act { Cloud.sendPasswordReset(email); note = "Reset link sent to $email." } }
                        Spacer(Modifier.height(14.dp))
                        GateButton("Send reset link", primary = true, busy = busy) { act { Cloud.sendPasswordReset(email); note = "Reset link sent to $email." } }
                        GateLink("Back to sign in") { mode = "signin"; error = null; note = null }
                    }
                    else -> {
                        val signup = mode == "signup"
                        GateField(email, { email = it }, "Email", KeyboardType.Email, ImeAction.Next, fr = mainFr) { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }
                        Spacer(Modifier.height(12.dp))
                        GateField(pw, { pw = it }, if (signup) "Password (8+ characters)" else "Password", KeyboardType.Password,
                            if (signup) ImeAction.Next else ImeAction.Done, password = true) {
                            if (signup) focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
                            else act { Cloud.signIn(email, pw); onDone() }
                        }
                        if (signup) {
                            Spacer(Modifier.height(12.dp))
                            GateField(pw2, { pw2 = it }, "Confirm password", KeyboardType.Password, ImeAction.Done, password = true) {}
                        }
                        Spacer(Modifier.height(16.dp))
                        if (signup) GateButton("Create account", primary = true, busy = busy) {
                            if (pw != pw2) { error = "The passwords don't match."; return@GateButton }
                            act { Cloud.signUp(email, pw); mode = "verify" }
                        } else GateButton("Sign in", primary = true, busy = busy) {
                            act {
                                Cloud.signIn(email, pw)
                                if (Cloud.user.value?.verified == false) mode = "verify" else onDone()
                            }
                        }
                        if (!BuildConfig.IS_TV) {
                            Spacer(Modifier.height(10.dp))
                            GateButton("Continue with Google", google = true) {
                                val activity = ctx.findActivity()
                                if (activity == null) error = "Google sign-in isn't available here." else act { Cloud.signInWithGoogle(googleIdToken(activity)); onDone() }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        if (signup) GateLink("Already have an account? Sign in") { mode = "signin"; error = null }
                        else {
                            GateLink("New to Xstream? Create an account") { mode = "signup"; error = null }
                            GateLink("Forgot password?") { mode = "reset"; error = null }
                        }
                        if (BuildConfig.IS_TV) Text(
                            "Signed up with Google on your phone? Set a password there first: Settings › Account › Set a password for TV.",
                            Modifier.padding(top = 8.dp), fontSize = 12.sp, color = Color(0xFF7C8394),
                        )
                    }
                }
                error?.let { Text(it, Modifier.padding(top = 14.dp), color = Color(0xFFFF5A5F), fontSize = 14.sp) }
                note?.let { Text(it, Modifier.padding(top = 14.dp), color = Color(0xFF7BD88F), fontSize = 14.sp) }
                if (allowSkip && mode != "verify") {
                    Spacer(Modifier.height(18.dp))
                    GateLink("Skip for now — use Xstream without an account", center = true) { Cloud.skip(); onDone() }
                }
            }
        }
    }
}

@Composable
private fun GateField(
    value: String, onChange: (String) -> Unit, hint: String, type: KeyboardType, ime: ImeAction,
    password: Boolean = false, fr: FocusRequester? = null, onIme: () -> Unit,
) {
    val tv = BuildConfig.IS_TV
    var focused by remember { mutableStateOf(false) }
    var rowFocused by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    // TV: the field is a button until OK is pressed on it — only then does the keyboard open.
    var active by remember { mutableStateOf(!tv) }
    val tfFr = remember { FocusRequester() }
    LaunchedEffect(active) { if (tv && active) { withFrameNanos { }; repeat(4) { if (tfFr.tryFocus()) return@LaunchedEffect; delay(50) } } }
    val shape = RoundedCornerShape(14.dp)
    val lit = focused || rowFocused
    Row(
        Modifier.fillMaxWidth().height(54.dp)
            .then(if (tv && fr != null) Modifier.focusRequester(fr) else Modifier)
            .then(if (tv) Modifier.onFocusChanged { rowFocused = it.isFocused }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { active = true } else Modifier)
            .clip(shape).background(Color(0xFF17181F))
            .border(if (lit) 2.dp else 1.dp, if (lit) Color.White else Color(0x22FFFFFF), shape).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(if (tv && rowFocused && !active) "$hint — press OK to type" else hint, fontSize = 16.sp, color = Muted)
            BasicTextField(
                value, onChange,
                Modifier.fillMaxWidth().focusRequester(tfFr)
                    .focusProperties { canFocus = active }
                    .onFocusChanged { focused = it.isFocused; if (tv && !it.isFocused && active) active = false }.tvOkKeyboard(),
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(BrandRed),
                visualTransformation = if (password && !show) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = tvKeyboard(KeyboardOptions(keyboardType = type, imeAction = ime)),
                keyboardActions = KeyboardActions(onNext = { onIme() }, onDone = { onIme() }),
            )
        }
        if (password && !tv) Icon(
            if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (show) "Hide password" else "Show password",
            Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).clickable { show = !show }, tint = Muted,
        )
    }
}

@Composable
private fun GateButton(label: String, primary: Boolean = false, google: Boolean = false, busy: Boolean = false, fr: FocusRequester? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(27.dp)
    val bg = when {
        primary -> Brush.verticalGradient(listOf(Color(0xFFF0242E), Color(0xFFD10A14), Color(0xFFA8060E)))
        google -> SolidColor(Color.White)
        focused -> SolidColor(Color(0x33FFFFFF))
        else -> SolidColor(Color(0x1AFFFFFF))
    }
    Box(
        Modifier.fillMaxWidth().height(52.dp)
            .then(if (focused) Modifier.border(2.dp, if (google) BrandRed else Color.White, shape) else Modifier)
            .padding(if (focused) 3.dp else 0.dp)
            .clip(shape).background(bg)
            .then(if (primary) Modifier.border(2.dp, Color.Black, shape) else Modifier)
            .then(if (fr != null) Modifier.focusRequester(fr) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (!busy) onClick() },
        Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        else Row(verticalAlignment = Alignment.CenterVertically) {
            if (google) {
                Text("G", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4285F4))
                Spacer(Modifier.width(10.dp))
            }
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (google) Color(0xFF1F1F1F) else Color.White)
        }
    }
}

@Composable
private fun GateLink(text: String, center: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text,
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color(0x26FFFFFF) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        fontSize = 14.sp, color = if (focused) Color.White else Color(0xFFB6BDCC), fontWeight = FontWeight.Medium,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
    )
}

// ───────────── Account page: cloud section ─────────────

/** Settings › Account: sign in, sync status, auto sync, sync now, password for TV, sign out. */
@Composable
internal fun CloudAccountSection() {
    val user by Cloud.user.collectAsStateWithLifecycle()
    val status by Cloud.status.collectAsStateWithLifecycle()
    val auto by Cloud.autoSync.collectAsStateWithLifecycle()
    var gate by remember { mutableStateOf(false) }
    var pwDialog by remember { mutableStateOf(false) }
    var confirmOut by remember { mutableStateOf(false) }

    SectionLabel("Cloud sync")
    SettingsCard {
        val u = user
        if (u == null) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudOff, null, Modifier.size(22.dp), tint = Muted)
                    Spacer(Modifier.width(10.dp))
                    Text("Not signed in", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("Sign in to keep every profile's addons, settings, collections, My List and progress in sync on all your phones and TVs.",
                    Modifier.padding(top = 6.dp), fontSize = 14.sp, color = Muted)
            }
            SettingRow(Icons.Default.CloudSync, "Sign in or create an account", "Email and password" + if (BuildConfig.IS_TV) "" else ", or Google") { gate = true }
        } else {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (u.verified) Icons.Default.CloudDone else Icons.Default.CloudOff, null, Modifier.size(22.dp),
                        tint = if (u.verified) Color(0xFF7BD88F) else Color(0xFFFFB13B))
                    Spacer(Modifier.width(10.dp))
                    Text(u.email.ifBlank { "Signed in" }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(if (u.google) "Signed in with Google" else "Signed in with email", Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Muted)
                if (status.isNotBlank()) Text(status, Modifier.padding(top = 6.dp), fontSize = 14.sp, color = Color(0xFFC9CDD6))
            }
            if (!u.verified) {
                RowDivider(20.dp)
                SettingRow(Icons.Default.CloudOff, "Verify your email", "Open the link we emailed you — sync starts after that.") { gate = true }
            }
            RowDivider(20.dp)
            ToggleRow("Auto cloud sync", "Changes save to the cloud as you make them and appear on your other devices.", auto) { Cloud.setAutoSync(it) }
            RowDivider(20.dp)
            SettingRow(Icons.Default.Sync, "Sync now", "Check the cloud for changes right away.") { Cloud.syncNow() }
            if (u.google && !BuildConfig.IS_TV) {
                RowDivider(20.dp)
                SettingRow(Icons.Default.Password, "Set a password for TV", "Fire TV can't use Google sign-in: sign in there with your email and this password.") { pwDialog = true }
            }
            RowDivider(20.dp)
            SettingRow(Icons.Default.Logout, "Sign out", "Your data stays on this device. To sign out every device, change your password.") { confirmOut = true }
        }
    }
    Text(
        "Each profile syncs its own addons, settings, collections, My List and progress. Some settings (quality, stream filters, player) can be kept separate for phone and TV.",
        Modifier.padding(horizontal = 24.dp, vertical = 10.dp), fontSize = 13.sp, color = Muted,
    )

    if (gate) {
        BackHandler { gate = false }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { gate = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) { CloudGate(onDone = { gate = false }, allowSkip = false) }
    }
    if (pwDialog) PasswordDialog { pwDialog = false }
    if (confirmOut) PremiumAlert(
        onDismissRequest = { confirmOut = false },
        containerColor = Surface1,
        title = { Text("Sign out?") },
        text = { Text("Sync stops on this device. Everything already here stays.") },
        confirmButton = { TextButton({ Cloud.signOut(); confirmOut = false }) { Text("Sign out") } },
        dismissButton = { TextButton({ confirmOut = false }) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordDialog(onClose: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    PremiumAlert(
        onDismissRequest = onClose,
        containerColor = Surface1,
        title = { Text("Password for TV") },
        text = {
            Column {
                Text("Then sign in on the TV with your Google email and this password.", fontSize = 14.sp, color = Muted)
                Spacer(Modifier.height(12.dp))
                GateField(pw, { pw = it }, "New password (8+ characters)", KeyboardType.Password, ImeAction.Done, password = true) {}
                msg?.let { Text(it, Modifier.padding(top = 10.dp), fontSize = 13.sp, color = Color(0xFFC9CDD6)) }
            }
        },
        confirmButton = {
            TextButton({
                if (busy) return@TextButton
                busy = true
                scope.launch {
                    msg = try { Cloud.setPassword(pw); "Password set." } catch (e: Exception) { e.message ?: "Couldn't set the password." }
                    busy = false
                }
            }) { Text(if (busy) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClose) { Text("Close") } },
    )
}

// ───────────── "Same on all devices / Separate for phone and TV" ─────────────

/**
 * For a settings section: keep these [keys] separate for phone and TV (in this profile), or the same everywhere.
 */
@Composable
internal fun DeviceScopeCard(vm: AppViewModel, keys: List<String>, what: String) {
    val texts by vm.texts.collectAsStateWithLifecycle()
    val dev = remember(texts["devOnly"]) { vm.deviceOnlyKeys(texts) }
    val separate = keys.all { it in dev }
    SettingsCard {
        Row(Modifier.padding(start = 20.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Devices, null, Modifier.size(20.dp), tint = Muted)
            Spacer(Modifier.width(8.dp))
            Text("Phone and TV", fontSize = 14.sp, color = Muted, fontWeight = FontWeight.SemiBold)
        }
        ToggleRow(
            "Separate for phone and TV",
            if (separate) "On: $what can be different on your phone and your TV (this profile)."
            else "Off: $what are the same on every device for this profile.",
            separate,
        ) { on ->
            val next = if (on) dev + keys else dev - keys.toSet()
            vm.setText("devOnly", next.joinToString("|"))
        }
    }
    Spacer(Modifier.height(8.dp))
}
