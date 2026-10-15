package com.example.streamnest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
private fun SpaceTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE6E9F2))

@Composable
fun MySpaceScreen(
    vm: AppViewModel,
    onSettings: () -> Unit,
    onProfile: () -> Unit,
    onSwitchProfile: () -> Unit,
    onOpen: (Meta) -> Unit,
    onPlay: (Screen.Player) -> Unit,
    onManual: (Meta, String) -> Unit = { _, _ -> },
) {
    val profile by vm.current.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val myList by vm.myList.collectAsStateWithLifecycle()
    var editMode by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var adding by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Progress?>(null) }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(520.dp).background(Brush.verticalGradient(listOf(Color(0xFF14091E), Palette.tint.copy(alpha = 0.55f), Bg))))
        LazyColumn(Modifier.fillMaxSize()) {
            // Logo + Help & Settings
            item {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppLogo(vm, 40.dp)
                    Spacer(Modifier.weight(1f))
                    Row(
                        Modifier.tourTarget("settings").clip(RoundedCornerShape(20.dp)).clickable(onClick = onSettings).padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Settings, null, Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t("helpSettings"), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            // Current profile card
            item {
                val shape = RoundedCornerShape(20.dp)
                Row(
                    Modifier.padding(16.dp).fillMaxWidth().clip(shape).background(Color(0xCC161B26)).border(1.dp, Color(0x14FFFFFF), shape).padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProfileAvatar(vm, profile, 58.dp, Modifier.clickable(onClick = onProfile))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile?.name ?: "", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${progress.size} in progress  ·  ${myList.size} in watchlist", fontSize = 14.sp, color = Color(0xFF9AA3B5))
                    }
                    Row(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(Accent).clickable(onClick = onSwitchProfile).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.SwapHoriz, null, Modifier.size(18.dp), tint = OnAccent)
                        Spacer(Modifier.width(4.dp))
                        Text("Switch", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = OnAccent)
                    }
                }
            }
            // Profiles row
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SpaceTitle("Profiles", Modifier.weight(1f))
                    Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable { editMode = !editMode }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (editMode) Icons.Default.Check else Icons.Default.Edit, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (editMode) "Done" else "Edit", fontSize = 16.sp)
                    }
                }
            }
            item {
                LazyRow(Modifier.tourTarget("profiles"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(profiles, key = { it.id }) { p ->
                        Column(
                            Modifier.width(80.dp).clip(RoundedCornerShape(14.dp)).clickable { if (editMode) editing = p else ProfileLock.ask(p) { vm.selectProfile(p) } },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val selected = p.id == profile?.id
                            Box {
                                ProfileAvatar(vm, p, 72.dp, if (selected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                                if (editMode) Box(Modifier.size(72.dp).clip(CircleShape).background(Color(0x88000000)), Alignment.Center) {
                                    Icon(Icons.Default.Edit, "Edit ${p.name}", Modifier.size(24.dp))
                                }
                            }
                            Text(p.name, Modifier.padding(top = 8.dp), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                    if (profiles.size < 5) item {
                        Column(Modifier.width(80.dp).clip(RoundedCornerShape(14.dp)).clickable { adding = true }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(72.dp).clip(CircleShape).background(Color(0xFF2A2030)), Alignment.Center) { Icon(Icons.Default.Add, "Add profile", Modifier.size(32.dp)) }
                            Text("Add", Modifier.padding(top = 8.dp), fontSize = 14.sp)
                        }
                    }
                }
            }
            // Watchlist
            item { SpaceTitle("Watchlist", Modifier.padding(start = 16.dp, top = 18.dp, bottom = 12.dp)) }
            if (myList.isEmpty()) {
                item {
                    Text("Nothing here yet. Tap + on any title to save it.", Modifier.padding(horizontal = 16.dp), color = Color(0xFF9AA3B5), fontSize = 15.sp)
                }
            } else {
                // TV: six posters a row (three made each poster screen-high).
                val cols = if (BuildConfig.IS_TV) 6 else 3
                items(myList.chunked(cols)) { row ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(if (BuildConfig.IS_TV) 16.dp else 10.dp)) {
                        row.forEach { m -> Poster(m, Modifier.weight(1f), onOpen) }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            // Continue watching
            if (progress.isNotEmpty()) {
                item { SpaceTitle(t("continueWatching").replace("{name}", profile?.name ?: ""), Modifier.padding(start = 16.dp, top = 26.dp, bottom = 12.dp)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(progress, key = { it.key }) { p ->
                            ContinueCard(p, onClick = { onPlay(Screen.Player(p.title, p.url, p.subs, p.key, p.image, p.positionMs)) }, onRemove = { vm.removeProgress(p.key) },
                                onLongClick = { actionsFor = p })
                        }
                    }
                }
            }
            if (!BuildConfig.IS_TV) item {
                var joining by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp).tourTarget("together").clip(RoundedCornerShape(14.dp)).background(Color(0xFF15161C))
                        .clickable { joining = true }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Groups, null, Modifier.size(24.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Watch together", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Join a friend's room with its code. To start one: play something › More › Together.", fontSize = 12.sp, color = Color(0xFF9AA3B5))
                    }
                }
                if (joining) JoinRoomDialog(onJoined = { st ->
                    joining = false
                    val expected = st.pos + if (st.playing) (System.currentTimeMillis() - st.receivedAt + 300) else 0L
                    onPlay(Screen.Player(st.title, st.url, emptyList(), key = st.key, image = st.image, resumeMs = expected, type = st.type))
                }, onDismiss = { joining = false })
            }
            if (!BuildConfig.IS_TV) item { DownloadsSection(onPlay) }
            item { Spacer(Modifier.navigationBarsPadding().height(120.dp)) }
        }
    }
    editing?.let { p -> ProfileEditor(vm, p) { editing = null } }
    if (adding) ProfileEditor(vm, null) { adding = false }
    actionsFor?.let { p ->
        ContinueActions(
            p,
            onDetails = { onOpen(p.toMeta()) },
            onManual = { onManual(p.toMeta(), p.key) },
            onStartOver = { onPlay(Screen.Player(p.title, p.url, p.subs, p.key, p.image, 0L)) },
            onRemove = { vm.removeProgress(p.key) },
            onDismiss = { actionsFor = null },
        )
    }
}
