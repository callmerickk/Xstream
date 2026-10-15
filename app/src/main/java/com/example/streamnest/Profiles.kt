package com.example.streamnest

import android.graphics.Matrix
import android.net.Uri
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.io.File

private val avatarColors = listOf(0xFFE8323C, 0xFF2E7DD7, 0xFF8E44AD, 0xFF16A085, 0xFFE67E22, 0xFFD35490)

/** A round profile picture: photo, animated GIF, looping silent video, or a coloured initial. */
@Composable
fun ProfileAvatar(vm: AppViewModel, p: Profile?, size: Dp, modifier: Modifier = Modifier, animate: Boolean = size >= 48.dp) {
    val ctx = LocalContext.current
    Box(modifier.size(size).clip(CircleShape).background(Color(0xFF232B3A)), Alignment.Center) {
        when {
            p == null -> {}
            p.avatar == "video" && animate -> VideoLoop(vm.avatarFile(p), p.ver, Modifier.fillMaxSize())
            p.avatar.isNotEmpty() -> AsyncImage(
                ImageRequest.Builder(ctx).data(vm.avatarFile(p)).memoryCacheKey("av_${p.id}_${p.ver}")
                    .diskCachePolicy(CachePolicy.DISABLED).build(),
                p.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            )
            p.kids -> Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFF8D27A), Color(0xFFD99A2B)))),
                Alignment.Center,
            ) { Text("KiDS", fontSize = (size.value * 0.24f).sp, fontWeight = FontWeight.ExtraBold, color = Color.White, fontFamily = BrandFont) }
            else -> {
                val c = Color(avatarColors[(p.id.hashCode() and 0x7fffffff) % avatarColors.size])
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c, c.copy(alpha = 0.6f)))), Alignment.Center) {
                    Text(p.name.take(1).uppercase(), fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

/** Silent looping video cropped to fill its box (used for "live photo" profile pictures). */
@OptIn(UnstableApi::class)
@Composable
fun VideoLoop(file: File, ver: Int, modifier: Modifier) {
    val ctx = LocalContext.current
    var view by remember { mutableStateOf<TextureView?>(null) }
    var vSize by remember { mutableStateOf(VideoSize.UNKNOWN) }
    val player = remember(file.path, ver) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) { vSize = videoSize }
            })
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    fun applyCrop(tv: TextureView) {
        val vw = vSize.width.toFloat(); val vh = vSize.height.toFloat()
        val w = tv.width.toFloat(); val h = tv.height.toFloat()
        if (vw <= 0f || vh <= 0f || w <= 0f || h <= 0f) return
        val scale = maxOf(w / vw, h / vh)
        tv.setTransform(Matrix().apply { setScale(vw * scale / w, vh * scale / h, w / 2f, h / 2f) })
    }

    AndroidView(
        factory = { c ->
            TextureView(c).also { tv ->
                view = tv
                player.setVideoTextureView(tv)
                tv.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> applyCrop(v as TextureView) }
            }
        },
        update = { tv -> player.setVideoTextureView(tv); applyCrop(tv) },
        modifier = modifier,
    )
    LaunchedEffect(vSize) { view?.let { applyCrop(it) } }
}

/** The current profile's picture (used in the bottom bar and settings). */
@Composable
fun Avatar(vm: AppViewModel, size: Dp, modifier: Modifier = Modifier) {
    val p by vm.current.collectAsStateWithLifecycle()
    ProfileAvatar(vm, p, size, modifier)
}

