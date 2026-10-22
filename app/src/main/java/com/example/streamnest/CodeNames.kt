package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Developer's Corner › Code names: every real server name and the name normal users see (Hotstar → Helios…), editable.
 * Saved on this phone at once; "Publish for everyone" sends them with the developer's defaults.
 */
@Composable
fun CodeNamesPage(vm: AppViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val labels = remember {
        mapOf("moviebox dash" to "MovieBox · DASH / HLS (fast start)", "moviebox mp4" to "MovieBox · MP4 (sharper)")
    }
    // Current list (edits over built-ins), one editable row each.
    val rows = remember { mutableStateListOf<Pair<String, String>>().apply { addAll(Public.codes().sortedBy { it.first }) } }
    var newReal by remember { mutableStateOf("") }
    var newCode by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    fun save() {
        val defaults = (Public.defaultCodes + Public.defaultKinds).toMap()
        // Only what differs from the built-in list is stored.
        val out = rows.filter { (k, v) -> defaults[k] != v }.joinToString("\n") { "${it.first}=${it.second}" }
        vm.setText("codeNames", out)
        msg = "Saved on this phone."
    }
    Column(Modifier.fillMaxSize().background(Bg)) {
        PageHeader("Code names", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 80.dp)) {
            Text("What normal users see instead of the real server names. A row matches a plugin whose name starts with (or contains) the real name.",
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 13.sp, color = Muted)
            SectionLabel("Names (${rows.size})")
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows.forEachIndexed { i, (real, code) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(labels[real] ?: real.replaceFirstChar { it.uppercase() }, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(code, { v -> rows[i] = real to v }, Modifier.width(150.dp), singleLine = true, shape = RoundedCornerShape(12.dp))
                            IconButton({ rows.removeAt(i) }) { Icon(Icons.Default.Close, "Remove", tint = Color(0xFFFF8A80)) }
                        }
                    }
                }
            }
            SectionLabel("Add a name")
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(newReal, { newReal = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp), placeholder = { Text("Real name (e.g. jiotv)") })
                    OutlinedTextField(newCode, { newCode = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp), placeholder = { Text("Code name (e.g. Jupiter)") })
                    OutlinedButton({
                        val k = newReal.trim().lowercase()
                        if (k.isNotBlank() && newCode.isNotBlank()) { rows.removeAll { it.first == k }; rows.add(0, k to newCode.trim()); newReal = ""; newCode = "" }
                    }) { Text("Add", color = Color.White) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ save() }, Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(25.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A31), contentColor = Color.White)) { Text("Save", fontWeight = FontWeight.Bold) }
                OutlinedButton({ vm.setText("codeNames", ""); rows.clear(); rows.addAll(Public.codes().sortedBy { it.first }); msg = "Back to the built-in names." },
                    Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(25.dp)) { Text("Reset", color = Color.White) }
            }
            Button({ save(); msg = "Publishing…"; scope.launch { msg = DevDefaults.publish(vm) ?: "Published — every account gets these names the next time a profile opens." } },
                Modifier.padding(16.dp).fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914), contentColor = Color.White)) { Text("Publish for everyone", fontWeight = FontWeight.Bold) }
            msg?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), fontSize = 13.sp, color = Muted) }
        }
    }
}
