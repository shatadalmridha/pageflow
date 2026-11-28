package com.pageflow

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.Typeface as ComposeTypeface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()          // content never resizes when system bars hide, so the page stays still
        super.onCreate(savedInstanceState)
        val app = AppState(applicationContext)
        val initial = intent?.data
        setContent { PageFlowApp(app, initial) }
    }
}

sealed interface Screen {
    object Home : Screen
    class Docx(val uri: Uri) : Screen
    class Pdf(val uri: Uri) : Screen
    object Settings : Screen
}

fun nameOf(ctx: Context, uri: Uri): String = try {
    if (uri.scheme == "file") File(uri.path ?: "").name.ifEmpty { "Document" }
    else ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && i >= 0) c.getString(i) else null
    } ?: (uri.lastPathSegment ?: "Document")
} catch (_: Throwable) { uri.lastPathSegment ?: "Document" }

@Composable
fun PageFlowApp(app: AppState, initial: Uri?) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val theme = app.theme
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }

    SideEffect {
        val w = (view.context as? Activity)?.window
        if (w != null) {
            val c = WindowCompat.getInsetsController(w, view)
            c.isAppearanceLightStatusBars = !theme.dark
            c.isAppearanceLightNavigationBars = !theme.dark
        }
    }
    LaunchedEffect(initial) {
        if (initial != null) {
            val (u, type) = withContext(Dispatchers.IO) {
                val name = nameOf(ctx, initial)
                val t = (ctx.contentResolver.getType(initial) ?: "")
                val pdf = t.contains("pdf") || name.endsWith(".pdf", true)
                Pair(app.persistAccess(initial), if (pdf) "pdf" else "docx")
            }
            app.addRecent(u.toString(), nameOf(ctx, u), type)
            stack.add(if (type == "pdf") Screen.Pdf(u) else Screen.Docx(u))
        }
    }
    BackHandler(stack.size > 1) { stack.removeAt(stack.size - 1) }
    MaterialTheme(colorScheme = appColors(theme)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val s = stack.last()) {
                is Screen.Home -> Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                    Home(app,
                        onOpen = { uri, type ->
                            app.addRecent(uri.toString(), nameOf(ctx, uri), type)
                            stack.add(if (type == "pdf") Screen.Pdf(uri) else Screen.Docx(uri))
                        },
                        onSettings = { stack.add(Screen.Settings) })
                }
                is Screen.Docx -> DocxReader(app, s.uri) { stack.removeAt(stack.size - 1) }
                is Screen.Pdf -> PdfReader(app, s.uri) { stack.removeAt(stack.size - 1) }
                is Screen.Settings -> Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                    SettingsScreen(app) { stack.removeAt(stack.size - 1) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Home
const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

@Composable
fun Home(app: AppState, onOpen: (Uri, String) -> Unit, onSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    var stale by remember { mutableStateOf<Recent?>(null) }
    var showTheme by remember { mutableStateOf(false) }
    fun pick(uri: Uri?, type: String) {
        if (uri == null) return
        scope.launch {
            val u = withContext(Dispatchers.IO) { app.persistAccess(uri) }
            onOpen(u, type)
        }
    }
    val docxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { pick(it, "docx") }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { pick(it, "pdf") }
    var q by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.background(Color(0xFF2F5D8A), RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text("P", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("PageFlow", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Smooth Document Reader", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { showTheme = true }) { Text("Theme") }
            TextButton(onClick = onSettings) { Text("Settings") }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { docxPicker.launch(arrayOf(DOCX_MIME)) }, modifier = Modifier.weight(1f)) { Text("Open DOCX") }
            OutlinedButton(onClick = { pdfPicker.launch(arrayOf("application/pdf")) }, modifier = Modifier.weight(1f)) { Text("Open PDF") }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("Search recent documents") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Text("Recent", fontWeight = FontWeight.SemiBold)
        val shown = app.recents.filter { it.name.contains(q, ignoreCase = true) }
        if (shown.isEmpty()) {
            Text("Nothing here yet. Open a DOCX or PDF to begin.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            items(shown) { r ->
                Card(Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { app.accessible(Uri.parse(r.uri)) }
                        if (ok) onOpen(Uri.parse(r.uri), r.type) else stale = r
                    }
                }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.type.uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(54.dp))
                        Text(r.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    if (showTheme) ThemeSheet(app) { showTheme = false }
    stale?.let { r ->
        AlertDialog(
            onDismissRequest = { stale = null },
            title = { Text("File not accessible") },
            text = { Text("“${r.name}” was moved, deleted, or its access was revoked. You can remove it from Recents and open it again with Open DOCX / Open PDF.") },
            confirmButton = { TextButton(onClick = { app.removeRecent(r); stale = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { stale = null }) { Text("Keep") } }
        )
    }
}

@Composable
fun FullscreenEffect(chrome: State<Boolean>) {
    val view = LocalView.current
    LaunchedEffect(Unit) {
        snapshotFlow { chrome.value }.collect { show ->
            val w = (view.context as? Activity)?.window ?: return@collect
            val c = WindowCompat.getInsetsController(w, view)
            if (show) c.show(WindowInsetsCompat.Type.systemBars()) else {
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val w = (view.context as? Activity)?.window
            if (w != null) WindowCompat.getInsetsController(w, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

// ------------------------------------------------------------------ Settings + Font Manager + Dictionary
fun importFont(ctx: Context, app: AppState, uri: Uri): String? {
    val name = nameOf(ctx, uri).replace(Regex("[^A-Za-z0-9._\\[\\],-]"), "_")
    if (name.substringAfterLast('.').lowercase() !in setOf("ttf", "otf")) return "Please choose a .ttf or .otf file."
    val dest = File(app.fonts.dir, name)
    return try {
        val head = ByteArray(4)
        val n = ctx.contentResolver.openInputStream(uri)?.use { it.read(head) } ?: -1
        if (n < 4 || !FontMatch.isSfnt(head)) return "That file isn't a valid TrueType/OpenType font."
        ctx.contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        app.fonts.refresh()
        if (app.fonts.load(name) == null) { dest.delete(); app.fonts.refresh(); "That font couldn't be loaded by Android." }
        else { app.fontsVersion++; null }
    } catch (e: Throwable) { dest.delete(); app.fonts.refresh(); e.message ?: "Import failed" }
}

@Composable
fun SettingsScreen(app: AppState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var dictMsg by remember { mutableStateOf<String?>(null) }
    var dictCount by remember { mutableStateOf(-1L) }
    var showTheme by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { dictCount = withContext(Dispatchers.IO) { app.dict.count() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch { msg = withContext(Dispatchers.IO) { importFont(ctx, app, u) } }
    }
    val dictPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            dictMsg = "Importing…"
            dictMsg = try {
                val n = withContext(Dispatchers.IO) { app.dict.import(u) { c -> dictMsg = "Importing… $c" } }
                dictCount = withContext(Dispatchers.IO) { app.dict.count() }
                "Imported $n entries."
            } catch (e: Throwable) { "Import failed: ${e.message}" }
        }
    }
    val sample = "বাংলা লিপির নমুনা: ক্ষ জ্ঞ শ্রী দ্ধ — The quick brown fox"
    val files = remember(app.fontsVersion) { app.fonts.list }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                BarBtn("‹", onBack); Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Text("Reading theme", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Current: ${app.theme.name}", Modifier.weight(1f))
                Button(onClick = { showTheme = true }) { Text("Change…") }
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }
        item {
            Text("Reading mode", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("DOCX: continuous scrolling", Modifier.weight(1f)); Switch(app.docContinuous, { app.setViewModes(doc = it) })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("PDF: continuous scrolling", Modifier.weight(1f)); Switch(app.pdfContinuous, { app.setViewModes(pdf = it) })
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }
        item { Text("Font manager", fontWeight = FontWeight.SemiBold) }
        item {
            Row(Modifier.fillMaxWidth().clickable { app.readingFont = null; app.persist() }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(app.readingFont == null, { app.readingFont = null; app.persist() }); Text("None (use document / system fonts)")
            }
        }
        items(files) { f ->
            val tf = app.fonts.load(f.name)
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(app.readingFont == f.name, { app.readingFont = f.name; app.persist() })
                    Text(f.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = {
                        f.delete(); if (app.readingFont == f.name) app.readingFont = null
                        app.fonts.refresh(); app.fontsVersion++; app.persist()
                    }) { Text("Remove") }
                }
                if (tf != null) Text(sample, fontFamily = FontFamily(ComposeTypeface(tf)), fontSize = 18.sp, modifier = Modifier.padding(start = 48.dp))
            }
        }
        item {
            Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.padding(top = 8.dp)) { Text("Import TTF / OTF font") }
            msg?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Override document fonts")
                    Text("Off: keep the document's fonts; the selected font replaces missing Bengali fonts only.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(app.overrideFonts, { app.overrideFonts = it; app.persist() })
            }
        }
        item {
            Text("Font size: ${(app.fontScale * 100).toInt()}%", modifier = Modifier.padding(top = 12.dp))
            Slider(app.fontScale, { app.fontScale = it }, valueRange = 0.6f..2f, onValueChangeFinished = { app.persist() })
            Text("Line spacing: ${"%.2f".format(app.lineSpacing)}×")
            Slider(app.lineSpacing, { app.lineSpacing = it }, valueRange = 0.8f..2f, onValueChangeFinished = { app.persist() })
            OutlinedButton(onClick = { app.resetFontSettings() }) { Text("Reset font settings") }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }
        item {
            Text("Dictionary", fontWeight = FontWeight.SemiBold)
            Text(if (dictCount <= 0L) "No dictionary installed." else "$dictCount entries installed (offline).", Modifier.padding(top = 4.dp))
            Text("PageFlow ships no dictionary data and never invents meanings. Import your own word list: a UTF-8 text file with one " +
                "entry per line, \"word<TAB>meaning\" or \"word,meaning\" (several meanings separated by ; or |). English → Bengali and " +
                "Bengali → English lists both work. Look-ups use only your list.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { dictPicker.launch(arrayOf("*/*")) }) { Text("Import list…") }
                OutlinedButton(onClick = { scope.launch { withContext(Dispatchers.IO) { app.dict.clear() }; dictCount = 0; dictMsg = "Cleared." } }) { Text("Clear") }
            }
            dictMsg?.let { Text(it, Modifier.padding(top = 4.dp), fontSize = 13.sp) }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }
        item {
            Text("Storage", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    File(ctx.cacheDir, "current.docx").delete(); File(ctx.cacheDir, "media").deleteRecursively(); File(ctx.cacheDir, "pdf_text.pdf").delete()
                }) { Text("Clear cache") }
                OutlinedButton(onClick = { app.clearRecents() }) { Text("Clear recents") }
            }
            Text("Highlights are stored privately inside the app, separately from your documents.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        item { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }
        item {
            Text("About", fontWeight = FontWeight.SemiBold)
            Text("PageFlow 0.3.1 · a document reader, not an editor. Offline except Google Translate / Wiktionary links. " +
                "Documents are read from a temporary copy; your original files are never modified.",
                fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
            Text("Reads: DOCX (own renderer: not identical to Word, see FEATURES.md) and PDF. Selection, highlights, themes, dictionary import.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 32.dp))
        }
    }
    if (showTheme) ThemeSheet(app) { showTheme = false }
}

// ------------------------------------------------------------------ DOCX reader
/** Latest values for gesture callbacks (pointer handlers outlive a single composition). */
private class Live {
    var sel: DocSel? = null
    var geom: Geom? = null
    var pages: List<Page> = emptyList()
    var idxs: List<Int> = emptyList()
    var byPara: Map<Int, List<Hl>> = emptyMap()
}

@Composable
fun DocSurface(
    pages: List<Page>, idxs: List<Int>, vp: Viewport, theme: ReaderTheme, pagerMode: Boolean,
    store: HighlightStore, sel: DocSel?, onSel: (DocSel?) -> Unit, chrome: MutableState<Boolean>,
    onDictionary: (String) -> Unit, onGeom: (Geom) -> Unit, onVisible: (Int) -> Unit
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val pad = with(density) { 10.dp.toPx() }
    val gap = with(density) { 12.dp.toPx() }
    val minTop = with(density) { 100.dp.toPx() }
    val hls = store.items.toList()
    val byPara = remember(hls) { hls.groupBy { it.para } }
    val live = remember { Live() }
    live.sel = sel; live.pages = pages; live.idxs = idxs; live.byPara = byPara
    var menu by remember { mutableStateOf<Pair<Hl, Offset>?>(null) }
    val paper = remember { Paint() }
    val edge = remember { Paint().apply { style = Paint.Style.STROKE } }
    val fill = remember { Paint() }
    val num = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        val geom = remember(pages, idxs, vw) { docGeom(pages, idxs, vw, pad, gap) }
        live.geom = geom
        LaunchedEffect(geom, vw, vh) {
            vp.st = vp.st.withViewport(vw, vh).withContent(vw, geom.totalH)
            onGeom(geom)
        }
        LaunchedEffect(geom) {
            snapshotFlow { val s = vp.st; geom.firstVisible((-s.ty + vh * 0.35f) / s.scale) }
                .collect { if (idxs.isNotEmpty()) onVisible(idxs[it.coerceIn(0, idxs.size - 1)]) }
        }

        fun hit(sx: Float, sy: Float, loose: Boolean): Anchor? {
            val g = live.geom ?: return null
            if (g.n == 0) return null
            val st = vp.st
            val cx = st.toContentX(sx); val cy = st.toContentY(sy)
            val k = g.firstVisible(cy).coerceIn(0, g.n - 1)
            val pi = live.idxs[k]
            return hitTest(live.pages[pi], pi, g.toPageX(k, cx), g.toPageY(k, cy), loose)
        }
        fun findHl(a: Anchor): Hl? {
            val ti = live.pages[a.page].items.getOrNull(a.item) as? TextItem ?: return null
            return live.byPara[ti.paraId]?.lastOrNull { a.off >= it.start && a.off < it.end }
        }

        val gestures = Modifier.viewportGestures(vp, pagerMode,
            onTap = { o ->
                val a = hit(o.x, o.y, false)
                val h = if (a != null) findHl(a) else null
                when {
                    live.sel != null -> onSel(null)
                    menu != null -> menu = null
                    h != null -> menu = Pair(h, o)
                    else -> chrome.value = !chrome.value
                }
            },
            onDoubleTap = { o -> vp.st = if (vp.st.zoomed) vp.st.zoomTo(1f, o.x, o.y) else vp.st.zoomTo(2.2f, o.x, o.y) },
            onLongPress = { o ->
                val a = hit(o.x, o.y, false)
                if (a != null) wordSelection(live.pages[a.page], a)?.let { menu = null; onSel(it) }
            })

        Canvas(Modifier.fillMaxSize().clipToBounds().then(gestures)) {
            val s = vp.st
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save(); nc.translate(s.tx, s.ty); nc.scale(s.scale, s.scale)
                val y0 = -s.ty / s.scale
                val y1 = y0 + size.height / s.scale
                var k = geom.firstVisible(y0)
                while (k < geom.n && geom.top[k] <= y1) {
                    val pi = idxs[k]
                    val pg = pages[pi]
                    nc.save(); nc.translate(geom.left[k], geom.top[k]); nc.scale(geom.sc[k], geom.sc[k])
                    paper.color = theme.page
                    nc.drawRect(0f, 0f, pg.w, pg.h, paper)
                    edge.color = (theme.ink and 0xFFFFFF) or 0x30000000; edge.strokeWidth = 1f / (geom.sc[k] * s.scale)
                    nc.drawRect(0f, 0f, pg.w, pg.h, edge)
                    PageDrawer.draw(nc, pg, theme.ink, geom.sc[k] * s.scale) { oc ->
                        for (item in pg.items) if (item is TextItem && item.paraId >= 0) {
                            val list = byPara[item.paraId] ?: continue
                            for (h in list) {
                                fill.color = (h.color and 0xFFFFFF) or 0x77000000
                                for (r in itemRects(item, h.start, h.end)) oc.drawRect(r.l, r.t, r.r, r.b, fill)
                            }
                        }
                        val sl = sel
                        if (sl != null && sl.a.page == pi) {
                            fill.color = 0x553B82F6
                            for (r in selRects(pg, sl)) oc.drawRect(r.l, r.t, r.r, r.b, fill)
                        }
                    }
                    num.color = (theme.ink and 0xFFFFFF) or 0xAA000000.toInt(); num.textSize = 9f * S
                    nc.drawText("${pi + 1}", pg.w / 2f, pg.footerY + 3f * S, num)
                    nc.restore()
                    k++
                }
                nc.restore()
            }
        }

        // selection handles + toolbar (only read the viewport while a selection exists)
        val s0 = sel
        if (s0 != null && geom.n > 0 && idxs.contains(s0.a.page)) {
            val k = idxs.indexOf(s0.a.page)
            val pg = pages[s0.a.page]
            val rects = selRects(pg, s0)
            if (rects.isNotEmpty()) {
                val st = vp.st
                fun sx(px: Float) = st.tx + geom.fromPageX(k, px) * st.scale
                fun sy(py: Float) = st.ty + geom.fromPageY(k, py) * st.scale
                val f = rects.first(); val l = rects.last()
                val hc = Color(0xFF2F5D8A)
                SelHandle(sx(f.l), sy(f.b), hc, onMove = { x, y ->
                    hit(x, y, true)?.let { a -> if (a.page == s0.a.page) onSel(DocSel(a, s0.b)) }
                }, onEnd = {})
                SelHandle(sx(l.r), sy(l.b), hc, onMove = { x, y ->
                    hit(x, y, true)?.let { a -> if (a.page == s0.a.page) onSel(DocSel(s0.a, a)) }
                }, onEnd = {})
                val text = selText(pg, s0)
                SelectionToolbar(
                    centerX = (sx(f.l) + sx(l.r)) / 2f, aboveY = sy(f.t) - 10f, belowY = sy(l.b) + with(density) { 48.dp.toPx() }, minTop = minTop,
                    showParagraph = true, onParagraph = { paragraphSelection(pg, s0.a)?.let { onSel(it) } },
                    onCopy = { copyText(ctx, text); onSel(null) },
                    onDictionary = { onDictionary(text.trim().split(Regex("\\s+")).firstOrNull() ?: text) },
                    onTranslate = { External.open(ctx, External.translateUrl(text)) },
                    onHighlight = { ci ->
                        val g = UUID.randomUUID().toString()
                        store.add(selPieces(pg, s0).map { Hl(UUID.randomUUID().toString(), g, -1, it.paraId, it.start, it.end, HlColors.all[ci], it.text) })
                        onSel(null)
                    }
                )
            }
        }
        menu?.let { (h, o) ->
            HighlightMenu(o.x, o.y - 24f, o.y + with(density) { 36.dp.toPx() }, minTop,
                onColor = { ci -> store.recolorGroup(h.group, HlColors.all[ci]); menu = null },
                onDelete = { store.removeGroup(h.group); menu = null }, onClose = { menu = null })
        }
    }
}

@Composable
fun DocxReader(app: AppState, uri: Uri, onBack: () -> Unit) {
    val theme = app.theme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var doc by remember { mutableStateOf<Doc?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var pages by remember { mutableStateOf<List<Page>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    val chrome = remember { mutableStateOf(true) }
    var showSearch by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf<DocSel?>(null) }
    var dictWord by remember { mutableStateOf<String?>(null) }
    var contPage by remember { mutableIntStateOf(0) }
    var contGeom by remember { mutableStateOf<Geom?>(null) }
    var jumpTo by remember { mutableStateOf<Int?>(null) }
    val title = remember(uri) { nameOf(ctx, uri) }
    val target = remember(uri) { app.lastPage(uri.toString()) }
    val engine = remember { AndroidTextEngine(app.fonts) }
    val store = remember(uri) { app.highlights(uri.toString()) }
    val continuous = app.docContinuous
    val pager = rememberPagerState(initialPage = 0) { pages.size.coerceAtLeast(1) }
    val contVp = remember { Viewport(centerY = false) }
    val vpMap = remember { HashMap<Int, Viewport>() }
    val gapPx = with(density) { 12.dp.toPx() }
    val current = if (continuous) contPage else pager.currentPage
    val lastIdx = (pages.size - 1).coerceAtLeast(0)

    FullscreenEffect(chrome)
    DisposableEffect(Unit) { onDispose { PageDrawer.clear() } }

    // The original is only ever read; parsing happens from a cache copy and images go to cache files.
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val f = File(ctx.cacheDir, "current.docx")
                val media = File(ctx.cacheDir, "media")
                media.deleteRecursively()
                val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open this file.")
                input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                doc = DocxParser(f, media).parse()
            } catch (e: DocxException) { err = e.message
            } catch (e: OutOfMemoryError) { err = "This document is too large for this device's memory."
            } catch (e: SecurityException) { err = "Access to this file was revoked. Reopen it from Open DOCX."
            } catch (e: Throwable) { err = e.message ?: e.toString() }
        }
    }
    LaunchedEffect(doc, app.fontScale, app.lineSpacing, app.fontsVersion, app.readingFont, app.overrideFonts, theme.page, theme.ink) {
        val d = doc ?: return@LaunchedEffect
        val first = pages.isEmpty()
        val frac = if (!first) current.toFloat() / pages.size.coerceAtLeast(1) else null
        busy = true
        sel = null
        val cfg = LayoutCfg(app.fontScale, app.lineSpacing, theme.ink, theme.page, app.overrideFonts, app.readingFont)
        val publish: (List<Page>) -> Unit = { l -> pages = l }
        val result = withContext(Dispatchers.Default) {
            try {
                Paginator(d, cfg, engine).run(if (first) publish else null) { !isActive }
            } catch (e: CancellationException) { throw e
            } catch (e: OutOfMemoryError) { err = "Ran out of memory while laying out this document."; null
            } catch (e: Throwable) { err = e.toString(); null }
        }
        if (result != null) {
            pages = result
            if (frac != null) jumpTo = (frac * result.size).toInt().coerceIn(0, result.size - 1)
        }
        busy = false
    }
    LaunchedEffect(pages.size, busy) {
        if (!restored && pages.isNotEmpty() && (pages.size > target || !busy)) {
            jumpTo = target.coerceIn(0, pages.size - 1)
            restored = true
        }
    }
    LaunchedEffect(jumpTo, contGeom, continuous, pages.size) {
        val p = jumpTo ?: return@LaunchedEffect
        if (pages.isEmpty()) return@LaunchedEffect
        val t = p.coerceIn(0, pages.size - 1)
        if (continuous) {
            val g = contGeom ?: return@LaunchedEffect
            if (t < g.n) {
                val st = contVp.st
                contVp.st = st.copy(ty = -(g.top[t] - gapPx) * st.scale).clamped()
                contPage = t
                jumpTo = null
            }
        } else { pager.scrollToPage(t); jumpTo = null }
    }
    LaunchedEffect(current, restored) { if (restored) app.savePage(uri.toString(), current) }
    LaunchedEffect(pager.currentPage) {
        sel = null
        vpMap.filterKeys { it != pager.currentPage }.values.forEach { it.st = it.st.copy(scale = 1f, tx = 0f, ty = 0f).clamped() }
    }

    fun activeVp(): Viewport? = if (continuous) contVp else vpMap[pager.currentPage]
    fun zoom(f: Float) { activeVp()?.let { v -> val s = v.st; v.st = s.zoomBy(f, s.vw / 2f, s.vh / 2f) } }
    fun goTo(p: Int) { jumpTo = p.coerceIn(0, lastIdx) }
    fun setMode(c: Boolean) { val cur = current; app.setViewModes(doc = c); jumpTo = cur }
    val allIdx = remember(pages.size) { List(pages.size) { it } }
    val backdrop = Color(blend(theme.page, theme.ink, if (theme.dark) 0.18f else 0.10f))

    Box(Modifier.fillMaxSize().background(backdrop)) {
        val e = err
        if (e != null && pages.isEmpty()) {
            ReaderError("Couldn't open this document", e, onBack, Modifier.align(Alignment.Center))
        } else if (pages.isEmpty()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else if (continuous) {
            DocSurface(pages, allIdx, contVp, theme, false, store, sel, { sel = it }, chrome,
                { dictWord = it }, { contGeom = it }, { contPage = it })
        } else {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { idx ->
                val vp = remember(idx) { vpMap.getOrPut(idx) { Viewport(true) } }
                val one = remember(idx) { listOf(idx) }
                DocSurface(pages, one, vp, theme, true, store, sel, { sel = it }, chrome, { dictWord = it }, {}, {})
            }
        }
        if (busy && pages.isNotEmpty()) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(top = 90.dp, end = 16.dp).height(22.dp).width(22.dp), strokeWidth = 2.dp)
        if (e != null && pages.isNotEmpty()) Text("Layout stopped early: $e", color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.Center).background(backdrop).padding(8.dp))

        ChromeBars(chrome, theme,
            top = {
                BarBtn("‹", onBack)
                Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { showSearch = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Find", fontSize = 13.sp) }
                TextButton(onClick = { showTheme = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Theme", fontSize = 13.sp) }
                Box {
                    BarBtn("⋮") { showMenu = true }
                    DropdownMenu(showMenu, { showMenu = false }) {
                        DropdownMenuItem(text = { Text(if (continuous) "Single page mode" else "Continuous scrolling") }, onClick = { showMenu = false; setMode(!continuous) })
                        DropdownMenuItem(text = { Text("Text size  +") }, onClick = { app.fontScale = (app.fontScale + 0.1f).coerceAtMost(2f); app.persist() })
                        DropdownMenuItem(text = { Text("Text size  −") }, onClick = { app.fontScale = (app.fontScale - 0.1f).coerceAtLeast(0.6f); app.persist() })
                        DropdownMenuItem(text = { Text("Go to page…") }, onClick = { showMenu = false; showGoTo = true })
                    }
                }
            },
            bottom = {
                BarBtn("‹") { goTo(current - 1) }
                Slider(
                    value = current.toFloat().coerceAtMost(lastIdx.coerceAtLeast(1).toFloat()),
                    onValueChange = { v -> goTo(v.toInt()) },
                    valueRange = 0f..lastIdx.coerceAtLeast(1).toFloat(),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showGoTo = true }, contentPadding = PaddingValues(4.dp)) {
                    Text("${current + 1}/${pages.size.coerceAtLeast(1)}${if (busy) "+" else ""}", fontSize = 13.sp)
                }
                BarBtn("›") { goTo(current + 1) }
                BarBtn("−") { zoom(1f / 1.25f) }
                BarBtn("+") { zoom(1.25f) }
            }
        )
    }

    if (showTheme) ThemeSheet(app) { showTheme = false }
    dictWord?.let { w -> DictionaryDialog(w, app) { dictWord = null } }
    if (showGoTo) GoToDialog(pages.size, { goTo(it) }, { showGoTo = false })
    if (showSearch) {
        var q by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSearch = false },
            title = { Text("Search in document") },
            text = {
                Column {
                    OutlinedTextField(q, { q = it }, singleLine = true)
                    if (status.isNotEmpty()) Text(status, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val n = pages.size
                        val start = current
                        val hit = withContext(Dispatchers.Default) {
                            if (q.isBlank() || n == 0) -1
                            else (1..n).map { (start + it) % n }.firstOrNull { pages[it].text.contains(q, ignoreCase = true) } ?: -1
                        }
                        if (hit >= 0) { status = "Found on page ${hit + 1}"; goTo(hit) } else status = "No match"
                    }
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { showSearch = false }) { Text("Close") } }
        )
    }
}

// ------------------------------------------------------------------ PDF engine wrapper
/** Owns the renderer; every render opens and *always* closes its page; close() waits for in-flight renders. */
class PdfDoc private constructor(private val renderer: PdfRenderer, private val pfd: ParcelFileDescriptor) {
    private val lock = Any()
    @Volatile private var closed = false
    val count: Int = renderer.pageCount
    private val sizes = ConcurrentHashMap<Int, Pair<Int, Int>>()

    fun pageSize(i: Int): Pair<Int, Int>? {
        sizes[i]?.let { return it }
        synchronized(lock) {
            if (closed) return null
            var pg: PdfRenderer.Page? = null
            return try {
                pg = renderer.openPage(i)
                Pair(pg.width.coerceAtLeast(1), pg.height.coerceAtLeast(1)).also { sizes[i] = it }
            } catch (_: Throwable) { null } finally { try { pg?.close() } catch (_: Throwable) {} }
        }
    }

    /** matrix == null: page is scaled to fill the bitmap. Otherwise maps page points to bitmap pixels. */
    fun render(i: Int, bmp: Bitmap, matrix: Matrix?): Boolean {
        synchronized(lock) {
            if (closed) return false
            var pg: PdfRenderer.Page? = null
            return try {
                pg = renderer.openPage(i)
                pg.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                true
            } catch (_: Throwable) { false } finally { try { pg?.close() } catch (_: Throwable) {} }
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try { renderer.close() } catch (_: Throwable) {}
            try { pfd.close() } catch (_: Throwable) {}
        }
    }

    companion object {
        fun open(ctx: Context, uri: Uri): PdfDoc {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open this file.")
            try {
                return PdfDoc(PdfRenderer(pfd), pfd)
            } catch (t: Throwable) {
                try { pfd.close() } catch (_: Throwable) {}
                throw t
            }
        }
    }
}

fun pdfError(e: Throwable): String = when (e) {
    is SecurityException -> "This PDF is password-protected or its access was revoked."
    is OutOfMemoryError -> "Not enough memory to open this PDF."
    else -> e.message ?: "This file couldn't be opened as a PDF."
}

fun renderFit(pdf: PdfDoc, index: Int, widthPx: Int): Bitmap? {
    val (pw, ph) = pdf.pageSize(index) ?: return null
    val w = widthPx.coerceIn(100, 2400)
    val h = (w.toLong() * ph / pw).toInt().coerceIn(1, 6000)
    var bmp: Bitmap? = null
    return try {
        bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        if (pdf.render(index, bmp, null)) bmp else { bmp.recycle(); null }
    } catch (_: Throwable) { bmp?.recycle(); null }   // OutOfMemoryError ends up here
}

/** Renders only the visible viewport at the current zoom, so text stays sharp with constant memory. */
fun renderRegion(pdf: PdfDoc, index: Int, vw: Int, vh: Int, scale: Float, ox: Float, oy: Float, cw: Float, ch: Float): Bitmap? {
    var bmp: Bitmap? = null
    return try {
        bmp = Bitmap.createBitmap(vw.coerceAtLeast(1), vh.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.TRANSPARENT)
        val white = Paint().apply { color = android.graphics.Color.WHITE }
        android.graphics.Canvas(bmp).drawRect(ox, oy, ox + cw, oy + ch, white)
        val m = Matrix().apply { setScale(scale, scale); postTranslate(ox, oy) }
        if (pdf.render(index, bmp, m)) bmp else { bmp.recycle(); null }
    } catch (_: Throwable) { bmp?.recycle(); null }
}

/** A selection of glyphs [a..b] (unordered) on one PDF page. */
class PdfSel(val page: Int, val a: Int, val b: Int, val text: PdfPageText) {
    val lo get() = minOf(a, b)
    val hi get() = maxOf(a, b)
}

private class LivePdf {
    var geom: Geom? = null
    var idxs: List<Int> = emptyList()
    var sel: PdfSel? = null
    var hls: List<Hl> = emptyList()
}

@Composable
fun PdfSurface(
    pdf: PdfDoc, ws: FloatArray, hs: FloatArray, idxs: List<Int>, vp: Viewport, theme: ReaderTheme, pagerMode: Boolean,
    store: HighlightStore, lazyText: LazyPdfText, sel: PdfSel?, onSel: (PdfSel?) -> Unit, chrome: MutableState<Boolean>,
    onDictionary: (String) -> Unit, onGeom: (Geom) -> Unit, onVisible: (Int) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val pad = with(density) { 10.dp.toPx() }
    val gap = with(density) { 12.dp.toPx() }
    val minTop = with(density) { 100.dp.toPx() }
    val hls = store.items.filter { it.page >= 0 }
    val live = remember { LivePdf() }
    live.idxs = idxs; live.sel = sel; live.hls = hls
    var menu by remember { mutableStateOf<Pair<Hl, Offset>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val cache = remember(pdf) { object : android.util.LruCache<Int, Bitmap>(48 * 1024 * 1024) { override fun sizeOf(key: Int, value: Bitmap) = value.byteCount } }
    val hi = remember(pdf) { HashMap<Int, Bitmap>() }
    var hiKey by remember { mutableStateOf(Triple(1f, 0f, 0f)) }
    val bmpPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val white = remember { Paint().apply { color = android.graphics.Color.WHITE } }
    val fill = remember { Paint() }
    val edge = remember { Paint().apply { style = Paint.Style.STROKE; color = 0x33000000 } }
    DisposableEffect(pdf) { onDispose { cache.evictAll(); hi.clear() } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        val geom = remember(ws, hs, idxs, vw) { Geom(FloatArray(idxs.size) { ws[idxs[it]] }, FloatArray(idxs.size) { hs[idxs[it]] }, vw, pad, gap) }
        live.geom = geom
        LaunchedEffect(geom, vw, vh) {
            vp.st = vp.st.withViewport(vw, vh).withContent(vw, geom.totalH)
            onGeom(geom)
        }
        LaunchedEffect(geom) {
            snapshotFlow { val s = vp.st; geom.firstVisible((-s.ty + vh * 0.35f) / s.scale) }
                .collect { if (idxs.isNotEmpty()) onVisible(idxs[it.coerceIn(0, idxs.size - 1)]) }
        }
        // base bitmaps for visible pages (cancelled and restarted whenever the view moves)
        LaunchedEffect(geom, vw, vh) {
            if (geom.n == 0) return@LaunchedEffect
            snapshotFlow { val s = vp.st; val y0 = -s.ty / s.scale; Pair(geom.firstVisible(y0), geom.lastVisible(y0 + vh / s.scale)) }
                .collectLatest { (a, b) ->
                    for (k in (a - 1).coerceAtLeast(0)..(b + 1).coerceAtMost(geom.n - 1)) {
                        val pi = idxs[k]
                        if (cache.get(pi) == null) {
                            val bmp = withContext(Dispatchers.IO) { renderFit(pdf, pi, (vw - 2 * pad).toInt().coerceIn(200, 1800)) }
                            if (bmp != null) { cache.put(pi, bmp); tick++ }
                        }
                    }
                }
        }
        // sharp re-render of the visible area once zooming/panning settles
        LaunchedEffect(geom, vw, vh) {
            if (geom.n == 0) return@LaunchedEffect
            snapshotFlow { val s = vp.st; Triple(s.scale, s.tx, s.ty) }.collectLatest { key ->
                if (key.first <= 1.05f) { hi.clear(); hiKey = key; return@collectLatest }
                delay(220)
                val s = vp.st
                val y0 = -s.ty / s.scale
                val fresh = HashMap<Int, Bitmap>()
                for (k in geom.firstVisible(y0)..geom.lastVisible(y0 + vh / s.scale)) {
                    val ps = geom.sc[k] * s.scale
                    val b = withContext(Dispatchers.IO) {
                        renderRegion(pdf, idxs[k], vw.toInt(), vh.toInt(), ps, s.tx + geom.left[k] * s.scale, s.ty + geom.top[k] * s.scale, ws[idxs[k]] * ps, hs[idxs[k]] * ps)
                    }
                    if (b != null) fresh[idxs[k]] = b
                }
                hi.clear(); hi.putAll(fresh); hiKey = key
            }
        }

        /** Screen point -> (page position k, x pt, y pt). */
        fun ptAt(sx: Float, sy: Float): Triple<Int, Float, Float>? {
            val g = live.geom ?: return null
            if (g.n == 0) return null
            val st = vp.st
            val cx = st.toContentX(sx); val cy = st.toContentY(sy)
            val k = g.firstVisible(cy).coerceIn(0, g.n - 1)
            return Triple(k, g.toPageX(k, cx), g.toPageY(k, cy))
        }
        fun findHl(k: Int, x: Float, y: Float): Hl? {
            val pi = live.idxs[k]
            return live.hls.lastOrNull { h -> h.page == pi && h.rects.any { r -> x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3] } }
        }

        val gestures = Modifier.viewportGestures(vp, pagerMode,
            onTap = { o ->
                val p = ptAt(o.x, o.y)
                val h = if (p != null) findHl(p.first, p.second, p.third) else null
                when {
                    live.sel != null -> onSel(null)
                    menu != null -> menu = null
                    h != null -> menu = Pair(h, o)
                    else -> chrome.value = !chrome.value
                }
            },
            onDoubleTap = { o -> vp.st = if (vp.st.zoomed) vp.st.zoomTo(1f, o.x, o.y) else vp.st.zoomTo(2.2f, o.x, o.y) },
            onLongPress = { o ->
                val p = ptAt(o.x, o.y)
                if (p != null) scope.launch {
                    val pi = live.idxs[p.first]
                    val t = lazyText.page(pi)
                    if (t == null) Toast.makeText(ctx, "Text engine unavailable: ${lazyText.error ?: "unknown error"}", Toast.LENGTH_LONG).show()
                    else if (t.glyphs.isEmpty()) Toast.makeText(ctx, "This page has no selectable text (it may be a scanned image).", Toast.LENGTH_LONG).show()
                    else {
                        val gi = t.glyphAt(p.second, p.third, false)
                        if (gi >= 0) { val r = t.wordRange(gi); menu = null; onSel(PdfSel(pi, r.first, r.last, t)) }
                    }
                }
            })

        Canvas(Modifier.fillMaxSize().clipToBounds().then(gestures)) {
            val s = vp.st
            if (tick < 0) return@Canvas   // reading `tick` makes this draw block re-run when a bitmap arrives
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                val y0 = -s.ty / s.scale
                val y1 = y0 + size.height / s.scale
                val first = geom.firstVisible(y0)
                nc.save(); nc.translate(s.tx, s.ty); nc.scale(s.scale, s.scale)
                var k = first
                while (k < geom.n && geom.top[k] <= y1) {
                    val pi = idxs[k]
                    nc.save(); nc.translate(geom.left[k], geom.top[k]); nc.scale(geom.sc[k], geom.sc[k])
                    nc.drawRect(0f, 0f, ws[pi], hs[pi], white)
                    cache.get(pi)?.let { nc.drawBitmap(it, null, android.graphics.RectF(0f, 0f, ws[pi], hs[pi]), bmpPaint) }
                    edge.strokeWidth = 1f / (geom.sc[k] * s.scale)
                    nc.drawRect(0f, 0f, ws[pi], hs[pi], edge)
                    nc.restore(); k++
                }
                nc.restore()
                if (s.scale > 1.05f && hiKey == Triple(s.scale, s.tx, s.ty)) for (b in hi.values) nc.drawBitmap(b, 0f, 0f, null)
                nc.save(); nc.translate(s.tx, s.ty); nc.scale(s.scale, s.scale)
                k = first
                while (k < geom.n && geom.top[k] <= y1) {
                    val pi = idxs[k]
                    nc.save(); nc.translate(geom.left[k], geom.top[k]); nc.scale(geom.sc[k], geom.sc[k])
                    for (h in hls) if (h.page == pi) {
                        fill.color = (h.color and 0xFFFFFF) or 0x66000000
                        for (r in h.rects) nc.drawRect(r[0], r[1], r[2], r[3], fill)
                    }
                    val sl = sel
                    if (sl != null && sl.page == pi) {
                        fill.color = 0x553B82F6
                        for (r in sl.text.rects(sl.lo, sl.hi)) nc.drawRect(r[0], r[1], r[2], r[3], fill)
                    }
                    nc.restore(); k++
                }
                nc.restore()
            }
        }

        val s0 = sel
        if (s0 != null && geom.n > 0 && idxs.contains(s0.page)) {
            val k = idxs.indexOf(s0.page)
            val rects = s0.text.rects(s0.lo, s0.hi)
            if (rects.isNotEmpty()) {
                val st = vp.st
                fun sx(px: Float) = st.tx + geom.fromPageX(k, px) * st.scale
                fun sy(py: Float) = st.ty + geom.fromPageY(k, py) * st.scale
                fun glyphAtScreen(x: Float, y: Float): Int {
                    val cx = vp.st.toContentX(x); val cy = vp.st.toContentY(y)
                    return s0.text.glyphAt(geom.toPageX(k, cx), geom.toPageY(k, cy), true)
                }
                val f = rects.first(); val l = rects.last()
                val hc = Color(0xFF2F5D8A)
                SelHandle(sx(f[0]), sy(f[3]), hc, onMove = { x, y -> val g = glyphAtScreen(x, y); if (g >= 0) onSel(PdfSel(s0.page, g, s0.hi, s0.text)) }, onEnd = {})
                SelHandle(sx(l[2]), sy(l[3]), hc, onMove = { x, y -> val g = glyphAtScreen(x, y); if (g >= 0) onSel(PdfSel(s0.page, s0.lo, g, s0.text)) }, onEnd = {})
                val text = s0.text.textOf(s0.lo, s0.hi)
                SelectionToolbar(
                    centerX = (sx(f[0]) + sx(l[2])) / 2f, aboveY = sy(f[1]) - 10f, belowY = sy(l[3]) + with(density) { 48.dp.toPx() }, minTop = minTop,
                    showParagraph = false, onParagraph = {},
                    onCopy = { copyText(ctx, text); onSel(null) },
                    onDictionary = { onDictionary(text.trim().split(Regex("\\s+")).firstOrNull() ?: text) },
                    onTranslate = { External.open(ctx, External.translateUrl(text)) },
                    onHighlight = { ci ->
                        store.add(listOf(Hl(UUID.randomUUID().toString(), UUID.randomUUID().toString(), s0.page, -1, s0.lo, s0.hi,
                            HlColors.all[ci], text, s0.text.rects(s0.lo, s0.hi))))
                        onSel(null)
                    }
                )
            }
        }
        menu?.let { (h, o) ->
            HighlightMenu(o.x, o.y - 24f, o.y + with(density) { 36.dp.toPx() }, minTop,
                onColor = { ci -> store.recolorGroup(h.group, HlColors.all[ci]); menu = null },
                onDelete = { store.removeGroup(h.group); menu = null }, onClose = { menu = null })
        }
    }
}

@Composable
fun PdfReader(app: AppState, uri: Uri, onBack: () -> Unit) {
    val theme = app.theme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var pdf by remember { mutableStateOf<PdfDoc?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var dims by remember { mutableStateOf<Pair<FloatArray, FloatArray>?>(null) }
    val chrome = remember { mutableStateOf(true) }
    var showGoTo by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf<PdfSel?>(null) }
    var dictWord by remember { mutableStateOf<String?>(null) }
    var contPage by remember { mutableIntStateOf(0) }
    var contGeom by remember { mutableStateOf<Geom?>(null) }
    var jumpTo by remember { mutableStateOf<Int?>(null) }
    val title = remember(uri) { nameOf(ctx, uri) }
    val store = remember(uri) { app.highlights(uri.toString()) }
    val lazyText = remember(uri) { LazyPdfText(ctx, uri) }
    val continuous = app.pdfContinuous
    val count = pdf?.count ?: 0
    val pager = rememberPagerState(initialPage = 0) { count.coerceAtLeast(1) }
    val contVp = remember { Viewport(centerY = false) }
    val vpMap = remember { HashMap<Int, Viewport>() }
    val gapPx = with(density) { 12.dp.toPx() }
    val current = if (continuous) contPage else pager.currentPage
    val lastIdx = (count - 1).coerceAtLeast(0)
    FullscreenEffect(chrome)
    DisposableEffect(uri) { onDispose { lazyText.close() } }

    // The renderer lives exactly as long as this effect; the finally block closes it even if opening was cancelled.
    LaunchedEffect(uri) {
        var d: PdfDoc? = null
        try {
            d = withContext(Dispatchers.IO + NonCancellable) { PdfDoc.open(ctx, uri) }
            pdf = d
            val n = d.count
            val sizes = withContext(Dispatchers.IO) {
                val w = FloatArray(n); val h = FloatArray(n)
                val first = d.pageSize(0) ?: Pair(595, 842)
                for (i in 0 until n) {
                    val p = if (n > 2000 && i > 0) first else (d.pageSize(i) ?: first)   // huge files: assume uniform pages
                    w[i] = p.first.toFloat(); h[i] = p.second.toFloat()
                }
                Pair(w, h)
            }
            dims = sizes
            awaitCancellation()
        } catch (e: CancellationException) { throw e
        } catch (e: Throwable) { err = pdfError(e)
        } finally { pdf = null; dims = null; d?.close() }
    }
    LaunchedEffect(dims) {
        if (dims != null && !restored) { jumpTo = app.lastPage(uri.toString()).coerceIn(0, lastIdx); restored = true }
    }
    LaunchedEffect(jumpTo, contGeom, continuous, count) {
        val p = jumpTo ?: return@LaunchedEffect
        if (count == 0) return@LaunchedEffect
        val t = p.coerceIn(0, count - 1)
        if (continuous) {
            val g = contGeom ?: return@LaunchedEffect
            if (t < g.n) {
                val st = contVp.st
                contVp.st = st.copy(ty = -(g.top[t] - gapPx) * st.scale).clamped()
                contPage = t; jumpTo = null
            }
        } else { pager.scrollToPage(t); jumpTo = null }
    }
    LaunchedEffect(current, restored) { if (restored) app.savePage(uri.toString(), current) }
    LaunchedEffect(pager.currentPage) {
        sel = null
        vpMap.filterKeys { it != pager.currentPage }.values.forEach { it.st = it.st.copy(scale = 1f, tx = 0f, ty = 0f).clamped() }
    }

    fun activeVp(): Viewport? = if (continuous) contVp else vpMap[pager.currentPage]
    fun zoom(f: Float) { activeVp()?.let { v -> val s = v.st; v.st = s.zoomBy(f, s.vw / 2f, s.vh / 2f) } }
    fun goTo(p: Int) { jumpTo = p.coerceIn(0, lastIdx) }
    fun setMode(c: Boolean) { val cur = current; app.setViewModes(pdf = c); jumpTo = cur }
    val allIdx = remember(count) { List(count) { it } }
    val backdrop = Color(blend(theme.page, theme.ink, if (theme.dark) 0.18f else 0.10f))

    Box(Modifier.fillMaxSize().background(backdrop)) {
        val d = pdf
        val dm = dims
        if (d == null || dm == null) {
            val e = err
            if (e != null) ReaderError("Couldn't open this PDF", e, onBack, Modifier.align(Alignment.Center))
            else CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else if (continuous) {
            PdfSurface(d, dm.first, dm.second, allIdx, contVp, theme, false, store, lazyText, sel, { sel = it }, chrome,
                { dictWord = it }, { contGeom = it }, { contPage = it })
        } else {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { idx ->
                val vp = remember(idx) { vpMap.getOrPut(idx) { Viewport(true) } }
                val one = remember(idx) { listOf(idx) }
                PdfSurface(d, dm.first, dm.second, one, vp, theme, true, store, lazyText, sel, { sel = it }, chrome, { dictWord = it }, {}, {})
            }
        }

        ChromeBars(chrome, theme,
            top = {
                BarBtn("‹", onBack)
                Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { showTheme = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Theme", fontSize = 13.sp) }
                Box {
                    BarBtn("⋮") { showMenu = true }
                    DropdownMenu(showMenu, { showMenu = false }) {
                        DropdownMenuItem(text = { Text(if (continuous) "Single page mode" else "Continuous scrolling") }, onClick = { showMenu = false; setMode(!continuous) })
                        DropdownMenuItem(text = { Text("Go to page…") }, onClick = { showMenu = false; showGoTo = true })
                    }
                }
            },
            bottom = {
                BarBtn("‹") { goTo(current - 1) }
                Slider(
                    value = current.toFloat().coerceAtMost(lastIdx.coerceAtLeast(1).toFloat()),
                    onValueChange = { v -> goTo(v.toInt()) },
                    valueRange = 0f..lastIdx.coerceAtLeast(1).toFloat(),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showGoTo = true }, contentPadding = PaddingValues(4.dp)) {
                    Text("${current + 1}/${count.coerceAtLeast(1)}", fontSize = 13.sp)
                }
                BarBtn("›") { goTo(current + 1) }
                BarBtn("−") { zoom(1f / 1.25f) }
                BarBtn("+") { zoom(1.25f) }
            }
        )
    }
    if (showTheme) ThemeSheet(app) { showTheme = false }
    dictWord?.let { w -> DictionaryDialog(w, app) { dictWord = null } }
    if (showGoTo) GoToDialog(count, { goTo(it) }, { showGoTo = false })
}
