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
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.Typeface as ComposeTypeface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
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
    val sys = isSystemInDarkTheme()
    val pal = remember(app.theme, sys) { palette(app.theme, sys) }
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }

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
    MaterialTheme(colorScheme = if (pal.dark) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val s = stack.last()) {
                is Screen.Home -> Home(app,
                    onOpen = { uri, type ->
                        app.addRecent(uri.toString(), nameOf(ctx, uri), type)
                        stack.add(if (type == "pdf") Screen.Pdf(uri) else Screen.Docx(uri))
                    },
                    onSettings = { stack.add(Screen.Settings) })
                is Screen.Docx -> DocxReader(app, s.uri, pal) { stack.removeAt(stack.size - 1) }
                is Screen.Pdf -> PdfReader(app, s.uri, pal) { stack.removeAt(stack.size - 1) }
                is Screen.Settings -> SettingsScreen(app) { stack.removeAt(stack.size - 1) }
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

// ------------------------------------------------------------------ shared bits
fun Modifier.pinchZoom(onZoom: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            if (ev.changes.size >= 2) {
                val z = ev.calculateZoom()
                if (z != 1f) { onZoom(z); ev.changes.forEach { it.consume() } }
            }
        } while (ev.changes.any { it.pressed })
    }
}

@Composable
fun BarBtn(label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun FullscreenEffect(chrome: Boolean) {
    val view = LocalView.current
    LaunchedEffect(chrome) {
        val w = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(w, view)
        if (chrome) c.show(WindowInsetsCompat.Type.systemBars()) else {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val w = (view.context as? Activity)?.window
            if (w != null) WindowCompat.getInsetsController(w, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }
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

// ------------------------------------------------------------------ DOCX reader
@Composable
fun DocxReader(app: AppState, uri: Uri, pal: Pal, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf<Doc?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var pages by remember { mutableStateOf<List<Page>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var chrome by remember { mutableStateOf(true) }
    var zoom by remember { mutableStateOf(1f) }
    var showSearch by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf(false) }
    val title = remember(uri) { nameOf(ctx, uri) }
    val target = remember(uri) { app.lastPage(uri.toString()) }
    val engine = remember { AndroidTextEngine(app.fonts) }
    val pager = rememberPagerState(initialPage = 0) { pages.size.coerceAtLeast(1) }

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
    LaunchedEffect(doc, app.fontScale, app.lineSpacing, app.fontsVersion, app.readingFont, app.overrideFonts, pal.ink) {
        val d = doc ?: return@LaunchedEffect
        val first = pages.isEmpty()
        val frac = if (!first) pager.currentPage.toFloat() / pages.size.coerceAtLeast(1) else null
        busy = true
        val cfg = LayoutCfg(app.fontScale, app.lineSpacing, pal.ink, app.overrideFonts, app.readingFont)
        val publish: (List<Page>) -> Unit = { l -> pages = l }
        val result = withContext(Dispatchers.Default) {
            try {
                // first layout publishes finished pages progressively so reading can start immediately
                Paginator(d, cfg, engine).run(if (first) publish else null) { !isActive }
            } catch (e: CancellationException) { throw e
            } catch (e: OutOfMemoryError) { err = "Ran out of memory while laying out this document."; null
            } catch (e: Throwable) { err = e.toString(); null }
        }
        if (result != null) {
            pages = result
            if (frac != null) pager.scrollToPage((frac * result.size).toInt().coerceIn(0, result.size - 1))
        }
        busy = false
    }
    LaunchedEffect(pages.size, busy) {
        if (!restored && pages.isNotEmpty() && (pages.size > target || !busy)) {
            pager.scrollToPage(target.coerceIn(0, pages.size - 1))
            restored = true
        }
    }
    LaunchedEffect(pager.currentPage, restored) { if (restored) app.savePage(uri.toString(), pager.currentPage) }

    val bg = if (pal.dark) Color(0xFF111111) else Color(0xFFE6E3DC)
    Box(Modifier.fillMaxSize().background(bg)) {
        val e = err
        if (e != null && pages.isEmpty()) {
            ReaderError("Couldn't open this document", e, onBack, Modifier.align(Alignment.Center))
        } else if (pages.isEmpty()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            HorizontalPager(state = pager, userScrollEnabled = zoom <= 1.01f, modifier = Modifier.fillMaxSize()) { idx ->
                val pg = pages.getOrNull(idx)
                if (pg != null) {
                    BoxWithConstraints(
                        Modifier.fillMaxSize()
                            .pinchZoom { z -> zoom = (zoom * z).coerceIn(1f, 4f) }
                            .pointerInput(Unit) { detectTapGestures(onTap = { chrome = !chrome }, onDoubleTap = { zoom = if (zoom > 1.05f) 1f else 2f }) }
                    ) {
                        val boxW = maxWidth
                        val pageW = (boxW - 24.dp) * zoom
                        Box(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                                .widthIn(min = boxW),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            val numPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
                            Canvas(
                                Modifier.padding(12.dp).width(pageW).aspectRatio(pg.w / pg.h)
                                    .shadow(6.dp, RoundedCornerShape(2.dp)).background(Color(pal.paper)).clipToBounds()
                            ) {
                                drawIntoCanvas { c ->
                                    val nc = c.nativeCanvas
                                    val sc = size.width / pg.w
                                    nc.save(); nc.scale(sc, sc)
                                    PageDrawer.draw(nc, pg, pal.ink, sc)
                                    nc.restore()
                                    numPaint.color = pal.ink and 0x00FFFFFF or 0xAA000000.toInt()
                                    numPaint.textSize = 9f * S * sc
                                    nc.drawText("${idx + 1}", size.width / 2f, pg.footerY * sc + numPaint.textSize / 3f, numPaint)
                                }
                            }
                        }
                    }
                }
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(16.dp).height(22.dp).width(22.dp), strokeWidth = 2.dp)
            if (e != null) Text("Layout stopped early: $e", color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center).background(bg).padding(8.dp))
        }

        AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter)) {
            Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarBtn("‹", onBack)
                    Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    BarBtn("Find") { showSearch = true }
                    BarBtn("A−") { app.fontScale = (app.fontScale - 0.1f).coerceAtLeast(0.6f); app.persist() }
                    BarBtn("A+") { app.fontScale = (app.fontScale + 0.1f).coerceAtMost(2f); app.persist() }
                }
            }
        }
        AnimatedVisibility(chrome, Modifier.align(Alignment.BottomCenter)) {
            Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarBtn("‹") { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } }
                    Slider(
                        value = pager.currentPage.toFloat().coerceAtMost((pages.size - 1).coerceAtLeast(1).toFloat()),
                        onValueChange = { v -> scope.launch { pager.scrollToPage(v.toInt()) } },
                        valueRange = 0f..(pages.size - 1).coerceAtLeast(1).toFloat(),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showGoTo = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
                        Text("${pager.currentPage + 1}/${pages.size.coerceAtLeast(1)}${if (busy) "+" else ""}", fontSize = 13.sp)
                    }
                    BarBtn("›") { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost((pages.size - 1).coerceAtLeast(0))) } }
                    BarBtn("−") { zoom = (zoom - 0.25f).coerceAtLeast(1f) }
                    BarBtn("+") { zoom = (zoom + 0.25f).coerceAtMost(4f) }
                }
            }
        }
    }

    if (showGoTo) GoToDialog(pages.size, { scope.launch { pager.scrollToPage(it) } }, { showGoTo = false })
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
                        val start = pager.currentPage
                        val hit = withContext(Dispatchers.Default) {
                            if (q.isBlank() || n == 0) -1
                            else (1..n).map { (start + it) % n }.firstOrNull { pages[it].text.contains(q, ignoreCase = true) } ?: -1
                        }
                        if (hit >= 0) { status = "Found on page ${hit + 1}"; pager.animateScrollToPage(hit) } else status = "No match"
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

