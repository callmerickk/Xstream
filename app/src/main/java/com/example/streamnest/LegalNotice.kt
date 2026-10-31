package com.example.streamnest

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle

const val XSTREAM_LEGAL_VERSION = 1
const val XSTREAM_LEGAL_NOTICE = "Xstream is a media player and organiser. It does not host, upload, store or distribute any movies, shows, music or other content. Everything you play is found and streamed by third-party add-ons, plug-ins and websites that you choose to use, and comes directly from those third-party services.\n\nWe do not control, check or endorse that content and are not responsible for it. You are solely responsible for making sure that what you watch or listen to, and how you use it, is legal where you live, including copyright and licensing laws.\n\nCopyright owners who want material removed should contact the website or file host where it is hosted.\n\nBy tapping \"I understand and agree\" you confirm you are 18+ (or have a parent's permission) and accept these terms."

private tailrec fun Context.legalActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.legalActivity()
    else -> null
}

@Composable
fun LegalNoticeGate(vm: AppViewModel) {
    val user by Cloud.user.collectAsStateWithLifecycle()
    val texts by vm.texts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uid = user?.uid
    var accepted by remember(uid) { mutableStateOf(false) }

    LaunchedEffect(uid, texts["legalAcceptedAt"], texts["legalVersion"], texts["legalAcceptedUid"]) {
        if (uid == null) { accepted = false; return@LaunchedEffect }
        val prefs = context.getSharedPreferences("xstream_legal", Context.MODE_PRIVATE)
        accepted = prefs.getInt("version_" + uid, 0) >= XSTREAM_LEGAL_VERSION ||
            (texts["legalVersion"]?.toIntOrNull() ?: 0) >= XSTREAM_LEGAL_VERSION &&
            texts["legalAcceptedUid"] == uid && !texts["legalAcceptedAt"].isNullOrBlank()
    }

    if (user != null && !accepted) {
        var checked by remember(uid) { mutableStateOf(false) }
        BackHandler(enabled = true) { }
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color(0xEE030303)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(horizontal = 22.dp, vertical = 28.dp)
                        .clip(RoundedCornerShape(24.dp)).background(Color(0xFF191919)).padding(24.dp),
                ) {
                    Text("Before you start", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        XSTREAM_LEGAL_NOTICE,
                        Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                        fontSize = 15.sp, lineHeight = 22.sp, color = Color(0xFFE0E0E0),
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = { checked = it })
                        Text("I have read and understand this notice", fontSize = 14.sp, color = Color.White)
                    }
                    Button(
                        onClick = {
                            val now = System.currentTimeMillis().toString()
                            context.getSharedPreferences("xstream_legal", Context.MODE_PRIVATE).edit()
                                .putInt("version_" + uid, XSTREAM_LEGAL_VERSION).putLong("accepted_at_" + uid, now.toLong()).apply()
                            vm.setText("legalVersion", XSTREAM_LEGAL_VERSION.toString())
                            vm.setText("legalAcceptedAt", now)
                            vm.setText("legalAcceptedUid", uid.orEmpty())
                            accepted = true
                        },
                        enabled = checked,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914), contentColor = Color.White),
                    ) { Text("I understand and agree", fontWeight = FontWeight.Bold) }
                    TextButton(
                        onClick = { context.legalActivity()?.finishAffinity() },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text("Exit", color = Color(0xFFAAAAAA)) }
                }
            }
        }
    }
}
