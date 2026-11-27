package com.pageflow

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

fun appColors(t: ReaderTheme): ColorScheme {
    val bg = Color(t.header)
    val on = Color(t.ink)
    val base = if (t.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = if (t.dark) Color(0xFF8FB8E8) else Color(0xFF2F5D8A),
        onPrimary = if (t.dark) Color(0xFF0B1B2E) else Color.White,
        background = bg, onBackground = on, surface = bg, onSurface = on,
        surfaceVariant = Color(t.page), onSurfaceVariant = on.copy(alpha = 0.72f),
        surfaceContainer = Color(t.page), surfaceContainerHigh = Color(t.page), surfaceContainerHighest = Color(t.page),
        surfaceContainerLow = bg, surfaceContainerLowest = bg,
        outline = on.copy(alpha = 0.4f), outlineVariant = on.copy(alpha = 0.2f)
    )
}

fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("PageFlow", text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

@Composable
fun BarBtn(label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun ReaderError(title: String, detail: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp)) {
        Text(title, fontWeight = FontWeight.Bold)
        Text(detail, modifier = Modifier.padding(top = 8.dp))
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
fun GoToDialog(count: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var t by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page (1–$count)") },
        text = { OutlinedTextField(t, { t = it.filter { c -> c.isDigit() }.take(6) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { t.toIntOrNull()?.let { onGo((it - 1).coerceIn(0, (count - 1).coerceAtLeast(0))) }; onDismiss() }) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Header and footer overlays. They float above the page (the page never resizes) and animate with fade + slide.
 * [visible] is a State so only this composable recomposes when the controls are toggled.
 */
@Composable
fun BoxScope.ChromeBars(
    visible: State<Boolean>, theme: ReaderTheme,
    top: @Composable RowScope.() -> Unit, bottom: @Composable RowScope.() -> Unit
) {
    val ink = Color(theme.ink)
    AnimatedVisibility(
        visible.value, Modifier.align(Alignment.TopCenter),
        enter = fadeIn(tween(240)) + slideInVertically(tween(280)) { -it / 2 },
        exit = fadeOut(tween(200)) + slideOutVertically(tween(240)) { -it / 2 }
    ) {
        Surface(color = Color(theme.header), contentColor = ink, shadowElevation = 3.dp) {
            Column {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBarsIgnoringVisibility))
                Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, content = top)
            }
        }
    }
    AnimatedVisibility(
        visible.value, Modifier.align(Alignment.BottomCenter),
        enter = fadeIn(tween(240)) + slideInVertically(tween(280)) { it / 2 },
        exit = fadeOut(tween(200)) + slideOutVertically(tween(240)) { it / 2 }
    ) {
        Surface(color = Color(theme.footer), contentColor = ink, shadowElevation = 3.dp) {
            Column {
                Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, content = bottom)
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBarsIgnoringVisibility))
            }
        }
    }
}

/** Floating toolbar: Copy · Bangla Dictionary · Google Translate · Highlight (5 colours). */
@Composable
fun SelectionToolbar(
    centerX: Float, aboveY: Float, belowY: Float, minTop: Float,
    showParagraph: Boolean, onParagraph: () -> Unit,
    onCopy: () -> Unit, onDictionary: () -> Unit, onTranslate: () -> Unit, onHighlight: (Int) -> Unit
) {
    var colors by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Surface(
            shape = RoundedCornerShape(14.dp), shadowElevation = 8.dp, tonalElevation = 2.dp,
            modifier = Modifier.layout { m, c ->
                val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
                layout(c.maxWidth, c.maxHeight) {
                    val x = (centerX - p.width / 2f).coerceIn(8f, (c.maxWidth - p.width - 8f).coerceAtLeast(8f))
                    val y = if (aboveY - p.height >= minTop) aboveY - p.height else belowY
                    p.place(x.roundToInt(), y.roundToInt())
                }
            }
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!colors) {
                    val st = 13.sp
                    TextButton(onClick = onCopy) { Text("Copy", fontSize = st) }
                    TextButton(onClick = onDictionary) { Text("Dictionary", fontSize = st) }
                    TextButton(onClick = onTranslate) { Text("Translate", fontSize = st) }
                    TextButton(onClick = { colors = true }) { Text("Highlight", fontSize = st) }
                    if (showParagraph) TextButton(onClick = onParagraph) { Text("¶", fontSize = 16.sp) }
                } else {
                    for ((i, c) in HlColors.all.withIndex()) {
                        Box(Modifier.padding(6.dp).size(30.dp).background(Color(c), CircleShape)
                            .border(1.dp, Color(0x55000000), CircleShape).clickable { onHighlight(i) })
                    }
                    TextButton(onClick = { colors = false }) { Text("✕") }
                }
            }
        }
    }
}

