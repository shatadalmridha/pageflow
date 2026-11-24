package com.pageflow

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.text.style.TabStopSpan
import android.util.LruCache
import java.io.File
import java.util.concurrent.CancellationException

/** Layout units per point. Pages are laid out at 2x and scaled when drawn, so zoom stays vector-sharp. */
const val S = 2f

// ------------------------------------------------------------ text measurement abstraction
/** Minimal view of a laid-out paragraph. Android implementation wraps StaticLayout; tests use a fake. */
interface TextLayout {
    val lineCount: Int
    val height: Int
    val width: Int
    val text: CharSequence
    fun lineTop(i: Int): Int
    fun lineBottom(i: Int): Int
    fun lineStart(i: Int): Int
    fun lineEnd(i: Int): Int
    fun draw(c: Canvas)
}

interface TextEngine {
    fun build(p: ParaBlock, width: Int, cfg: LayoutCfg): TextLayout
}

class LayoutCfg(
    val fontScale: Float, val lineSpacing: Float, val ink: Int,
    val overrideFonts: Boolean, val reading: String?
)

sealed class Item
class TextItem(val layout: TextLayout, val x: Float, val y: Float, val l0: Int, val l1: Int) : Item()
class RectItem(val x: Float, val y: Float, val w: Float, val h: Float, val fill: Int?, val stroke: Boolean) : Item()
class ImgItem(val file: File, val x: Float, val y: Float, val w: Float, val h: Float) : Item()

class Page(val w: Float, val h: Float, val footerY: Float) {
    val items = ArrayList<Item>()
    val text: String by lazy {
        items.filterIsInstance<TextItem>().joinToString("\n") {
            it.layout.text.subSequence(it.layout.lineStart(it.l0), it.layout.lineEnd(it.l1 - 1)).toString()
        }
    }
}

fun isBn(c: Char) = c.code in 0x0980..0x09FF

/**
 * Splits text into Bengali / non-Bengali segments. Neutral characters (space, digits, punctuation,
 * ZWJ/ZWNJ) stay with the surrounding script so conjunct clusters are never split.
 */
fun segments(s: String): List<Pair<String, Boolean>> {
    val out = ArrayList<Pair<String, Boolean>>()
    var cur = false
    val sb = StringBuilder()
    for (c in s) {
        val cls = if (isBn(c)) true else if (c.isLetter()) false else cur
        if (sb.isNotEmpty() && cls != cur) { out.add(Pair(sb.toString(), cur)); sb.setLength(0) }
        cur = cls
        sb.append(c)
    }
    if (sb.isNotEmpty()) out.add(Pair(sb.toString(), cur))
    return out
}

