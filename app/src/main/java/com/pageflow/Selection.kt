package com.pageflow

import java.text.BreakIterator

/** A position in a rendered DOCX page: which text item on the page and the character offset inside its layout. */
class Anchor(val page: Int, val item: Int, val off: Int)

/** Selection within one page. [a] is always before-or-equal [b]. */
class DocSel(x: Anchor, y: Anchor) {
    val a: Anchor
    val b: Anchor
    init {
        val swap = x.item > y.item || (x.item == y.item && x.off > y.off)
        a = if (swap) y else x
        b = if (swap) x else y
    }
}

/** Simple rectangle in page layout units (no Android dependency so it can be unit-tested). */
class Rc(val l: Float, val t: Float, val r: Float, val b: Float)

private fun clusterBoundary(text: CharSequence, off: Int, forward: Boolean): Int {
    if (text.isEmpty()) return 0
    val o = off.coerceIn(0, text.length)
    val bi = BreakIterator.getCharacterInstance()
    bi.setText(text.toString())
    if (bi.isBoundary(o)) return o
    val r = if (forward) bi.following(o) else bi.preceding(o)
    return if (r == BreakIterator.DONE) o else r
}

/** Snaps an offset to a grapheme-cluster boundary so a Bengali conjunct is never cut in half. */
fun snapToCluster(text: CharSequence, off: Int): Int = clusterBoundary(text, off, false)

private fun isWordChar(s: String, i: Int): Boolean {
    val c = s[i]
    if (Character.isLetterOrDigit(c) || c == '_' || c == '\u200C' || c == '\u200D') return true
    val t = Character.getType(c)
    if (t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt()) return true
    // apostrophe inside a word: don't, ও'র
    if ((c == '\'' || c == '\u2019') && i > 0 && i + 1 < s.length && Character.isLetter(s[i - 1]) && Character.isLetter(s[i + 1])) return true
    return false
}

/**
 * Word around [offset] (end exclusive) or null if there is no word there. Works for Bengali and English:
 * letters, digits, vowel signs, virama and joiners all belong to the word, so conjuncts are never split.
 */
fun wordBounds(text: CharSequence, offset: Int): IntRange? {
    if (text.isEmpty()) return null
    val s = text.toString()
    var o = offset.coerceIn(0, s.length - 1)
    if (!isWordChar(s, o)) {
        if (o > 0 && isWordChar(s, o - 1)) o-- else return null
    }
    var a = o
    while (a > 0 && isWordChar(s, a - 1)) a--
    var b = o + 1
    while (b < s.length && isWordChar(s, b)) b++
    return a until b
}

private fun textItems(page: Page) = page.items.withIndex().filter { it.value is TextItem }

/**
 * Finds the text position at page-layout coordinates ([px], [py]). With [loose] = false the point must lie
 * on a text item; with [loose] = true the closest item is used (for dragging a selection handle).
 */
fun hitTest(page: Page, pageIdx: Int, px: Float, py: Float, loose: Boolean): Anchor? {
    var best: Anchor? = null
    var bestD = Float.MAX_VALUE
    for ((idx, any) in textItems(page)) {
        val it = any as TextItem
        val l = it.layout
        val h = (l.lineBottom(it.l1 - 1) - l.lineTop(it.l0)).toFloat()
        val dy = when { py < it.y -> it.y - py; py > it.y + h -> py - (it.y + h); else -> 0f }
        val dx = when { px < it.x -> it.x - px; px > it.x + l.width -> px - (it.x + l.width); else -> 0f }
        val d = dy * 4f + dx
        if (!loose && (dy > 0f || dx > 24f)) continue
        if (d < bestD) {
            bestD = d
            val ly = (py - it.y + l.lineTop(it.l0)).toInt()
            val line = l.lineForY(ly).coerceIn(it.l0, it.l1 - 1)
            var off = l.offsetAt(line, px - it.x)
            off = off.coerceIn(l.lineStart(line), l.lineVisibleEnd(line).coerceAtLeast(l.lineStart(line)))
            best = Anchor(pageIdx, idx, snapToCluster(l.text, off))
        }
    }
    return best
}

/** Word at a hit position as a selection, or null. */
fun wordSelection(page: Page, a: Anchor): DocSel? {
    val it = page.items.getOrNull(a.item) as? TextItem ?: return null
    val r = wordBounds(it.layout.text, a.off) ?: return null
    return DocSel(Anchor(a.page, a.item, r.first), Anchor(a.page, a.item, r.last + 1))
}

/** Whole paragraph (the part of it that is on this page). */
fun paragraphSelection(page: Page, a: Anchor): DocSel? {
    val it = page.items.getOrNull(a.item) as? TextItem ?: return null
    val l = it.layout
    return DocSel(Anchor(a.page, a.item, l.lineStart(it.l0)), Anchor(a.page, a.item, l.lineVisibleEnd(it.l1 - 1)))
}