/** Menu shown when tapping an existing highlight: change colour or delete. */
@Composable
fun HighlightMenu(centerX: Float, aboveY: Float, belowY: Float, minTop: Float, onColor: (Int) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Surface(
            shape = RoundedCornerShape(14.dp), shadowElevation = 8.dp,
            modifier = Modifier.layout { m, c ->
                val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
                layout(c.maxWidth, c.maxHeight) {
                    val x = (centerX - p.width / 2f).coerceIn(8f, (c.maxWidth - p.width - 8f).coerceAtLeast(8f))
                    val y = if (aboveY - p.height >= minTop) aboveY - p.height else belowY
                    p.place(x.roundToInt(), y.roundToInt())
                }
            }
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                for ((i, c) in HlColors.all.withIndex()) {
                    Box(Modifier.padding(6.dp).size(30.dp).background(Color(c), CircleShape)
                        .border(1.dp, Color(0x55000000), CircleShape).clickable { onColor(i) })
                }
                TextButton(onClick = onDelete) { Text("Delete", fontSize = 13.sp) }
                TextButton(onClick = onClose) { Text("✕") }
            }
        }
    }
}

/** Draggable selection handle; [onMove] receives the finger target in screen pixels. */
@Composable
fun SelHandle(x: Float, y: Float, color: Color, onMove: (Float, Float) -> Unit, onEnd: () -> Unit) {
    val cx by rememberUpdatedState(x)
    val cy by rememberUpdatedState(y)
    val moveState by rememberUpdatedState(onMove)
    val endState by rememberUpdatedState(onEnd)
    var startX = 0f; var startY = 0f
    var acc = Offset.Zero
    Box(
        Modifier.offset2(x - 20f, y)
            .size(40.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { startX = cx; startY = cy; acc = Offset.Zero },
                    onDragEnd = { endState() }, onDragCancel = { endState() }
                ) { change, drag ->
                    change.consume()
                    acc += drag
                    moveState(startX + acc.x, startY + acc.y - 22.dp.toPx())
                }
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Box(Modifier.size(18.dp).background(color, CircleShape).border(2.dp, Color.White, CircleShape))
    }
}

private fun Modifier.offset2(x: Float, y: Float): Modifier = this.layout { m, c ->
    val p = m.measure(c)
    layout(p.width, p.height) { p.place(x.roundToInt(), y.roundToInt()) }
}

