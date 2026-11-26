package com.example.streamnest

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private val SiBg = Color(0xFF030303)
private val SiSub = Color(0xFFAAAAAA)
private val SiRed = Color(0xFFE50914)

private const val SIGN_IN_URL =
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"

/**
 * Wrap the Music screen in this. With the musicOrbit switch off it just shows the content.
 * With it on: the first time a profile opens Music it shows "Bring your YouTube Music".
 * After the choice is saved it shows the content, and does not ask that profile again.
 */
@Composable
fun MusicSignInGate(profileId: String, profileName: String, content: @Composable () -> Unit) {
    if (!Feature.musicOrbit) {
        content()
        return
    }
    val app = LocalContext.current.applicationContext
    var loaded by remember(profileId) { mutableStateOf(false) }
    LaunchedEffect(profileId) { MusicAccount.use(app, profileId); loaded = true }
    val acct by MusicAccount.current.collectAsState()
    val mode: String? = if (loaded && MusicAccount.activeId == profileId) acct.mode else null
    when (mode) {
        null -> Box(Modifier.fillMaxSize().background(SiBg))
        "" -> MusicSignInPage(profileId, profileName) { }
        else -> content()
    }
}

@Composable
private fun MusicSignInPage(profileId: String, profileName: String, onDone: (String) -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var web by remember { mutableStateOf(false) }

    if (web) {
        GoogleSignInWeb(
            onBack = { web = false },
            onCookie = { cookie ->
                scope.launch {
                    MusicSync.refreshLabel(app, profileId, cookie)
                    clearWebCookies() // the browser jar must not carry this account into another profile
                    MusicAccount.signIn(app, profileId, cookie) // last: the gate swaps screens as soon as this saves
                    onDone("google")
                }
            },
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(SiBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("For $profileName", color = SiSub, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            "Bring your YouTube Music",
            color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Sign in to see your own Home, playlists, liked music and history. Likes and playlist changes go back to your account.",
            color = SiSub, fontSize = 15.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SiRed)
                .clickable {
                    MusicLog.add("Sign-in page: Sign in with Google tapped (profile $profileId)")
                    web = true
                }
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Sign in with Google", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }

        Spacer(Modifier.height(12.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(16.dp))
                .clickable {
                    scope.launch {
                        MusicAccount.chooseOwn(app, profileId)
                        onDone("own")
                    }
                }
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Use Xstream's own library", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }

        Spacer(Modifier.height(24.dp))
        Text(
            "Unofficial; a second Google account is safer. Your sign-in stays on this phone.",
            color = SiSub, fontSize = 12.sp, textAlign = TextAlign.Center,
        )
    }
}

/** Empties the app-wide WebView cookie jar and site storage. Must run on the main thread. */
private suspend fun clearWebCookies() = withContext(Dispatchers.Main) {
    val cm = CookieManager.getInstance()
    suspendCancellableCoroutine<Unit> { k -> cm.removeAllCookies { if (k.isActive) k.resume(Unit) } }
    cm.flush()
    WebStorage.getInstance().deleteAllData()
}

private val SapisidRegex = Regex("(^|;\\s*)SAPISID=")

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GoogleSignInWeb(onCookie: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val latest by rememberUpdatedState(onCookie)
    var ready by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val done = remember { booleanArrayOf(false) }

    // Start from an empty browser so another profile's Google session is not picked up.
    LaunchedEffect(Unit) {
        clearWebCookies()
        MusicLog.add("Sign-in: browser cookies cleared, opening Google")
        ready = true
    }

    val web = remember {
        val check: (String?) -> Unit = check@{ url ->
            if (done[0] || url == null) return@check
            if (Uri.parse(url).host != "music.youtube.com") return@check
            val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com") ?: return@check
            if (SapisidRegex.containsMatchIn(cookie)) {
                done[0] = true
                CookieManager.getInstance().flush()
                MusicLog.add("Sign-in: Google sign-in finished, SAPISID cookie found")
                latest(cookie)
            }
        }
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // The default WebView user agent contains "; wv", which Google can use to refuse sign-in.
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    loading = false
                    check(url)
                }
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    check(url)
                }
            }
        }
    }

    LaunchedEffect(ready) { if (ready) web.loadUrl(SIGN_IN_URL) }
    DisposableEffect(Unit) { onDispose { web.stopLoading(); web.destroy() } }
    BackHandler { if (web.canGoBack()) web.goBack() else onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .background(SiBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Sign in to YouTube Music", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (loading) CircularProgressIndicator(Modifier.padding(end = 16.dp).height(20.dp), color = SiRed, strokeWidth = 2.dp)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (ready) AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
        }
    }
}
