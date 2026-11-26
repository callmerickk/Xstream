package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Prompt 7 (Feature.accessDevices): Developer's Corner > Access.
 * Grouped by account email (expandable). Inside: each device with model, typed name, app + version, last seen,
 * and Grant / Revoke. "Revoke all" per account (asks first). A badge shows how many devices are waiting.
 * Developer only: the page is opened from the developer's route, as the old AccessAdmin was.
 * The "Approval required" switch is kept here, so the developer does not lose it.
 */
@Composable
fun AccessDevicesAdmin() {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<AccessDevices.Rec>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var required by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val opened = remember { mutableStateListOf<String>() }
    var confirm by remember { mutableStateOf<Pair<String, List<AccessDevices.Rec>>?>(null) }

    // Loads now, then every 30 s while the page is open. A new tick (after a Grant / Revoke) loads at once.
    LaunchedEffect(tick) {
        while (true) {
            val list = AccessDevices.all()
            if (list == null) failed = true else {
                devices = list; failed = false
                required = Access.required()   // only read when the network worked, so a failure never flips the switch
            }
            delay(30_000)
        }
    }
    // Accounts with waiting devices start open (once).
    LaunchedEffect(devices != null) {
        devices?.let { l -> AccessDevices.byAccount(l).filter { AccessDevices.waiting(it.second) > 0 }.forEach { if (it.first !in opened) opened.add(it.first) } }
    }

    fun act(block: suspend () -> Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = runCatching { block() }.getOrDefault(false)
            if (!ok) Snack.show("Couldn't change it (check the Firebase rules)")
            busy = false
            tick++
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Approval required", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("When on, every phone must be approved here before it can use ${Brand.NAME}.", color = Color(0xFF9A9A9A), fontSize = 12.sp)
            }
            Switch(required, { on -> scope.launch { if (Access.setRequired(on)) required = on else Snack.show("Couldn't change it (check the Firebase rules)") } })
        }
        Text("Your account id (for the Firebase rules): ${Cloud.user.value?.uid ?: "sign in first"}", color = Color(0xFF8A8A8A), fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(14.dp))

        val list = devices
        when {
            list == null && failed -> {
                Text("Couldn't load the devices. Check the connection and the Firebase rules for access/devices.", color = Color(0xFFE08A8A), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Pill("Try again", Color(0xFF2A2B32)) { tick++ }
            }
            list == null -> Text("Loading…", color = Color(0xFF9A9A9A))
            list.isEmpty() -> Text("No devices have asked yet.", color = Color(0xFF9A9A9A))
            else -> {
                val waitingAll = AccessDevices.waiting(list)
                Text(if (waitingAll > 0) "$waitingAll waiting for a decision" else "Nobody is waiting",
                    color = if (waitingAll > 0) Color(0xFFFFC107) else Color(0xFF9A9A9A), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (failed) Text("Could not refresh just now; this is the last list that loaded.", color = Color(0xFFE08A8A), fontSize = 11.sp)
                AccessDevices.byAccount(list).forEach { (email, devs) ->
                    val isOpen = email in opened
                    val waiting = AccessDevices.waiting(devs)
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1A1B20))) {
                        Row(
                            Modifier.fillMaxWidth().clickable { if (isOpen) opened.remove(email) else opened.add(email) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(email, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("${devs.size} device${if (devs.size == 1) "" else "s"} · ${devs.count { it.status == AccessDevices.Status.APPROVED }} approved",
                                    color = Color(0xFF8A8A8A), fontSize = 12.sp)
                            }
                            if (waiting > 0) { AccessBadge(waiting); Spacer(Modifier.width(8.dp)) }
                            Icon(if (isOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, if (isOpen) "Close" else "Open", tint = Color(0xFF9A9A9A))
                        }
                        if (isOpen) {
                            devs.sortedByDescending { it.lastSeen }.forEach { d ->
                                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(d.name.ifBlank { "No name" }, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        StatusTag(d.status)
                                    }
                                    Text(d.model.ifBlank { "Unknown phone" }, color = Color(0xFFB3B3B3), fontSize = 12.sp)
                                    Text("${Brand.NAME} ${d.appVersion.ifBlank { "?" }} · last seen ${ago(d.lastSeen)}", color = Color(0xFF8A8A8A), fontSize = 12.sp)
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        if (d.status != AccessDevices.Status.APPROVED) Pill("Grant", Color(0xFF1F8F4E)) { act { AccessDevices.grant(d) } }
                                        if (d.status != AccessDevices.Status.REVOKED) Pill("Revoke", Color(0xFF5A2A2A)) { act { AccessDevices.revoke(d) } }
                                    }
                                }
                            }
                            if (devs.any { it.status != AccessDevices.Status.REVOKED }) {
                                Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                                    Pill("Revoke all", Color(0xFF7A2323)) { confirm = email to devs }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { (email, devs) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Revoke all devices?") },
            text = { Text("Every device of $email will be paused. Each person can ask again with their name.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    act { val n = AccessDevices.revokeAll(devs); ActLog.add("APP", "access: revoke all for $email, $n changed"); n > 0 }
                }) { Text("Revoke all") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

/** How many devices are waiting. Put it on the Access row in Developer's Corner. Re-reads every minute, only while shown. */
@Composable
fun rememberAccessWaiting(): State<Int> = produceState(0) {
    while (true) {
        if (Feature.accessGate && Feature.accessDevices) AccessDevices.all()?.let { value = AccessDevices.waiting(it) }
        delay(60_000)
    }
}

/** Small red round badge with a number; draws nothing for 0. */
@Composable
fun AccessBadge(count: Int) {
    if (count <= 0) return
    Box(Modifier.clip(CircleShape).background(Color(0xFFE50914)).padding(horizontal = 7.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
        Text(if (count > 99) "99+" else "$count", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusTag(s: AccessDevices.Status) {
    val (label, c) = when (s) {
        AccessDevices.Status.APPROVED -> "Approved" to Color(0xFF4CAF50)
        AccessDevices.Status.REVOKED -> "Revoked" to Color(0xFFE57373)
        AccessDevices.Status.WAITING -> "Waiting" to Color(0xFFFFC107)
    }
    Text(label, color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Pill(label: String, c: Color, onClick: () -> Unit) =
    Box(Modifier.clip(RoundedCornerShape(50)).background(c).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text(label, color = Color.White, fontSize = 13.sp)
    }

private fun ago(ms: Long): String {
    if (ms <= 0L) return "never"
    val m = (System.currentTimeMillis() - ms) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / 1440} days ago"
    }
}