@Composable
fun DictionaryDialog(word: String, app: AppState, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val res by produceState<DictResult?>(null, word) { value = withContext(Dispatchers.IO) { app.dict.lookup(word) } }
    val total by produceState(-1L) { value = withContext(Dispatchers.IO) { app.dict.count() } }
    val speaker = remember { Speaker(ctx) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    var note by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(word, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = { note = speaker.speak(word) }) { Text("🔊") }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val r = res
                when {
                    r == null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    r.meanings.isNotEmpty() -> {
                        if (r.viaRoot && r.matched != null) Text("Root form: ${r.matched}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(r.meanings.first(), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 6.dp))
                        if (r.meanings.size > 1) {
                            Text("Other meanings", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            for (m in r.meanings.drop(1).take(12)) Text("• $m", Modifier.padding(top = 2.dp))
                        }
                    }
                    total == 0L -> Text("No dictionary is installed. PageFlow does not ship dictionary data. " +
                        "Import your own English–Bengali word list (TSV or CSV) in Settings → Dictionary, or use the links below.")
                    else -> Text("No entry found for “$word” in your dictionary.")
                }
                note?.let { Text(it, Modifier.padding(top = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { External.open(ctx, External.wiktionaryUrl(word)) }) { Text("Wiktionary", fontSize = 12.sp) }
                    OutlinedButton(onClick = { External.open(ctx, External.translateUrl(word)) }) { Text("Translate", fontSize = 12.sp) }
                }
                Text("Wiktionary and Translate need an internet connection.", Modifier.padding(top = 6.dp), fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

// ------------------------------------------------------------------ themes
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSheet(app: AppState, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val cur = app.theme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(cur.header), contentColor = Color(cur.ink)
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            if (!editing) {
                Text("Reading theme", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
                val all = Themes.presets + listOfNotNull(app.custom)
                for (row in all.chunked(3)) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (t in row) ThemeCard(t, t.id == app.themeId, Modifier.weight(1f)) { app.selectTheme(t.id) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { editing = true }) { Text("Custom theme…") }
                    OutlinedButton(onClick = { app.resetTheme() }) { Text("Reset to default") }
                }
                Text("Documents keep their own text colours. Themes change the page background and automatic text.",
                    fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp), color = Color(cur.ink).copy(alpha = 0.7f))
            } else {
                CustomThemeEditor(app.custom ?: cur, onSave = { app.saveCustom(it); editing = false }, onCancel = { editing = false })
            }
        }
    }
}

@Composable
private fun ThemeCard(t: ReaderTheme, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clickable(onClick = onClick)
            .border(if (selected) BorderStroke(2.5.dp, Color(0xFF2F5D8A)) else BorderStroke(1.dp, Color(0x33000000)), RoundedCornerShape(12.dp))
            .background(Color(t.page), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Box(Modifier.fillMaxWidth().height(10.dp).background(Color(t.header), RoundedCornerShape(3.dp)))
        Text("Aa  অ", Modifier.padding(vertical = 8.dp), color = Color(t.ink), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(t.name, color = Color(t.ink), fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
fun CustomThemeEditor(initial: ReaderTheme, onSave: (ReaderTheme) -> Unit, onCancel: () -> Unit) {
    var t by remember { mutableStateOf(initial) }
    var which by remember { mutableIntStateOf(0) }
    val labels = listOf("Page", "Text", "Header", "Footer")
    fun get(i: Int) = when (i) { 0 -> t.page; 1 -> t.ink; 2 -> t.header; else -> t.footer }
    fun put(i: Int, c: Int) { t = when (i) { 0 -> t.copy(page = c); 1 -> t.copy(ink = c); 2 -> t.copy(header = c); else -> t.copy(footer = c) } }
    val hsv0 = colorToHsv(get(which))
    var hue by remember(which) { mutableFloatStateOf(hsv0.first) }
    var sat by remember(which) { mutableFloatStateOf(hsv0.second) }
    var v by remember(which) { mutableFloatStateOf(hsv0.third) }
    var hex by remember(which) { mutableStateOf(toHex(get(which))) }
    fun fromHsv() { val c = hsvToColor(hue, sat, v); put(which, c); hex = toHex(c) }
    val col = get(which)

    Text("Custom theme", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
    // live preview
    Column(Modifier.fillMaxWidth().border(1.dp, Color(0x33000000), RoundedCornerShape(12.dp))) {
        Box(Modifier.fillMaxWidth().background(Color(t.header), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).padding(10.dp)) {
            Text("PageFlow header", color = bestOn(t.header), fontSize = 13.sp)
        }
        Box(Modifier.fillMaxWidth().background(Color(t.page)).padding(14.dp)) {
            Text("The quick brown fox jumps over the lazy dog.\nবাংলা লিপির নমুনা: ক্ষ জ্ঞ শ্রী", color = Color(t.ink), fontSize = 16.sp)
        }
        Box(Modifier.fillMaxWidth().background(Color(t.footer), RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)).padding(10.dp)) {
            Text("Footer  ·  page 3 / 12", color = bestOn(t.footer), fontSize = 13.sp)
        }
    }
    Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0..3) {
            if (i == which) Button(onClick = {}, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) { Text(labels[i], fontSize = 13.sp) }
            else OutlinedButton(onClick = { which = i }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) { Text(labels[i], fontSize = 13.sp) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(Color(col), CircleShape).border(1.dp, Color(0x55000000), CircleShape))
        OutlinedTextField(hex, {
            hex = it
            parseHex(it)?.let { c -> put(which, c); val h = colorToHsv(c); hue = h.first; sat = h.second; v = h.third }
        }, singleLine = true, label = { Text("HEX") }, modifier = Modifier.padding(start = 12.dp).width(150.dp))
        Text("  R ${(col shr 16) and 255}  G ${(col shr 8) and 255}  B ${col and 255}", fontSize = 12.sp)
    }
    Text("Hue", fontSize = 12.sp); Slider(hue, { hue = it; fromHsv() }, valueRange = 0f..360f)
    Text("Saturation", fontSize = 12.sp); Slider(sat, { sat = it; fromHsv() }, valueRange = 0f..1f)
    Text("Brightness", fontSize = 12.sp); Slider(v, { v = it; fromHsv() }, valueRange = 0f..1f)
    val warn = Themes.warning(t)
    if (warn != null) {
        Text(warn, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        TextButton(onClick = { t = t.copy(ink = if (relLum(t.page) > 0.5f) 0xFF1A1A1A.toInt() else 0xFFE6E6E6.toInt()); hex = toHex(get(which)) }) { Text("Fix text colour") }
    }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onSave(t) }) { Text("Save theme") }
        OutlinedButton(onClick = { t = initial; which = 0 }) { Text("Undo") }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}
