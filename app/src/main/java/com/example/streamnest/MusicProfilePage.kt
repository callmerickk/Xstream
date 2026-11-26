package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Music profile page (placeholder in Part 1; the stats arrive in Part 2). Back closes it. */
@Composable
fun MusicProfilePage(vm: AppViewModel, profileName: String, onClose: () -> Unit) {
    val profile by vm.current.collectAsStateWithLifecycle()
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF030303))) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth()) { IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
                Spacer(Modifier.height(24.dp))
                ProfileAvatar(vm, profile, 112.dp, animate = true)
                Text(profileName, Modifier.padding(top = 14.dp), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Your listening stats come next.", Modifier.padding(top = 6.dp), fontSize = 13.sp, color = Color(0xFF9AA3B5))
            }
        }
    }
}
