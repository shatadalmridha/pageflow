package com.pageflow

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.LruCache
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/** One extracted character (or ligature) with its box in PDF points, origin top-left, y down. */
class PdfGlyph(val s: String, val l: Float, val t: Float, val r: Float, val b: Float, val ci: Int)

class PdfPageText(val glyphs: List<PdfGlyph>, val text: String) {
    /** Index of the glyph under (x, y) in points; with [loose] the nearest glyph within [tol] points. */
    fun glyphAt(x: Float, y: Float, loose: Boolean, tol: Float = 14f): Int {
        var best = -1
        var bd = Float.MAX_VALUE
        for ((i, g) in glyphs.withIndex()) {
            val dx = when { x < g.l -> g.l - x; x > g.r -> x - g.r; else -> 0f }
            val dy = when { y < g.t -> g.t - y; y > g.b -> y - g.b; else -> 0f }
            val d = dx * dx + dy * dy * 4f
            if (d < bd) { bd = d; best = i }
        }
        if (best < 0) return -1
        return if (loose || bd <= tol * tol) best else -1
    }

    /** Word around a glyph, as inclusive glyph indices. */
    fun wordRange(gi: Int): IntRange {
        val g = glyphs[gi]
        var s = g.ci
        var e = g.ci + g.s.length
        while (s > 0 && !text[s - 1].isWhitespace()) s--
        while (e < text.length && !text[e].isWhitespace()) e++
        var a = gi; var b = gi
        while (a > 0 && glyphs[a - 1].ci >= s) a--
        while (b < glyphs.size - 1 && glyphs[b + 1].ci < e) b++
        return a..b
    }

    fun textOf(a: Int, b: Int): String {
        val lo = minOf(a, b); val hi = maxOf(a, b)
        val s = glyphs[lo].ci
        val e = glyphs[hi].ci + glyphs[hi].s.length
        return text.substring(s.coerceIn(0, text.length), e.coerceIn(0, text.length)).trim()
    }

    /** Merges glyph boxes into one rectangle per text line: [l, t, r, b] in points. */
    fun rects(a: Int, b: Int): List<FloatArray> {
        val lo = minOf(a, b); val hi = maxOf(a, b)
        val out = ArrayList<FloatArray>()
        var cur: FloatArray? = null
        for (i in lo..hi) {
            val g = glyphs[i]
            val c = cur
            if (c != null && kotlin.math.abs((g.t + g.b) / 2f - (c[1] + c[3]) / 2f) < (g.b - g.t) * 0.6f) {
                c[0] = minOf(c[0], g.l); c[1] = minOf(c[1], g.t); c[2] = maxOf(c[2], g.r); c[3] = maxOf(c[3], g.b)
            } else {
                cur = floatArrayOf(g.l, g.t, g.r, g.b); out.add(cur)
            }
        }
        return out
    }
}

private class Collector : PDFTextStripper() {
    val glyphs = ArrayList<PdfGlyph>()
    val sb = StringBuilder()

    override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
        if (textPositions == null) return
        for (tp in textPositions) {
            val u = tp.unicode ?: continue
            if (u.isEmpty()) continue
            val h = tp.heightDir.coerceAtLeast(tp.fontSizeInPt * 0.8f)
            glyphs.add(PdfGlyph(u, tp.xDirAdj, tp.yDirAdj - h, tp.xDirAdj + tp.widthDirAdj, tp.yDirAdj, sb.length))
            sb.append(u)
        }
    }
    override fun writeWordSeparator() { sb.append(' ') }
    override fun writeLineSeparator() { sb.append('\n') }
    override fun writeParagraphSeparator() { sb.append('\n') }
}

/**
 * Text and character positions of PDF pages, extracted lazily one page at a time with PDFBox-Android.
 * Android's PdfRenderer cannot do this. Limits: scanned PDFs have no text; PDFs that use legacy (non-Unicode)
 * Bengali fonts may extract incorrectly, as in any PDF text extractor.
 */
class PdfTextEngine(ctx: Context, private val file: File) {
    private var doc: PDDocument? = null
    private val cache = LruCache<Int, PdfPageText>(8)

    init { PDFBoxResourceLoader.init(ctx.applicationContext) }

    @Synchronized
    fun page(i: Int): PdfPageText {
        cache.get(i)?.let { return it }
        val d = doc ?: PDDocument.load(file).also { doc = it }
        val c = Collector()
        c.sortByPosition = true
        c.startPage = i + 1
        c.endPage = i + 1
        c.getText(d)
        val r = PdfPageText(c.glyphs, c.sb.toString())
        cache.put(i, r)
        return r
    }

    @Synchronized
    fun close() { try { doc?.close() } catch (_: Throwable) {}; doc = null; cache.evictAll() }
}

/** Creates the text engine on first use (copies the PDF to cache once). Failures are reported, never thrown. */
class LazyPdfText(private val ctx: Context, private val uri: Uri) {
    private var engine: PdfTextEngine? = null
    @Volatile var error: String? = null

    suspend fun page(i: Int): PdfPageText? = withContext(Dispatchers.IO) {
        try {
            val e = engine ?: create().also { engine = it }
            e.page(i)
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
            null
        }
    }

    private fun create(): PdfTextEngine {
        val f = File(ctx.cacheDir, "pdf_text.pdf")
        val input = ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open the PDF")
        input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return PdfTextEngine(ctx, f)
    }

    fun close() { engine?.close(); engine = null }
}
