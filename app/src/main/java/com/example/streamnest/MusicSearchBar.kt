package com.example.streamnest

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * musicSearch2 · the search bar of YouTube Music: back, the text field, × (clear), mic (speech to text) and
 * "what's this song" (a stub for now). [onSearch] is the full search (Enter on the keyboard, or after the mic heard
 * something); [onQ] is every change of the text. The caller owns the text and the [focus] requester.
 */
@Composable
fun MusicSearchBar(
    q: String,
    onQ: (String) -> Unit,
    onSearch: (String) -> Unit,
    onBack: () -> Unit,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val sub = Color(0xFFAAAAAA)

    // Speech to text: Android's own recogniser screen, no permission and no library needed.
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
            if (said.isNotBlank()) {
                MusicLog.add("search: the mic heard \"${said.take(60)}\", searching it")
                onQ(said); onSearch(said)
            } else MusicLog.add("search: the mic heard nothing")
        } else MusicLog.add("search: the mic was closed without words")
    }
    val startMic: () -> Unit = {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a song, album or artist")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        MusicLog.add("search: mic tapped")
        try { voice.launch(i) } catch (e: ActivityNotFoundException) {
            MusicLog.add("search: no speech recogniser on this phone")
            ActLog.add("MUSIC", "mic search: no speech recogniser found")
            Snack.show("Speech to text isn't available on this phone")
        } catch (e: Exception) {
            MusicLog.add("search: the mic couldn't start (${e.message})")
            Snack.show("Couldn't start the mic")
        }
    }

    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
        Row(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF212121)).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), Alignment.CenterStart) {
                if (q.isEmpty()) Text("Search songs, albums, artists, podcasts", color = Color(0xFF8A8A8A), fontSize = 16.sp, maxLines = 1)
                BasicTextField(
                    q, onQ, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp), cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (q.isNotBlank()) onSearch(q.trim()) }),
                )
            }
            if (q.isNotEmpty()) IconButton({ onQ(""); runCatching { focus.requestFocus() } }, Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, "Clear", Modifier.size(20.dp), tint = sub)
            }
            IconButton(startMic, Modifier.size(40.dp)) { Icon(Icons.Default.Mic, "Search by voice", Modifier.size(22.dp), tint = Color.White) }
            IconButton({
                MusicLog.add("search: song recognition tapped (not built yet, shows coming soon)")
                Snack.show("Song recognition is coming soon")
            }, Modifier.size(40.dp)) { Icon(Icons.Default.GraphicEq, "What's this song", Modifier.size(22.dp), tint = Color.White) }
        }
    }
}
