package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Music prompt 5 (musicLook2), part 5: Settings > Music look, as a card for the profile page.
 *
 * Separate from Xstream's own theme. Saved per profile in the "music" prefs by MusicLook2
 * (setLook / setGlass / setFollow). Only called when Feature.musicLook2 is on; with the switch off
 * the profile page is exactly as before.
 */
@Composable
fun MusicLookCard(modifier: Modifier = Modifier) {
    if (!Feature.musicLook2) return
    val ctx = LocalContext.current
    val current by MusicLook.current.collectAsStateWithLifecycle()
    val glass by MusicLook2.glass.collectAsStateWithLifecycle()
    val follow by MusicLook2.follow.collectAsStateWithLifecycle()

    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xFF1D1D1D))
            .border(1.dp, Color(0x14FFFFFF), shape)
            .padding(16.dp)
    ) {
        Text("Music look", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "Only for Music. Xstream keeps its own theme.",
            color = Color(0xFFAAAAAA), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(14.dp))

        // The five colour looks.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MusicLook.all.forEach { look ->
                val selected = look.name == current.name
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .selectable(selected = selected, role = Role.RadioButton) {
                            if (!selected) {
                                MusicLook2.setLook(ctx, look)
                                MusicLog.add("Music look card: the ${look.name} look was chosen")
                            }
                        }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(look.glow, look.bg)))
                            .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else Color(0x33FFFFFF), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(16.dp).clip(CircleShape).background(look.accent))
                    }
                    Text(
                        look.name,
                        color = if (selected) Color.White else Color(0xFFAAAAAA),
                        fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        MusicLookToggle(
            title = "Glass",
            subtitle = if (MusicLook2.canBlur)
                "Frosted bars, cards and sheets over a blurred copy of the playing cover."
            else
                "This phone is on Android 11 or older and cannot blur, so a dark shade goes over the cover instead.",
            checked = glass,
            accent = current.accent,
        ) {
            MusicLook2.setGlass(ctx, it)
            MusicLog.add("Music look card: Glass turned " + if (it) "on" else "off")
        }
        MusicLookToggle(
            title = "Follow the song",
            subtitle = "Background and accent come from the playing cover. A grey cover keeps the look above.",
            checked = follow,
            accent = current.accent,
        ) {
            MusicLook2.setFollow(ctx, it)
            MusicLog.add("Music look card: Follow the song turned " + if (it) "on" else "off")
        }
    }
}

/** One row with a title, a grey line and a switch. The whole row is the touch target. */
@Composable
private fun MusicLookToggle(title: String, subtitle: String, checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFFAAAAAA), fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(12.dp))
        // onCheckedChange = null: the row handles the tap, so the switch is not a second touch target.
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = Color.White),
        )
    }
}
