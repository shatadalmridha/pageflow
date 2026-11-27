package com.pageflow

import android.graphics.Canvas
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.pageflow.TestDocx.p
import com.pageflow.TestDocx.run
import java.io.File

/** Deterministic stand-in for StaticLayout: fixed line height, fixed characters per line, 10 units per char. */
class FakeLayout(override val text: CharSequence, override val width: Int, private val lineH: Int, private val cpl: Int) : TextLayout {
    private val charW = 10f
    override val lineCount: Int = maxOf(1, (text.length + cpl - 1) / cpl)
    override val height: Int get() = lineCount * lineH
    override fun lineTop(i: Int) = i * lineH
    override fun lineBottom(i: Int) = (i + 1) * lineH
    override fun lineStart(i: Int) = minOf(text.length, i * cpl)
    override fun lineEnd(i: Int) = minOf(text.length, (i + 1) * cpl)
    override fun lineVisibleEnd(i: Int) = lineEnd(i)
    override fun lineLeft(i: Int) = 0f
    override fun lineRight(i: Int) = (lineEnd(i) - lineStart(i)) * charW
    override fun lineForY(y: Int) = (y / lineH).coerceIn(0, lineCount - 1)
    override fun offsetAt(line: Int, x: Float) = (lineStart(line) + (x / charW).toInt()).coerceIn(lineStart(line), lineEnd(line))
    override fun cursorX(offset: Int): Float {
        val line = (offset / cpl).coerceIn(0, lineCount - 1)
        return (offset - lineStart(line)) * charW
    }
    override fun draw(c: Canvas) {}
}

class FakeEngine(private val lineH: Int = 24, private val cpl: Int = 40) : TextEngine {
    var builds = 0
    override fun build(p: ParaBlock, width: Int, cfg: LayoutCfg, bg: Int?): TextLayout {
        builds++
        val t = (p.bullet?.let { "$it " } ?: "") + p.runs.joinToString("") { it.text }
        return FakeLayout(if (t.isEmpty()) " " else t, width, lineH, cpl)
    }
}

class PaginatorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cfg = LayoutCfg(1f, 1f, 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), false, null)
    // A4 with 72pt margins: content box 72*S .. (841.9-72)*S vertically
    private fun doc(vararg blocks: Block, sec: Section? = null) =
        Doc(listOf(sec ?: Section(blocks.toList(), 595.3f, 841.9f, 72f, 72f, 72f, 72f)))

    private fun para(text: String, before: Int = 0, after: Int = 0, pf: PFmt = PFmt(), style: String? = null) =
        ParaBlock(listOf(RunSpec(text, Fmt())), pf.also { it.before = before; it.after = after }, null, emptyList(), 22, style)

    private fun paginate(d: Doc, engine: TextEngine = FakeEngine()) = Paginator(d, cfg, engine).run()

    private fun textItems(pg: Page) = pg.items.filterIsInstance<TextItem>()

    // ---------- basics
    @Test fun emptyDocumentStillHasOnePage() {
        val pages = paginate(Doc(listOf(Section(emptyList(), 595.3f, 841.9f, 72f, 72f, 72f, 72f))))
        assertEquals(1, pages.size)
    }

    @Test fun shortParagraphFitsOnOnePage() {
        val pages = paginate(doc(para("Hello")))
        assertEquals(1, pages.size)
        assertEquals("Hello", pages[0].text)
    }

    @Test fun longTextFlowsAcrossPagesAndKeepsAllText() {
        val lines = 200
        val text = "x".repeat(40 * lines)
        val pages = paginate(doc(para(text)))
        assertTrue(pages.size > 1)
        assertEquals(text.length, pages.sumOf { it.text.replace("\n", "").length })
    }

    @Test fun everyItemStaysInsideMargins() {
        val pages = paginate(doc(*Array(60) { para("paragraph $it " + "word ".repeat(30), before = 120, after = 120) }))
        val top = 72f * S; val bottom = (841.9f - 72f) * S
        for (pg in pages) for (it in textItems(pg)) {
            val h = it.layout.lineBottom(it.l1 - 1) - it.layout.lineTop(it.l0)
            assertTrue("starts above top margin", it.y >= top - 0.01f)
            assertTrue("overflows bottom margin on page", it.y + h <= bottom + 0.01f)
            assertEquals(72f * S, it.x, 0.01f)
        }
    }

    @Test fun pagesCarryGeometryAndFooterPosition() {
        val pg = paginate(doc(para("x")))[0]
        assertEquals(595.3f * S, pg.w, 0.01f)
        assertEquals(841.9f * S, pg.h, 0.01f)
        assertEquals((841.9f - 36f) * S, pg.footerY, 0.01f)
    }

    // ---------- breaks
    @Test fun explicitPageBreakStartsNewPage() {
        val pages = paginate(doc(para("A"), PageBreak, para("B")))
        assertEquals(2, pages.size)
        assertEquals("A", pages[0].text); assertEquals("B", pages[1].text)
    }

    @Test fun trailingPageBreakDoesNotCreateBlankPage() {
        assertEquals(1, paginate(doc(para("A"), PageBreak)).size)
    }

    @Test fun pageBreakBeforeIsHonouredButNotOnEmptyFirstPage() {
        val pbb = PFmt().also { it.pbb = true }
        val pages = paginate(doc(para("A", pf = PFmt().also { it.pbb = true }), para("B", pf = pbb)))
        assertEquals(2, pages.size)
        assertEquals("A", pages[0].text)
    }

    // ---------- spacing
    @Test fun spaceBeforeIsAppliedOnFirstPageButDroppedAfterAutomaticBreak() {
        val first = paginate(doc(para("A", before = 400)))[0]
        assertEquals(72f * S + 400 / 20f * S, textItems(first)[0].y, 0.01f)
        val pages = paginate(doc(para("x".repeat(40 * 80)), para("B", before = 400)))
        // find a page that starts with a flowed continuation: its first y must be exactly the top margin
        assertEquals(72f * S, textItems(pages[1])[0].y, 0.01f)
    }

    @Test fun spaceBeforeIsKeptAfterExplicitPageBreak() {
        val pages = paginate(doc(para("A"), PageBreak, para("B", before = 400)))
        assertEquals(72f * S + 400 / 20f * S, textItems(pages[1])[0].y, 0.01f)
    }

    @Test fun paragraphSpacingAddsUp() {
        val pg = paginate(doc(para("A", after = 200), para("B", before = 100)))[0]
        val a = textItems(pg)[0]; val b = textItems(pg)[1]
        assertEquals(a.y + 24 + (200 + 100) / 20f * S, b.y, 0.01f)
    }

    @Test fun contextualSpacingRemovesGapBetweenSameStyleParagraphs() {
        val ctx = { PFmt().also { it.ctx = true } }
        val pg = paginate(doc(para("A", after = 200, pf = ctx(), style = "List"), para("B", before = 200, pf = ctx(), style = "List")))[0]
        val a = textItems(pg)[0]; val b = textItems(pg)[1]
        assertEquals(a.y + 24, b.y, 0.01f)
    }

    // ---------- widow / orphan / keep with next
    private fun fillTo(linesLeft: Int): List<Block> {
        // content box height / 24 = total lines per page; leave exactly `linesLeft` lines free
        val total = ((841.9f - 144f) * S / 24).toInt()
        return listOf(para("f".repeat(40 * (total - linesLeft))))
    }

    @Test fun orphanControlMovesWholeParagraphWhenOnlyOneLineFits() {
        val blocks = fillTo(1) + para("o".repeat(40 * 4))
        val pages = paginate(doc(*blocks.toTypedArray()))
        assertEquals(2, pages.size)
        assertEquals(1, textItems(pages[0]).size)       // only the filler on page 1
        assertEquals(4, textItems(pages[1])[0].l1 - textItems(pages[1])[0].l0)
    }

    @Test fun widowControlNeverLeavesASingleLineOnNextPage() {
        // 4-line paragraph with 3 lines free would leave 1 line alone -> moves 1 line over
        val blocks = fillTo(3) + para("w".repeat(40 * 4))
        val pages = paginate(doc(*blocks.toTypedArray()))
        val last = textItems(pages[1]).last()
        assertTrue("page 2 must hold >= 2 lines of the paragraph", last.l1 - last.l0 >= 2)
    }

    @Test fun keepWithNextPullsHeadingToNextPage() {
        val heading = PFmt().also { it.keepNext = true }
        val blocks = fillTo(2) + para("H", pf = heading) + para("b".repeat(40 * 5))
        val pages = paginate(doc(*blocks.toTypedArray()))
        assertEquals("H", textItems(pages[1])[0].layout.text.toString())
    }

    // ---------- images
    private fun img(wPt: Float, hPt: Float) = ImgSpec(File(tmp.root, "i.png"), wPt, hPt)
    private fun imgPara(i: ImgSpec, jc: String? = null) =
        ParaBlock(emptyList(), PFmt().also { it.jc = jc }, null, listOf(i), 22)

    @Test fun oversizedImageIsScaledToContentWidth() {
        val pg = paginate(doc(imgPara(img(2000f, 1000f))))[0]
        val it = pg.items.filterIsInstance<ImgItem>().single()
        val cw = (595.3f - 144f) * S
        assertEquals(cw, it.w, 0.01f)
        assertEquals(cw / 2f, it.h, 0.01f)             // aspect ratio kept
    }

    @Test fun veryTallImageIsShrunkToFitOnePage() {
        val it = paginate(doc(imgPara(img(100f, 5000f))))[0].items.filterIsInstance<ImgItem>().single()
        assertTrue(it.h <= (841.9f - 144f) * S + 0.01f)
    }

    @Test fun imageMovesToNextPageWhenItDoesNotFit() {
        val blocks = fillTo(3) + imgPara(img(300f, 300f))
        val pages = paginate(doc(*blocks.toTypedArray()))
        assertEquals(2, pages.size)
        assertEquals(1, pages[1].items.filterIsInstance<ImgItem>().size)
    }

    @Test fun centeredImageIsCentered() {
        val it = paginate(doc(imgPara(img(100f, 50f), "center")))[0].items.filterIsInstance<ImgItem>().single()
        assertEquals(72f * S + ((595.3f - 144f) * S - 100f * S) / 2f, it.x, 0.01f)
    }

    // ---------- tables
    private val bd = Border(null, 4, "single")
    private val allSides = Borders(bd, bd, bd, bd)
    private fun cell(t: String, span: Int = 1) = CellSpec(span, listOf(para(t)), null, 0, allSides)
    private fun table(rows: Int, cols: Int = 2) = TableBlock(
        List(cols) { 4500 },
        List(rows) { r -> RowSpec(List(cols) { c -> cell("r${r}c$c") }) }
    )

    @Test fun smallTableStaysOnOnePageWithBorders() {
        val pg = paginate(doc(table(3)))[0]
        assertEquals(6, pg.items.filterIsInstance<RectItem>().size)
        assertTrue(pg.items.filterIsInstance<RectItem>().all { it.borders?.any() == true })
        assertEquals(6, textItems(pg).size)
    }

    @Test fun tableRowsNeverSplitAndFlowToNextPage() {
        val pages = paginate(doc(table(80)))
        assertTrue(pages.size > 1)
        val bottom = (841.9f - 72f) * S
        for (pg in pages) for (r in pg.items.filterIsInstance<RectItem>()) assertTrue(r.y + r.h <= bottom + 0.01f)
        assertEquals(160, pages.sumOf { textItems(it).size })
    }

    @Test fun tableColumnsAreScaledDownToContentWidth() {
        val wide = TableBlock(listOf(20000, 20000), listOf(RowSpec(listOf(cell("a"), cell("b")))))
        val rects = paginate(doc(wide))[0].items.filterIsInstance<RectItem>()
        val right = rects.maxOf { it.x + it.w }
        assertTrue(right <= (595.3f - 72f) * S + 0.01f)
    }

    @Test fun gridSpanCellCoversBothColumns() {
        val t = TableBlock(listOf(3000, 3000), listOf(RowSpec(listOf(cell("m", 2)))))
        val r = paginate(doc(t))[0].items.filterIsInstance<RectItem>().single()
        assertEquals(6000 / 20f * S, r.w, 0.01f)
    }

    @Test fun cellFillIsKeptOnEveryTheme() {
        val t = TableBlock(listOf(3000), listOf(RowSpec(listOf(CellSpec(1, listOf(para("a")), 0xFFFFFF00.toInt())))))
        val dark = LayoutCfg(1f, 1f, 0xFFE0E0E0.toInt(), 0xFF202020.toInt(), false, null)
        assertEquals(0xFFFFFF00.toInt(), Paginator(doc(t), dark, FakeEngine()).run()[0].items.filterIsInstance<RectItem>().single().fill)
        assertEquals(0xFFFFFF00.toInt(), paginate(doc(t))[0].items.filterIsInstance<RectItem>().single().fill)
    }

    @Test fun cellVerticalAlignmentCentersShortCell() {
        val tall = CellSpec(1, listOf(para("t".repeat(120))), null, 0, null, "top")
        val short = CellSpec(1, listOf(para("s")), null, 0, null, "center")
        val items = textItems(paginate(doc(TableBlock(listOf(3000, 3000), listOf(RowSpec(listOf(tall, short))))))[0])
        assertEquals(24f, items[1].y - items[0].y, 0.01f)       // (72 - 24) / 2
    }

    @Test fun tableCanBeCentered() {
        val t = TableBlock(listOf(3000), listOf(RowSpec(listOf(cell("a")))), jc = "center")
        val r = paginate(doc(t))[0].items.filterIsInstance<RectItem>().single()
        val avail = (595.3f - 144f) * S
        assertEquals(72f * S + (avail - 300f) / 2f, r.x, 0.01f)
    }

    // ---------- shading, borders, anchored images
    @Test fun paragraphShadingAndBordersGoBehindText() {
        val b = Border(0xFFFF0000.toInt(), 8, "single")
        val pf = PFmt().also { it.shd = 0xFFDDEEFF.toInt(); it.bdr = Borders(b, b, b, b) }
        val pg = paginate(doc(para("shaded", pf = pf)))[0]
        val r = pg.back.filterIsInstance<RectItem>().single()
        assertEquals(0xFFDDEEFF.toInt(), r.fill)
        assertEquals(72f * S, r.x, 0.01f)
        assertTrue(r.borders!!.top != null && r.borders!!.bottom != null)
    }

    @Test fun shadedParagraphSplitAcrossPagesDrawsTopBorderOnlyOnFirstChunk() {
        val b = Border(null, 4, "single")
        val pf = PFmt().also { it.shd = 0xFFEEEEEE.toInt(); it.bdr = Borders(b, b, b, b) }
        val pages = paginate(doc(para("x".repeat(40 * 90), pf = pf)))
        val first = pages[0].back.filterIsInstance<RectItem>().single()
        val second = pages[1].back.filterIsInstance<RectItem>().single()
        assertNotNull(first.borders!!.top); assertNull(first.borders!!.bottom)
        assertNull(second.borders!!.top); assertNotNull(second.borders!!.bottom)
    }

    @Test fun behindTextAnchoredImageIsPositionedAbsolutelyAndDoesNotMoveText() {
        val an = ImgAnchor(true, "none", "page", null, 10f, "page", "top", 0f)
        val withImg = ParaBlock(listOf(RunSpec("Body", Fmt())), PFmt(), null, listOf(ImgSpec(File(tmp.root, "i.png"), 100f, 50f, an)), 22)
        val pg = paginate(doc(withImg))[0]
        val img = pg.back.filterIsInstance<ImgItem>().single()
        assertEquals(10f * S, img.x, 0.01f)
        assertEquals(0f, img.y, 0.01f)
        assertEquals(72f * S, textItems(pg)[0].y, 0.01f)         // text did not move
        assertTrue(pg.front.isEmpty())
    }

    @Test fun inFrontAnchoredImageGoesToFrontLayer() {
        val an = ImgAnchor(false, "none", "margin", "right", 0f, "margin", "bottom", 0f)
        val p = ParaBlock(emptyList(), PFmt(), null, listOf(ImgSpec(File(tmp.root, "i.png"), 100f, 50f, an)), 22)
        val pg = paginate(doc(p))[0]
        val img = pg.front.filterIsInstance<ImgItem>().single()
        assertEquals(72f * S + (595.3f - 144f) * S - 100f * S, img.x, 0.01f)
        assertEquals((841.9f - 72f) * S - 50f * S, img.y, 0.01f)
    }

    @Test fun squareWrapAnchoredImageStaysInFlowNeverOverlappingText() {
        val an = ImgAnchor(false, "square", "column", "center", 0f, "paragraph", "top", 0f)
        val p = ParaBlock(emptyList(), PFmt(), null, listOf(ImgSpec(File(tmp.root, "i.png"), 100f, 50f, an)), 22)
        val pg = paginate(doc(p, para("after")))[0]
        val img = pg.items.filterIsInstance<ImgItem>().single()
        assertTrue(textItems(pg)[0].y >= img.y + img.h)
    }

    // ---------- sections
    @Test fun newSectionWithDifferentGeometryStartsNewPageWithItsOwnSize() {
        val s1 = Section(listOf(para("A")), 595.3f, 841.9f, 72f, 72f, 72f, 72f)
        val s2 = Section(listOf(para("B")), 841.9f, 595.3f, 36f, 36f, 36f, 36f)
        val pages = paginate(Doc(listOf(s1, s2)))
        assertEquals(2, pages.size)
        assertEquals(841.9f * S, pages[1].w, 0.01f)
        assertEquals(36f * S, textItems(pages[1])[0].x, 0.01f)
    }

    @Test fun continuousSectionWithSameGeometryDoesNotBreakPage() {
        val s1 = Section(listOf(para("A")), 595.3f, 841.9f, 72f, 72f, 72f, 72f)
        val s2 = Section(listOf(para("B")), 595.3f, 841.9f, 72f, 72f, 72f, 72f, continuous = true)
        assertEquals(1, paginate(Doc(listOf(s1, s2))).size)
    }

    // ---------- cancellation / progress
    @Test fun cancellationStopsLayout() {
        var n = 0
        try {
            Paginator(doc(*Array(50) { para("x") }), cfg, FakeEngine()).run(null) { n++ > 5 }
            fail("expected cancellation")
        } catch (e: java.util.concurrent.CancellationException) { /* ok */ }
    }

    @Test fun progressCallbackOnlyReceivesCompletePages() {
        val seen = ArrayList<Int>()
        val all = Paginator(doc(para("x".repeat(40 * 24 * 40))), cfg, FakeEngine()).run({ seen.add(it.size) }, { false })
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it < all.size })          // never publishes the page still being filled
        assertEquals(seen.sorted(), seen)               // monotonically growing
    }

    // ---------- full pipeline: DOCX -> parser -> paginator (Bengali + English)
    @Test fun mixedBengaliEnglishDocumentEndToEnd() {
        val body = StringBuilder()
        body.append(p(run("Title ${TestDocx.BN_WORD}", "<w:b/><w:sz w:val=\"36\"/>")))
        repeat(40) { body.append(p(run("Paragraph $it: "), run(TestDocx.BN_SENTENCE + " ".repeat(1) + "text ".repeat(25)))) }
        body.append(TestDocx.pageBreak())
        body.append(p(run("Last page ${TestDocx.BN_CONJUNCT}")))
        body.append(TestDocx.sect())
        val d = TestDocx.parse(tmp.root, body.toString())
        val pages = paginate(d)
        assertTrue(pages.size >= 3)
        val all = pages.joinToString("\n") { it.text }
        assertTrue(all.contains(TestDocx.BN_SENTENCE))
        assertTrue(all.contains("Title ${TestDocx.BN_WORD}"))
        assertTrue(pages.last().text.contains(TestDocx.BN_CONJUNCT))
    }

    @Test fun largeDocumentPaginatesQuicklyAndLinearly() {
        val body = StringBuilder()
        repeat(3000) { body.append(p(run("Line $it ${TestDocx.BN_SENTENCE}"))) }
        body.append(TestDocx.sect())
        val d = TestDocx.parse(tmp.root, body.toString())
        val t0 = System.nanoTime()
        val e = FakeEngine()
        val pages = paginate(d, e)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(pages.size > 30)
        assertEquals(3000, e.builds)                     // each paragraph measured exactly once
        assertTrue("took ${ms}ms", ms < 5000)
    }

    @Test fun listItemsKeepTheirBulletInLayoutText() {
        val numbering = """<w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/>
            </w:lvl></w:abstractNum><w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>"""
        val li = p(run(TestDocx.BN_WORD), ppr = "<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>")
        val d = TestDocx.parse(tmp.root, li + li + TestDocx.sect(), numbering = numbering)
        val pg = paginate(d)[0]
        assertTrue(pg.text.startsWith("1. ${TestDocx.BN_WORD}"))
        assertTrue(pg.text.contains("2. ${TestDocx.BN_WORD}"))
    }
}