@Composable
fun PdfFitPage(pdf: PdfDoc, index: Int, widthPx: Int, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(null, pdf, index, widthPx) {
        value = withContext(Dispatchers.IO) { renderFit(pdf, index, widthPx) }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    else Box(modifier.fillMaxWidth().aspectRatio(0.707f).background(Color.White))
}

@Composable
fun PdfZoomPage(
    pdf: PdfDoc, index: Int, vw: Float, vh: Float, fitPage: Boolean, isCurrent: Boolean,
    onZoomed: (Boolean) -> Unit, onTap: () -> Unit
) {
    val dim by produceState<Pair<Int, Int>?>(null, pdf, index) { value = withContext(Dispatchers.IO) { pdf.pageSize(index) } }
    val d = dim
    if (d == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    else PdfZoomContent(pdf, index, d.first.toFloat(), d.second.toFloat(), vw, vh, fitPage, isCurrent, onZoomed, onTap)
}

@Composable
fun PdfZoomContent(
    pdf: PdfDoc, index: Int, pw: Float, ph: Float, vw: Float, vh: Float, fitPage: Boolean, isCurrent: Boolean,
    onZoomed: (Boolean) -> Unit, onTap: () -> Unit
) {
    val baseScale = if (fitPage) minOf(vw / pw, vh / ph) else vw / pw
    var z by remember(index, fitPage) { mutableStateOf(1f) }
    var px by remember(index, fitPage) { mutableStateOf(0f) }
    var py by remember(index, fitPage) { mutableStateOf(0f) }
    LaunchedEffect(isCurrent) { if (!isCurrent) { z = 1f; px = 0f; py = 0f } }
    LaunchedEffect(z, isCurrent) { if (isCurrent) onZoomed(z > 1.02f) }

    fun clampX(x: Float, zz: Float): Float { val w = pw * baseScale * zz; return if (w <= vw) (vw - w) / 2f else x.coerceIn(vw - w, 0f) }
    fun clampY(y: Float, zz: Float): Float { val h = ph * baseScale * zz; return if (h <= vh) (vh - h) / 2f else y.coerceIn(vh - h, 0f) }
    val ox = clampX(px, z)
    val oy = clampY(py, z)

    val baseW = (pw * baseScale).roundToInt().coerceIn(100, 1800)
    val base by produceState<Bitmap?>(null, pdf, index, baseW) { value = withContext(Dispatchers.IO) { renderFit(pdf, index, baseW) } }

    var settled by remember { mutableStateOf(Triple(1f, 0f, 0f)) }
    LaunchedEffect(z, ox, oy) { delay(180); settled = Triple(z, ox, oy) }
    val hi by produceState<Bitmap?>(null, settled, pdf, index, vw, vh) {
        val s = settled
        value = if (s.first <= 1.05f) null else withContext(Dispatchers.IO) {
            renderRegion(pdf, index, vw.toInt(), vh.toInt(), baseScale * s.first, s.second, s.third, pw * baseScale * s.first, ph * baseScale * s.first)
        }
    }

    Canvas(
        Modifier.fillMaxSize()
            .pointerInput(index, vw, vh, fitPage) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val multi = ev.changes.size >= 2
                        if (multi || z > 1.02f) {
                            val zc = if (multi) ev.calculateZoom() else 1f
                            val pan = ev.calculatePan()
                            val c = ev.calculateCentroid(useCurrent = true)
                            if (zc != 1f || pan != Offset.Zero) {
                                val nz = (z * zc).coerceIn(1f, 6f)
                                val f = nz / z
                                val cx = clampX(px, z); val cy = clampY(py, z)
                                px = c.x - (c.x - cx) * f + pan.x
                                py = c.y - (c.y - cy) * f + pan.y
                                z = nz
                                ev.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }
            .pointerInput(index, vw, vh, fitPage) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { off ->
                        if (z > 1.05f) { z = 1f; px = 0f; py = 0f }
                        else {
                            val cx = clampX(px, z); val cy = clampY(py, z)
                            val nz = 2.5f; val f = nz / z
                            px = off.x - (off.x - cx) * f; py = off.y - (off.y - cy) * f; z = nz
                        }
                    }
                )
            }
    ) {
        val b = base
        if (b != null) drawImage(
            b.asImageBitmap(),
            dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
            dstSize = IntSize((pw * baseScale * z).roundToInt().coerceAtLeast(1), (ph * baseScale * z).roundToInt().coerceAtLeast(1))
        )
        val h = hi
        if (h != null && settled == Triple(z, ox, oy)) drawImage(h.asImageBitmap())
    }
}

@Composable
fun PdfReader(app: AppState, uri: Uri, pal: Pal, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pdf by remember { mutableStateOf<PdfDoc?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var chrome by remember { mutableStateOf(true) }
    var continuous by remember { mutableStateOf(false) }
    var fitPage by remember { mutableStateOf(false) }
    var anyZoomed by remember { mutableStateOf(false) }
    var contZoom by remember { mutableStateOf(1f) }
    var showGoTo by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf(false) }
    val title = remember(uri) { nameOf(ctx, uri) }
    FullscreenEffect(chrome)

    // The renderer lives exactly as long as this effect; the finally block closes it even if opening was cancelled.
    LaunchedEffect(uri) {
        var d: PdfDoc? = null
        try {
            d = withContext(Dispatchers.IO + NonCancellable) { PdfDoc.open(ctx, uri) }
            pdf = d
            awaitCancellation()
        } catch (e: CancellationException) { throw e
        } catch (e: Throwable) { err = pdfError(e)
        } finally { pdf = null; d?.close() }
    }

    val doc = pdf
    val count = doc?.count ?: 0
    val pager = rememberPagerState(initialPage = 0) { count.coerceAtLeast(1) }
    val listState = rememberLazyListState()
    val current = if (continuous) listState.firstVisibleItemIndex else pager.currentPage
    LaunchedEffect(pager.currentPage) { anyZoomed = false }
    LaunchedEffect(count) {
        if (count > 0 && !restored) {
            val p = app.lastPage(uri.toString()).coerceIn(0, count - 1)
            pager.scrollToPage(p); listState.scrollToItem(p); restored = true
        }
    }
    LaunchedEffect(continuous) {
        if (restored) { if (continuous) listState.scrollToItem(pager.currentPage) else pager.scrollToPage(listState.firstVisibleItemIndex) }
    }
    LaunchedEffect(current, restored) { if (restored) app.savePage(uri.toString(), current) }
    fun goTo(i: Int) { scope.launch { if (continuous) listState.scrollToItem(i) else pager.scrollToPage(i) } }

    Box(Modifier.fillMaxSize().background(if (pal.dark) Color(0xFF111111) else Color(0xFFE6E3DC))) {
        val e = err
        if (doc == null) {
            if (e != null) ReaderError("Couldn't open this PDF", e, onBack, Modifier.align(Alignment.Center))
            else CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val vw = constraints.maxWidth.toFloat()
                val vh = constraints.maxHeight.toFloat()
                val density = LocalDensity.current
                if (continuous) {
                    val widthPx = (vw * contZoom).toInt()
                    Box(
                        Modifier.fillMaxSize().horizontalScroll(rememberScrollState())
                            .pinchZoom { z -> contZoom = (contZoom * z).coerceIn(1f, 3f) }
                            .pointerInput(Unit) { detectTapGestures(onTap = { chrome = !chrome }, onDoubleTap = { contZoom = if (contZoom > 1.05f) 1f else 2f }) }
                    ) {
                        LazyColumn(state = listState, modifier = Modifier.width(with(density) { widthPx.toDp() })) {
                            items(count) { i -> PdfFitPage(doc, i, widthPx, Modifier.padding(vertical = 4.dp)) }
                        }
                    }
                } else {
                    HorizontalPager(state = pager, userScrollEnabled = !anyZoomed, modifier = Modifier.fillMaxSize()) { i ->
                        PdfZoomPage(doc, i, vw, vh, fitPage, isCurrent = pager.currentPage == i,
                            onZoomed = { anyZoomed = it }, onTap = { chrome = !chrome })
                    }
                }
            }
        }
        AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter)) {
            Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarBtn("‹", onBack)
                    Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    if (!continuous) TextButton(onClick = { fitPage = !fitPage }) { Text(if (fitPage) "Fit width" else "Fit page") }
                    TextButton(onClick = { continuous = !continuous }) { Text(if (continuous) "Single" else "Scroll") }
                }
            }
        }
        AnimatedVisibility(chrome && doc != null, Modifier.align(Alignment.BottomCenter)) {
            Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarBtn("‹") { goTo((current - 1).coerceAtLeast(0)) }
                    Slider(
                        value = current.toFloat().coerceAtMost((count - 1).coerceAtLeast(1).toFloat()),
                        onValueChange = { v -> goTo(v.toInt()) },
                        valueRange = 0f..(count - 1).coerceAtLeast(1).toFloat(),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showGoTo = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
                        Text("${current + 1}/${count.coerceAtLeast(1)}", fontSize = 13.sp)
                    }
                    BarBtn("›") { goTo((current + 1).coerceAtMost((count - 1).coerceAtLeast(0))) }
                }
            }
        }
    }
    if (showGoTo) GoToDialog(count, { goTo(it) }, { showGoTo = false })
}

