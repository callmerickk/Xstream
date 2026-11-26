package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * "YouTube Music account: signed in as ... · Sign out", for one profile.
 * Signed out: "Sign in with Google" shows the "Bring your YouTube Music" page again.
 * Only use it behind Feature.musicOrbit.
 */
@Composable
fun MusicAccountSettings(profileId: String, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val acct by MusicAccount.current.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xFF1D1D1D))
            .border(1.dp, Color(0x14FFFFFF), shape)
            .padding(16.dp),
    ) {
        Text("YouTube Music account", color = Color(0xFFAAAAAA), fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (acct.signedIn) "Signed in as " + acct.label.ifBlank { "your Google account" }
                else "Using Xstream's own library",
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (acct.signedIn) "Sign out" else "Sign in",
                color = Color(0xFFE50914), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        if (acct.signedIn) confirm = true
                        else scope.launch {
                            MusicLog.add("Settings: Sign in tapped (profile $profileId)")
                            MusicAccount.askAgain(app, profileId)
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Text(
            "Each profile has its own account. Your sign-in stays on this phone.",
            color = Color(0xFFAAAAAA), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Sign out of YouTube Music?") },
            text = { Text("This profile goes back to Xstream's own library. Your account is not changed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch { MusicAccount.signOut(app, profileId) }
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
