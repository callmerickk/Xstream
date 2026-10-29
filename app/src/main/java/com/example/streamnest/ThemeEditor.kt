package com.example.streamnest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A theme the person made: a name and six colours. Saved in their settings (which sync with their account), so it is on
 * every phone they sign in on.
 */
@Serializable
data class CustomTheme(
    val name: String, val accent: String, val accent2: String, val background: String, val tint: String,
    val button: String, val buttonText: String,
)

object CustomThemes {
    fun list(vm: AppViewModel): List<CustomTheme> =
        runCatching { Api.json.decodeFromString<List<CustomTheme>>(vm.texts.value["customThemes"] ?: "[]") }.getOrDefault(emptyList())

    fun save(vm: AppViewModel, t: CustomTheme) {
        val l = list(vm).filter { !it.name.equals(t.name, true) } + t
        vm.setText("customThemes", Api.json.encodeToString(l))
        apply(vm, t)
    }

    fun delete(vm: AppViewModel, name: String) {
        vm.setText("customThemes", Api.json.encodeToString(list(vm).filter { it.name != name }))
    }

    fun apply(vm: AppViewModel, t: CustomTheme) {
        vm.setText("theme", "custom:" + t.name)
        vm.setText("accent", t.accent); vm.setText("accent2", t.accent2)
        vm.setText("background", t.background); vm.setText("deckTint", t.tint)
        vm.setText("btnColor", t.button); vm.setText("btnText", t.buttonText)
    }
}

fun colorHex(c: Color) = String.format("#%06X", c.toArgb() and 0xFFFFFF)

/** WCAG contrast between two colours (1 = same, 21 = black on white). */
fun contrastOf(a: Color, b: Color): Float {
    fun l(c: Color): Float {
        fun ch(v: Float) = if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        return 0.2126f * ch(c.red) + 0.7152f * ch(c.green) + 0.0722f * ch(c.blue)
    }
    val x = l(a); val y = l(b)
    return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
}

/** Layout › Theme › Create theme: a name and six colours, each from a colour wheel or a code. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ThemeEditor(vm: AppViewModel, start: CustomTheme?, onDone: () -> Unit) {
    var name by remember { mutableStateOf(start?.name ?: "") }
    val cols = remember {
        mutableStateMapOf(
            "accent" to (parseColor(start?.accent ?: "") ?: Palette.accent),
            "accent2" to (parseColor(start?.accent2 ?: "") ?: Palette.accent2),
            "background" to (parseColor(start?.background ?: "") ?: Palette.bg),
            "tint" to (parseColor(start?.tint ?: "") ?: Palette.tint),
            "button" to (parseColor(start?.button ?: "") ?: Palette.button),
            "buttonText" to (parseColor(start?.buttonText ?: "") ?: Palette.onButton),
        )
    }
    var editing by remember { mutableStateOf("accent") }
    val labels = linkedMapOf("accent" to "Accent", "accent2" to "Second accent", "background" to "Background", "tint" to "Top tint",
        "button" to "Button", "buttonText" to "Button text")
    val btn = cols["button"]!!; val btx = cols["buttonText"]!!; val bg = cols["background"]!!; val acc = cols["accent"]!!
    val textLow = contrastOf(btn, btx) < 3f
    val buttonLow = contrastOf(btn, bg) < 1.6f
    val accentLow = contrastOf(acc, bg) < 1.6f
    androidx.activity.compose.BackHandler { onDone() }
    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
        PageHeader(if (start == null) "Create theme" else "Edit theme", onDone)
        Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BasicTextField(name, { name = it.take(24) }, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold), cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x14FFFFFF)).padding(14.dp),
                decorationBox = { inner -> Box { if (name.isEmpty()) Text("Theme name, e.g. Ocean Night", color = Color(0xFF8A94A8), fontSize = 16.sp); inner() } })
            // The six colours: tap one to edit it with the wheel below.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEach { (k, l) ->
                    val on = k == editing
                    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Color(0x26FFFFFF) else Color(0x0DFFFFFF))
                        .border(1.dp, if (on) Color.White else Color(0x22FFFFFF), RoundedCornerShape(12.dp)).clickable { editing = k }
                        .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(cols[k]!!).border(1.dp, Color(0x55FFFFFF), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(l, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            ColorWheelPicker(labels[editing]!!, cols[editing]!!) { cols[editing] = it }
            // Preview: real buttons on the theme's background.
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Brush.verticalGradient(listOf(cols["tint"]!!, bg))).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Preview", fontSize = 12.sp, color = Color(0xFFB6BDCC), fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Selected", Modifier.clip(RoundedCornerShape(50)).background(btn).padding(horizontal = 14.dp, vertical = 8.dp), color = btx, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Not selected", Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF22232A)).padding(horizontal = 14.dp, vertical = 8.dp), color = Color.White, fontSize = 13.sp)
                    Text("Play", Modifier.clip(RoundedCornerShape(50)).background(acc).padding(horizontal = 14.dp, vertical = 8.dp), color = buttonTextFor(acc), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Brush.horizontalGradient(listOf(acc, cols["accent2"]!!))))
            }
            val warn = when {
                textLow -> "Button text is hard to read on the button colour. Pick a lighter or darker one."
                buttonLow -> "The button colour almost disappears on the background. Pick a brighter one."
                accentLow -> "The accent almost disappears on the background. Pick a brighter one."
                else -> null
            }
            warn?.let { Text(it, color = Color(0xFFFFCF5A), fontSize = 13.sp) }
            Text(if (name.isBlank()) "Give the theme a name" else "Save theme",
                Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(if (warn == null && name.isNotBlank()) btn else Color(0xFF2A2B33))
                    .clickable(enabled = warn == null && name.isNotBlank()) {
                        CustomThemes.save(vm, CustomTheme(name.trim(), colorHex(acc), colorHex(cols["accent2"]!!), colorHex(bg), colorHex(cols["tint"]!!), colorHex(btn), colorHex(btx)))
                        Snack.show("“${name.trim()}” saved to your account")
                        onDone()
                    }.padding(vertical = 14.dp),
                color = if (warn == null && name.isNotBlank()) btx else Color(0xFF8A94A8), fontWeight = FontWeight.Bold, fontSize = 15.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** A colour wheel (drag round for the colour, in and out for how strong) with a brightness slider and the code, all in step. */