/** Rectangles (page layout units) covering [start, end) of one text item. */
fun itemRects(it: TextItem, start: Int, end: Int): List<Rc> {
    val l = it.layout
    val out = ArrayList<Rc>()
    val s = maxOf(start, l.lineStart(it.l0))
    val e = minOf(end, l.lineVisibleEnd(it.l1 - 1))
    if (e <= s) return out
    val base = l.lineTop(it.l0)
    for (line in it.l0 until it.l1) {
        val ls = maxOf(s, l.lineStart(line))
        val le = minOf(e, l.lineVisibleEnd(line))
        if (le <= ls) continue
        val left = if (ls <= l.lineStart(line)) l.lineLeft(line) else l.cursorX(ls)
        val right = if (le >= l.lineVisibleEnd(line)) l.lineRight(line) else l.cursorX(le)
        if (right > left) out.add(Rc(it.x + left, it.y + l.lineTop(line) - base, it.x + right, it.y + l.lineBottom(line) - base))
    }
    return out
}

/** Item-relative [start, end) covered by the selection for a given item index, or null. */
fun selRangeFor(sel: DocSel, idx: Int, it: TextItem): IntRange? {
    if (idx < sel.a.item || idx > sel.b.item) return null
    val l = it.layout
    val s = if (idx == sel.a.item) sel.a.off else l.lineStart(it.l0)
    val e = if (idx == sel.b.item) sel.b.off else l.lineVisibleEnd(it.l1 - 1)
    return if (e > s) s until e else null
}

fun selRects(page: Page, sel: DocSel): List<Rc> {
    val out = ArrayList<Rc>()
    for ((idx, any) in textItems(page)) {
        val it = any as TextItem
        val r = selRangeFor(sel, idx, it) ?: continue
        out.addAll(itemRects(it, r.first, r.last + 1))
    }
    return out
}

fun selText(page: Page, sel: DocSel): String {
    val parts = ArrayList<String>()
    for ((idx, any) in textItems(page)) {
        val it = any as TextItem
        val r = selRangeFor(sel, idx, it) ?: continue
        parts.add(it.layout.text.subSequence(r.first, r.last + 1).toString().replace('\t', ' ').trimEnd('\n'))
    }
    return parts.joinToString("\n")
}

/** Pieces of the selection per paragraph, for storing highlights: (paraId, start, end, text). */
class SelPiece(val paraId: Int, val start: Int, val end: Int, val text: String)

fun selPieces(page: Page, sel: DocSel): List<SelPiece> {
    val out = ArrayList<SelPiece>()
    for ((idx, any) in textItems(page)) {
        val it = any as TextItem
        if (it.paraId < 0) continue
        val r = selRangeFor(sel, idx, it) ?: continue
        out.add(SelPiece(it.paraId, r.first, r.last + 1, it.layout.text.subSequence(r.first, r.last + 1).toString()))
    }
    return out
}

/**
 * Vertical stack of pages fitted to the viewport width, with a gap between pages. Page sizes are in the page's
 * own units (layout units for DOCX, points for PDF). Maps between content space and page space.
 */
class Geom(val ws: FloatArray, val hs: FloatArray, vw: Float, pad: Float, gap: Float) {
    val n = ws.size
    val left = FloatArray(n)
    val top = FloatArray(n)
    val sc = FloatArray(n)
    val totalH: Float

    init {
        var y = gap
        val fw = (vw - 2 * pad).coerceAtLeast(50f)
        for (k in 0 until n) {
            val s = fw / ws[k].coerceAtLeast(1f)
            sc[k] = s; left[k] = pad; top[k] = y
            y += hs[k] * s + gap
        }
        totalH = y
    }

    fun bottom(k: Int) = top[k] + hs[k] * sc[k]

    /** First page whose bottom is below [y]. */
    fun firstVisible(y: Float): Int {
        if (n == 0) return 0
        var lo = 0; var hi = n - 1
        while (lo < hi) { val m = (lo + hi) / 2; if (bottom(m) < y) lo = m + 1 else hi = m }
        return lo
    }

    /** Last page whose top is above [y]. */
    fun lastVisible(y: Float): Int {
        var k = firstVisible(y)
        while (k + 1 < n && top[k + 1] <= y) k++
        return k
    }

    fun toPageX(k: Int, cx: Float) = (cx - left[k]) / sc[k]
    fun toPageY(k: Int, cy: Float) = (cy - top[k]) / sc[k]
    fun fromPageX(k: Int, px: Float) = left[k] + px * sc[k]
    fun fromPageY(k: Int, py: Float) = top[k] + py * sc[k]
}

fun docGeom(pages: List<Page>, idxs: List<Int>, vw: Float, pad: Float, gap: Float) =
    Geom(FloatArray(idxs.size) { pages[idxs[it]].w }, FloatArray(idxs.size) { pages[idxs[it]].h }, vw, pad, gap)
