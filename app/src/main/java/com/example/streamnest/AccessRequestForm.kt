package com.example.streamnest

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Prompt 7 (Feature.accessDevices): the request part of the access screen.
 * Shows the account email and this phone's name, a required "Your name" box, and the "Request access" button.
 * Used for a new phone (state "request") and for a revoked phone (state "paused", button says "Ask again").
 * Only called when Feature.accessDevices is on.
 *
 * [onSend] gets the trimmed name and returns true when the request reached the database.
 */
@Composable
fun AccessRequestForm(email: String, paused: Boolean, onSend: suspend (String) -> Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("access", Context.MODE_PRIVATE) }
    // The name typed last time is filled in again, so "ask again" after a revoke is one tap. It is never made up.
    var name by remember { mutableStateOf(prefs.getString("devName", "").orEmpty()) }
    var sending by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    val missing = tried && name.isBlank()

    val send: () -> Unit = {
        tried = true
        if (name.isNotBlank() && !sending) {
            sending = true
            val n = name.trim()
            // Saved before sending: a successful send changes the state and this form leaves the screen at once.
            prefs.edit().putString("devName", n).apply()
            scope.launch {
                val ok = runCatching { onSend(n) }.getOrDefault(false)
                sending = false
                if (!ok) Snack.show("Couldn't send — check your connection")
            }
        }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (email.isNotBlank()) Text("Account: $email", color = Color(0xFFB3B3B3), fontSize = 13.sp, textAlign = TextAlign.Center)
        Text("Device: ${AccessDevices.model()}", color = Color(0xFFB3B3B3), fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Your name") },
            isError = missing,
            supportingText = { Text(if (missing) "Please type your name." else "So the developer knows it's you.") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { send() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFE50914), unfocusedBorderColor = Color(0xFF4A4A52),
                focusedLabelColor = Color(0xFFE50914), unfocusedLabelColor = Color(0xFF9A9A9A),
                cursorColor = Color(0xFFE50914),
                focusedSupportingTextColor = Color(0xFF9A9A9A), unfocusedSupportingTextColor = Color(0xFF9A9A9A),
            ),
        )
        Spacer(Modifier.height(14.dp))
        val enabled = name.isNotBlank() && !sending
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (enabled) Color(0xFFE50914) else Color(0xFF5A2A2E))
                .clickable(onClick = send)
                .padding(horizontal = 26.dp, vertical = 12.dp)
        ) {
            Text(
                if (sending) "Sending…" else if (paused) "Ask again" else "Request access",
                color = if (enabled) Color.White else Color(0xFFB3B3B3), fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