/** Create (p == null) or edit a profile: name, kids switch, picture / GIF / short video, delete. */
@Composable
fun ProfileEditor(vm: AppViewModel, p: Profile?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val live = p?.let { cur -> profiles.firstOrNull { it.id == cur.id } }
    var name by remember { mutableStateOf(p?.name ?: "") }
    var kids by remember { mutableStateOf(p?.kids ?: false) }
    var pending by remember { mutableStateOf<Uri?>(null) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var urlDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var gifSearch by remember { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (live != null) {
            busy = true
            scope.launch {
                vm.setAvatar(live, uri)?.let { Snack.show(it, millis = 4000) }
                busy = false
            }
        } else { pending = uri; pendingUrl = null }
    }

    PremiumAlert(
        onDismissRequest = onDone,
        containerColor = Surface1,
        title = { Text(if (p == null) "Add profile" else "Edit profile") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(104.dp), Alignment.Center) {
                    if (live != null) ProfileAvatar(vm, live, 104.dp)
                    else if (pending != null) AsyncImage(pending, null, Modifier.size(104.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    else if (pendingUrl != null) AsyncImage(pendingUrl, null, Modifier.size(104.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    else ProfileAvatar(vm, Profile("new", name.ifBlank { "?" }, kids), 104.dp)
                    if (busy) CircularProgressIndicator(Modifier.size(40.dp))
                }
                Row {
                    TextButton({ pick.launch(arrayOf("image/*", "video/*")) }) { Text("Choose file") }
                    TextButton({ urlDialog = true }) { Text("From URL") }
                    if (!BuildConfig.IS_TV) TextButton({ gifSearch = true }) { Text("GIFs") }
                    if (live?.avatar?.isNotEmpty() == true) TextButton({ vm.clearAvatar(live) }) { Text("Remove") }
                }
                Text("JPEG, PNG, GIF, or a video up to 15 seconds (plays silently on a loop). Or paste a link to an image or GIF.", fontSize = 12.sp, color = Color(0xFF9AA3B5), textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(name, { name = it.take(20) }, Modifier.fillMaxWidth().tvOkKeyboard(), singleLine = true, label = { Text("Name") }, shape = RoundedCornerShape(12.dp), keyboardOptions = tvKeyboard())
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Kids profile", fontWeight = FontWeight.Medium)
                        Text("Shows only animated and family titles.", fontSize = 12.sp, color = Color(0xFF9AA3B5))
                    }
                    Switch(kids, { kids = it })
                }
                if (live != null && !BuildConfig.IS_TV) ProfileLockRow(live)
                if (live != null && profiles.size > 1) {
                    TextButton({ confirmDelete = true }, Modifier.padding(top = 6.dp)) { Text("Delete profile", color = Color(0xFFFF6B6B)) }
                }
            }
        },
        confirmButton = {
            TextButton({
                if (live != null) {
                    vm.renameProfile(live, name, kids)
                    onDone()
                } else {
                    val created = vm.addProfile(name, kids)
                    val uri = pending
                    val link = pendingUrl
                    if (uri != null) scope.launch {
                        vm.setAvatar(created, uri)?.let { Snack.show(it, millis = 4000) }
                    } else if (link != null) scope.launch {
                        vm.setAvatarFromUrl(created, link)?.let { Snack.show(it, millis = 4000) }
                    }
                    onDone()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDone) { Text("Cancel") } },
    )

    if (gifSearch) GifPicker(vm, onPick = { link ->
        gifSearch = false
        if (live != null) {
            busy = true
            scope.launch { vm.setAvatarFromUrl(live, link)?.let { Snack.show(it, millis = 4000) }; busy = false }
        } else { pendingUrl = link; pending = null }
    }, onDismiss = { gifSearch = false })

    if (urlDialog) {
        var link by remember { mutableStateOf("") }
        PremiumAlert(
            onDismissRequest = { urlDialog = false },
            title = { Text("Picture from URL") },
            text = {
                Column {
                    Text("Paste a direct link to a JPEG, PNG, GIF (animated is fine), WebP or a short MP4.", fontSize = 13.sp, color = Color(0xFF9AA3B5))
                    OutlinedTextField(link, { link = it.trim() }, Modifier.fillMaxWidth().padding(top = 12.dp).tvOkKeyboard(), singleLine = true,
                        placeholder = { Text("https://…/picture.gif") }, shape = RoundedCornerShape(12.dp), keyboardOptions = tvKeyboard())
                    if (link.startsWith("http")) Box(Modifier.fillMaxWidth().padding(top = 14.dp), Alignment.Center) {
                        AsyncImage(link, null, Modifier.size(84.dp).clip(CircleShape).background(Color(0xFF222228)), contentScale = ContentScale.Crop)
                    }
                }
            },
            confirmButton = {
                TextButton({
                    if (live != null) {
                        busy = true; urlDialog = false
                        scope.launch {
                            vm.setAvatarFromUrl(live, link)?.let { Snack.show(it, millis = 4000) }
                            busy = false
                        }
                    } else { pendingUrl = link; pending = null; urlDialog = false }
                }, enabled = link.startsWith("http")) { Text("Use picture") }
            },
            dismissButton = { TextButton({ urlDialog = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete && live != null) PremiumAlert(
        onDismissRequest = { confirmDelete = false },
        containerColor = Surface1,
        title = { Text("Delete ${live.name}?") },
        text = { Text("This removes the profile with its watch history and watchlist.") },
        confirmButton = { TextButton({ vm.deleteProfile(live); confirmDelete = false; onDone() }) { Text("Delete", color = Color(0xFFFF6B6B)) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
}