fun lum(c: Int): Float =
    (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f

fun adaptColor(c: Int?, ink: Int): Int {
    if (c == null) return ink
    val dark = lum(ink) > 0.5f
    if (dark && lum(c) < 0.35f) return ink
    if (!dark && lum(c) > 0.85f) return ink
    return c
}

// ------------------------------------------------------------ Android engine
class RunSpan(
    private val tf: Typeface, private val size: Float, private val color: Int,
    private val ul: Boolean, private val strike: Boolean, private val vert: Int, private val bg: Int?
) : MetricAffectingSpan() {
    private fun applyTo(p: TextPaint) {
        p.typeface = tf
        p.textSize = if (vert != 0) size * 0.65f else size
        if (vert > 0) p.baselineShift += (p.ascent() / 2f).toInt()
        if (vert < 0) p.baselineShift -= (p.ascent() / 2f).toInt()
    }
    override fun updateMeasureState(p: TextPaint) = applyTo(p)
    override fun updateDrawState(p: TextPaint) {
        applyTo(p)
        p.color = color
        p.isUnderlineText = ul
        p.isStrikeThruText = strike
        if (bg != null) p.bgColor = bg
    }
}

class AndroidTextLayout(private val l: StaticLayout) : TextLayout {
    override val lineCount: Int get() = l.lineCount
    override val height: Int get() = l.height
    override val width: Int get() = l.width
    override val text: CharSequence get() = l.text
    override fun lineTop(i: Int) = l.getLineTop(i)
    override fun lineBottom(i: Int) = l.getLineBottom(i)
    override fun lineStart(i: Int) = l.getLineStart(i)
    override fun lineEnd(i: Int) = l.getLineEnd(i)
    override fun draw(c: Canvas) = l.draw(c)
}

class AndroidTextEngine(private val fonts: FontResolver) : TextEngine {
    override fun build(p: ParaBlock, width: Int, cfg: LayoutCfg): TextLayout {
        val sb = SpannableStringBuilder()
        val scale = S * cfg.fontScale

        fun add(text: String, f: Fmt) {
            if (text.isEmpty()) return
            for ((seg0, bn) in segments(text)) {
                val seg = if (f.caps && !bn) seg0.uppercase() else seg0
                // Word keeps separate size/bold/italic for complex scripts (Bengali): szCs, bCs, iCs.
                val bold = if (bn) (f.boldCs ?: f.bold) else f.bold
                val italic = if (bn) (f.italicCs ?: f.italic) else f.italic
                val hp = if (bn) (f.sizeCsHp ?: f.sizeHp) else f.sizeHp
                val tf = fonts.resolve(if (bn) (f.fontCs ?: f.font) else f.font, bold, italic, bn, cfg.overrideFonts, cfg.reading)
                val st = sb.length
                sb.append(seg)
                sb.setSpan(
                    RunSpan(tf, hp / 2f * scale, adaptColor(f.color, cfg.ink), f.underline, f.strike, f.vert, f.highlight),
                    st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        if (p.bullet != null) add(p.bullet + "\t", p.runs.firstOrNull()?.fmt ?: Fmt(sizeHp = p.sizeHp))
        for (r in p.runs) add(r.text, r.fmt)
        if (sb.isEmpty()) add(" ", Fmt(sizeHp = p.sizeHp, sizeCsHp = p.sizeHp))

        val pf = p.pf
        val left = (((pf.left ?: 0) / 20f) * S).toInt().coerceIn(0, (width * 0.7f).toInt())
        val firstPx = (left + (pf.first / 20f) * S).toInt().coerceAtLeast(0)
        sb.setSpan(LeadingMarginSpan.Standard(firstPx, left), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)

        // Word's default tab stop is 0.5in (36pt); Android's default is tiny.
        val stops = ArrayList<Int>()
        if (p.bullet != null) stops.add(left)
        var x = (36f * S).toInt()
        while (x < width && stops.size < 40) { if (x > left) stops.add(x); x += (36f * S).toInt() }
        for (s in stops) sb.setSpan(TabStopSpan.Standard(s), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)

        val defPt = p.sizeHp / 2f
        val mult = when (pf.lineRule) {
            "exact", "atLeast" -> ((pf.line / 20f) / (defPt * 1.2f)).coerceIn(0.8f, 3f)
            else -> pf.line / 240f
        } * cfg.lineSpacing

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = defPt * scale; color = cfg.ink }
        val al = when (pf.jc) {
            "center" -> Layout.Alignment.ALIGN_CENTER
            "right", "end" -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val bld = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, width.coerceAtLeast(40))
            .setAlignment(al)
            .setIncludePad(false)
            .setLineSpacing(0f, mult.coerceIn(0.5f, 4f))
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
        // Bengali glyphs come from a fallback font with taller ascent/descent; include it in line height.
        if (Build.VERSION.SDK_INT >= 28) bld.setUseLineSpacingFromFallbacks(true)
        if (pf.jc == "both" || pf.jc == "distribute") bld.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        return AndroidTextLayout(bld.build())
    }
}

// ------------------------------------------------------------ pagination
private sealed class CellPart {
    class T(val lay: TextLayout, val before: Float, val after: Float) : CellPart()
    class I(val img: ImgSpec, val w: Float, val h: Float) : CellPart()
}
private class CellL(val x: Float, val w: Float, val fill: Int?, val parts: List<CellPart>)

class Paginator(private val doc: Doc, private val cfg: LayoutCfg, private val engine: TextEngine) {
    private val pages = ArrayList<Page>()
    private var pw = 0f; private var ph = 0f
    private var ml = 0f; private var contentW = 0f
    private var top = 0f; private var bottom = 0f; private var footerY = 0f
    private var cur = Page(0f, 0f, 0f)
    private var y = 0f
    private var suppressBefore = false
    private var lastAfter = 0f
    private var prevPara: ParaBlock? = null
    private var published = 0
    private var progress: ((List<Page>) -> Unit)? = null
    private var cancelled: () -> Boolean = { false }
    private val darkInk = lum(cfg.ink) > 0.5f

    private fun setGeometry(s: Section) {
        pw = s.pageW * S; ph = s.pageH * S
        ml = s.ml * S; contentW = pw - ml - s.mr * S
        top = s.mt * S; bottom = ph - s.mb * S
        footerY = ph - s.mb * S / 2f
    }

    /** auto = flowed onto a new page (Word drops space-before there); false = explicit break / new section. */
    private fun newPage(auto: Boolean) {
        progress?.let { cb ->
            if (pages.size - published >= 12) { cb(ArrayList(pages)); published = pages.size }
        }
        cur = Page(pw, ph, footerY)
        pages.add(cur)
        y = top
        suppressBefore = auto
        lastAfter = 0f
        prevPara = null
    }

    fun run(onProgress: ((List<Page>) -> Unit)? = null, isCancelled: () -> Boolean = { false }): List<Page> {
        progress = onProgress
        cancelled = isCancelled
        var prev: Section? = null
        for (sec in doc.sections) {
            setGeometry(sec)
            val same = prev != null && sec.continuous &&
                prev.pageW == sec.pageW && prev.pageH == sec.pageH && prev.ml == sec.ml && prev.mr == sec.mr &&
                prev.mt == sec.mt && prev.mb == sec.mb
            if (prev == null) newPage(false)
            else if (!same) {
                if (cur.items.isEmpty() && pages.isNotEmpty()) pages.removeAt(pages.size - 1)
                newPage(false)
            }
            val bl = sec.blocks
            for (i in bl.indices) {
                if (cancelled()) throw CancellationException()
                when (val b = bl[i]) {
                    is ParaBlock -> para(b, bl.getOrNull(i + 1))
                    is TableBlock -> { table(b); prevPara = null }
                    is PageBreak -> { if (cur.items.isNotEmpty()) newPage(false); prevPara = null }
                }
            }
            prev = sec
        }
        while (pages.size > 1 && pages[pages.size - 1].items.isEmpty()) pages.removeAt(pages.size - 1)
        if (pages.isEmpty()) { setGeometry(Section(emptyList(), 595.3f, 841.9f, 72f, 72f, 72f, 72f)); newPage(false) }
        return pages
    }

    private fun para(p: ParaBlock, next: Block?) {
        if (p.pf.pbb && cur.items.isNotEmpty()) newPage(false)
        val hasText = p.runs.any { it.text.isNotBlank() } || p.bullet != null || p.images.isEmpty()
        if (hasText) {
            val lay = engine.build(p, contentW.toInt(), cfg)
            val before = p.pf.before / 20f * S
            val after = p.pf.after / 20f * S
            val prev = prevPara
            val ctxSame = p.pf.ctx && prev != null && prev.pf.ctx && prev.styleId == p.styleId && cur.items.isNotEmpty()
            if (ctxSame) y -= lastAfter
            fun gap(): Float = if (ctxSame) 0f else if (cur.items.isEmpty() && suppressBefore) 0f else before
            var g = gap()
            if (p.pf.keepNext && next is ParaBlock && cur.items.isNotEmpty()) {
                val nl = engine.build(next, contentW.toInt(), cfg)
                val nh = nl.lineBottom(minOf(1, nl.lineCount - 1)) - nl.lineTop(0)
                if (y + g + lay.height + after + next.pf.before / 20f * S + nh > bottom) { newPage(true); g = gap() }
            }
            y += g

            var line = 0
            val n = lay.lineCount
            while (line < n) {
                val avail = bottom - y
                val base = lay.lineTop(line)
                var end = line
                while (end < n && lay.lineBottom(end) - base <= avail) end++
                if (end < n) {
                    // widow/orphan control (Word default): never strand a single line
                    if (line == 0 && end == 1 && cur.items.isNotEmpty()) end = 0
                    else if (n - end == 1) {
                        if (end - line >= 3) end -= 1
                        else if (line == 0 && cur.items.isNotEmpty()) end = 0
                    }
                }
                if (end <= line) {
                    if (cur.items.isNotEmpty()) { newPage(true); continue } else end = line + 1
                }
                cur.items.add(TextItem(lay, ml, y, line, end))
                y += (lay.lineBottom(end - 1) - base).toFloat()
                line = end
                if (line < n) newPage(true)
            }
            y += after
            lastAfter = after
            prevPara = p
        } else prevPara = null
        for (img in p.images) { image(img, p.pf.jc); lastAfter = 0f }
    }

    private fun image(i: ImgSpec, jc: String?) {
        var w = i.wPt * S
        var h = i.hPt * S
        if (w > contentW) { h *= contentW / w; w = contentW }
        val maxH = bottom - top
        if (h > maxH) { w *= maxH / h; h = maxH }
        if (y + h > bottom && cur.items.isNotEmpty()) newPage(true)
        val x = when (jc) {
            "center" -> ml + (contentW - w) / 2f
            "right", "end" -> ml + contentW - w
            else -> ml
        }
        cur.items.add(ImgItem(i.file, x, y, w, h))
        y += h + 4 * S
    }

    private fun table(t: TableBlock) {
        val padL = t.padLTwips / 20f * S
        val padR = t.padRTwips / 20f * S
        val padY = 2f * S
        val indent = (t.indentTwips / 20f * S).coerceIn(0f, contentW * 0.5f)
        val avail = contentW - indent
        var cols = t.colW.map { it / 20f * S }
        var sum = cols.sum()
        if (sum <= 0f) { val n = t.colW.size.coerceAtLeast(1); cols = List(n) { avail / n }; sum = avail }
        if (sum > avail) { val k = avail / sum; cols = cols.map { it * k } }
        val pageH = bottom - top

        for (row in t.rows) {
            if (cancelled()) throw CancellationException()
            var ci = 0
            var rowH = row.minHTwips / 20f * S
            val cl = ArrayList<CellL>()
            val heights = ArrayList<Float>()
            for (cell in row.cells) {
                val last = (ci + cell.span).coerceAtMost(cols.size)
                var w = 0f
                for (k in ci until last) w += cols[k]
                var x = ml + indent
                for (k in 0 until ci.coerceAtMost(cols.size)) x += cols[k]
                val inner = (w - padL - padR).toInt().coerceAtLeast(20)
                val parts = ArrayList<CellPart>()
                var h = 2 * padY
                for (p in cell.paras) {
                    if (p.runs.any { it.text.isNotBlank() } || p.bullet != null || p.images.isEmpty()) {
                        val lay = engine.build(p, inner, cfg)
                        val bf = p.pf.before / 20f * S
                        val af = p.pf.after / 20f * S
                        parts.add(CellPart.T(lay, bf, af))
                        h += bf + lay.height + af
                    }
                    for (im in p.images) {
                        var iw = im.wPt * S; var ih = im.hPt * S
                        if (iw > inner) { ih *= inner / iw; iw = inner.toFloat() }
                        parts.add(CellPart.I(im, iw, ih)); h += ih + 2 * S
                    }
                }
                if (parts.isEmpty()) h += 12 * S
                cl.add(CellL(x, w, if (darkInk) null else cell.fill, parts))
                heights.add(h)
                rowH = maxOf(rowH, h)
                ci += cell.span
            }
            rowH = rowH.coerceAtMost(pageH)   // a row taller than a page is clipped (rows are not split)
            if (y + rowH > bottom && cur.items.isNotEmpty()) newPage(true)
            for (c in cl) {
                if (c.w <= 0f) continue
                if (c.fill != null || t.borders) cur.items.add(RectItem(c.x, y, c.w, rowH, c.fill, t.borders))
                var yy = y + padY
                for (part in c.parts) when (part) {
                    is CellPart.T -> {
                        yy += part.before
                        cur.items.add(TextItem(part.lay, c.x + padL, yy, 0, part.lay.lineCount))
                        yy += part.lay.height + part.after
                    }
                    is CellPart.I -> {
                        cur.items.add(ImgItem(part.img.file, c.x + padL, yy, part.w, part.h))
                        yy += part.h + 2 * S
                    }
                }
            }
            y += rowH
            lastAfter = 0f
        }
    }
}

// ------------------------------------------------------------ drawing
object PageDrawer {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun clear() = cache.evictAll()

    private fun bitmap(file: File, targetPx: Int): Bitmap? {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var s = 1
            while (o.outWidth / (s * 2) >= targetPx.coerceAtLeast(64)) s *= 2
            while ((o.outWidth / s).toLong() * (o.outHeight / s) > 12_000_000L) s *= 2
            val key = file.path + "#" + s
            cache.get(key)?.let { return it }
            val o2 = BitmapFactory.Options().apply { inSampleSize = s }
            BitmapFactory.decodeFile(file.path, o2)?.also { cache.put(key, it) }
        } catch (_: Throwable) {   // includes OutOfMemoryError: drop caches and skip the image
            cache.evictAll(); null
        }
    }

    /** Draws a page in layout units. [scale] is the canvas scale already applied by the caller. */
    fun draw(c: Canvas, page: Page, ink: Int, scale: Float) {
        for (item in page.items) when (item) {
            is RectItem -> {
                if (item.fill != null) { p.style = Paint.Style.FILL; p.color = item.fill; c.drawRect(item.x, item.y, item.x + item.w, item.y + item.h, p) }
                if (item.stroke) {
                    p.style = Paint.Style.STROKE; p.strokeWidth = 1f
                    p.color = (ink and 0x00FFFFFF) or 0x99000000.toInt()
                    c.drawRect(item.x, item.y, item.x + item.w, item.y + item.h, p)
                }
            }
            is TextItem -> {
                val l = item.layout
                val top = l.lineTop(item.l0).toFloat()
                val bot = l.lineBottom(item.l1 - 1).toFloat()
                // Extra room only where no neighbouring line exists, so Bengali marks above/below aren't clipped.
                val padT = if (item.l0 == 0) 40f else 0f
                val padB = if (item.l1 >= l.lineCount) 40f else 0f
                c.save()
                c.translate(item.x, item.y - top)
                c.clipRect(-10f, top - padT, l.width + 10f, bot + padB)
                l.draw(c)
                c.restore()
            }
            is ImgItem -> bitmap(item.file, (item.w * scale).toInt())?.let {
                p.style = Paint.Style.FILL; p.isFilterBitmap = true
                c.drawBitmap(it, null, RectF(item.x, item.y, item.x + item.w, item.y + item.h), p)
            }
        }
    }
}