// ------------------------------------------------------------------ Settings + Font Manager
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch { msg = withContext(Dispatchers.IO) { importFont(ctx, app, u) } }
    }
    val themes = listOf("system" to "System default", "light" to "Light", "dark" to "Dark", "sepia" to "Sepia", "cream" to "Warm cream", "black" to "Pure black")
    val sample = "বাংলা লিপির নমুনা: ক্ষ জ্ঞ শ্রী দ্ধ — The quick brown fox"
    val files = remember(app.fontsVersion) { app.fonts.list }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                BarBtn("‹", onBack); Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        item { Text("Theme", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp)) }
        items(themes) { (k, label) ->
            Row(Modifier.fillMaxWidth().clickable { app.theme = k; app.persist() }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(app.theme == k, { app.theme = k; app.persist() }); Text(label)
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
            Text("Storage", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    File(ctx.cacheDir, "current.docx").delete(); File(ctx.cacheDir, "media").deleteRecursively()
                }) { Text("Clear cache") }
                OutlinedButton(onClick = { app.clearRecents() }) { Text("Clear recents") }
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }
        item {
            Text("About", fontWeight = FontWeight.SemiBold)
            Text("PageFlow 0.2.0 · fully offline · no internet permission. Documents are read from a temporary copy; your original files are never modified.",
                fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
            Text("Available: DOCX page reader, Bengali font manager, PDF reader, themes, search, recents.\n" +
                "Planned (not built yet): DOCX editing & Save As, bookmarks, headers/footers, text boxes, PDF search.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 32.dp))
        }
    }
}