@Composable
fun ColorWheelPicker(label: String, value: Color, onChange: (Color) -> Unit) {
    val hsv = remember(value) { FloatArray(3).also { android.graphics.Color.colorToHSV(value.toArgb(), it) } }
    var hue by remember(label) { mutableFloatStateOf(hsv[0]) }
    var sat by remember(label) { mutableFloatStateOf(hsv[1]) }
    var bri by remember(label) { mutableFloatStateOf(hsv[2].coerceAtLeast(0.02f)) }
    var code by remember(label) { mutableStateOf(colorHex(value)) }
    fun emit() { val c = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, bri))); code = colorHex(c); onChange(c) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x0DFFFFFF)).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9AA3B5), letterSpacing = 1.sp)
            BasicTextField(code, { t ->
                code = t.take(7)
                parseColor(t)?.takeIf { t.trim().removePrefix("#").length == 6 }?.let { c ->
                    val h = FloatArray(3); android.graphics.Color.colorToHSV(c.toArgb(), h); hue = h[0]; sat = h[1]; bri = h[2]; onChange(c)
                }
            }, singleLine = true, textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 14.sp), cursorBrush = SolidColor(Color.White),
                modifier = Modifier.width(96.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x33000000)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp))
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.size(220.dp), Alignment.Center) {
            fun pick(o: Offset, w: Float) {
                val c = w / 2f; val dx = o.x - c; val dy = o.y - c
                hue = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360) % 360).toFloat()
                sat = (hypot(dx, dy) / c).coerceIn(0f, 1f)
                emit()
            }
            Canvas(Modifier.fillMaxSize()
                .pointerInput(label) { detectTapGestures { pick(it, size.width.toFloat()) } }
                .pointerInput(label) { detectDragGestures { ch, _ -> pick(ch.position, size.width.toFloat()); ch.consume() } }) {
                val r = size.minDimension / 2f
                drawCircle(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)), r)
                drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), center, r), r)
                // Darker when the brightness slider is down.
                drawCircle(Color.Black.copy(alpha = 1f - bri), r)
                val a = Math.toRadians(hue.toDouble())
                val p = Offset(center.x + (cos(a) * sat * r).toFloat(), center.y + (sin(a) * sat * r).toFloat())
                drawCircle(Color.Black, 13.dp.toPx(), p, style = Stroke(4.dp.toPx()))
                drawCircle(Color.White, 12.dp.toPx(), p, style = Stroke(2.5.dp.toPx()))
                drawCircle(value, 9.dp.toPx(), p)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Brightness", fontSize = 12.sp, color = Color(0xFF9AA3B5))
            Slider(bri, { bri = it.coerceIn(0.02f, 1f); emit() }, Modifier.weight(1f).padding(start = 10.dp),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = value, inactiveTrackColor = Color(0x33FFFFFF)))
        }
    }
}
