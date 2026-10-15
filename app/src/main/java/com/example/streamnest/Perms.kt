package com.example.streamnest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * First open after installing / updating (phones): one screen asking for notifications (rings from friends, new
 * episodes), microphone and camera (Watch together voice / video). Android's own windows follow. Allowing doesn't turn
 * anything on: the mic and camera stay off until switched on in Watch together. Shown once.
 */
@Composable
fun PermissionIntro() {
    if (BuildConfig.IS_TV) return
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("perm_intro", Context.MODE_PRIVATE) }
    val wanted = remember {
        listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
        )
    }
    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var show by remember { mutableStateOf(!prefs.getBoolean("v1", false) && wanted.any { !has(it) }) }
    var results by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        results = r
        prefs.edit().putBoolean("v1", true).apply()
        show = false
        val ok = r.values.count { it }
        Snack.show(if (ok == r.size) "All set — friends can ring you, and you can talk in Watch together" else "You can allow the rest later, when you use it")
    }
    if (!show) return
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0F)).background(Brush.radialGradient(listOf(Color(0x40E50914), Color.Transparent), radius = 900f))
        .clickable(enabled = true, onClick = {}), Alignment.Center) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(AppLook.logoRes(AppLook.logo.value)), null, Modifier.size(58.dp))
            Text("Watch together, properly", Modifier.padding(top = 14.dp), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text("Allow these once so friends can ring you and you can talk and see each other while you watch.",
                Modifier.padding(top = 6.dp), fontSize = 13.sp, color = Color(0xFF9AA3B5), textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            @Composable fun item(icon: String, title: String, sub: String) =
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF15161C))
                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0x24E50914)).border(1.dp, Color(0x80E50914), CircleShape), Alignment.Center) { Text(icon, fontSize = 15.sp) }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(sub, fontSize = 12.sp, color = Color(0xFF9AA3B5))
                    }
                }
            item("🔔", "Notifications", "Rings from friends, new episodes")
            item("🎙", "Microphone", "Talk in Watch together")
            item("📷", "Camera", "Your face in your friend's bubble")
            Text("The mic and camera stay off until you turn them on in Watch together.", Modifier.padding(top = 10.dp), fontSize = 12.sp, color = Color(0xFF8A94A8), textAlign = TextAlign.Center)
            Spacer(Modifier.height(22.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFE50914)).clickable { launcher.launch(wanted.filter { !has(it) }.toTypedArray()) }
                .padding(vertical = 14.dp), Alignment.Center) { Text("Allow", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            Text("Not now", Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).clickable {
                prefs.edit().putBoolean("v1", true).apply(); show = false
            }.padding(horizontal = 18.dp, vertical = 10.dp), fontSize = 14.sp, color = Color(0xFF9AA3B5), fontWeight = FontWeight.SemiBold)
        }
    }
}
