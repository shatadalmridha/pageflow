package com.pageflow

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.pageflow.TestDocx.p
import com.pageflow.TestDocx.run

/** Parser-level checks for the formatting that makes a DOCX look like it did in Word. */
class FidelityTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun paras(d: Doc) = d.sections.flatMap { it.blocks }.filterIsInstance<ParaBlock>()
    private fun parse(body: String, styles: String? = null, theme: String? = null, media: Map<String, ByteArray> = emptyMap()) =
        TestDocx.parse(tmp.root, body + TestDocx.sect(), styles = styles, theme = theme, media = media)

    // ---------- colours
    @Test fun explicitHexColourIsKept() {
        val f = paras(parse(p(run("x", "<w:color w:val=\"C00000\"/>"))))[0].runs[0].fmt
        assertEquals(0xFFC00000.toInt(), f.color)
    }

    @Test fun autoColourStaysNullSoTheThemeCanApply() {
        assertNull(paras(parse(p(run("x", "<w:color w:val=\"auto\"/>"))))[0].runs[0].fmt.color)
    }

    @Test fun themeColourIsResolvedWhenNoHexGiven() {
        val f = paras(parse(p(run("x", "<w:color w:val=\"auto\" w:themeColor=\"accent1\"/>")), theme = TestDocx.THEME))[0].runs[0].fmt
        assertEquals(0xFF4472C4.toInt(), f.color)
    }

    @Test fun hexBeatsThemeReference() {
        val f = paras(parse(p(run("x", "<w:color w:val=\"FF0000\" w:themeColor=\"accent1\"/>")), theme = TestDocx.THEME))[0].runs[0].fmt
        assertEquals(0xFFFF0000.toInt(), f.color)
    }

    @Test fun themeShadeDarkensTheColour() {
        val f = paras(parse(p(run("x", "<w:color w:val=\"auto\" w:themeColor=\"accent1\" w:themeShade=\"80\"/>")), theme = TestDocx.THEME))[0].runs[0].fmt
        val r = (f.color!! shr 16) and 255
        assertTrue("shade should darken 0x44 (68): got $r", r in 30..38)
    }

    @Test fun themeFontsAreResolvedForLatinAndBengali() {
        val rpr = "<w:rFonts w:asciiTheme=\"minorHAnsi\" w:cstheme=\"minorBidi\"/>"
        val f = paras(parse(p(run("x", rpr)), theme = TestDocx.THEME))[0].runs[0].fmt
        assertEquals("Calibri", f.font)
        assertEquals("Vrinda", f.fontCs)
    }

    @Test fun highlightAndRunShadingAreRead() {
        val f = paras(parse(p(run("x", "<w:highlight w:val=\"green\"/><w:shd w:val=\"clear\" w:fill=\"FFFF00\"/>"))))[0].runs[0].fmt
        assertEquals(0xFF00FF00.toInt(), f.highlight)
        assertEquals(0xFFFFFF00.toInt(), f.bgFill)
    }

    @Test fun letterSpacingStrikeAndCapsAreRead() {
        val f = paras(parse(p(run("x", "<w:spacing w:val=\"20\"/><w:strike/><w:caps/><w:vertAlign w:val=\"superscript\"/>"))))[0].runs[0].fmt
        assertEquals(20, f.spacing); assertTrue(f.strike); assertTrue(f.caps); assertEquals(1, f.vert)
    }

    // ---------- paragraph formatting
    @Test fun paragraphShadingBordersIndentsAndExactLineHeight() {
        val ppr = "<w:pBdr><w:top w:val=\"single\" w:sz=\"8\" w:color=\"FF0000\"/></w:pBdr><w:shd w:val=\"clear\" w:fill=\"DDEEFF\"/>" +
            "<w:spacing w:line=\"300\" w:lineRule=\"exact\"/><w:ind w:left=\"720\" w:right=\"360\"/>"
        val pf = paras(parse(p(run("x"), ppr = ppr)))[0].pf
        assertEquals(0xFFDDEEFF.toInt(), pf.shd)
        assertEquals(8, pf.bdr!!.top!!.szEighth)
        assertEquals(0xFFFF0000.toInt(), pf.bdr!!.top!!.color)
        assertEquals("exact", pf.lineRule); assertEquals(300, pf.line)
        assertEquals(720, pf.left); assertEquals(360, pf.right)
    }

    @Test fun paragraphIdsAreUniqueOrderedAndStableAcrossParses() {
        val body = p(run("a")) + p(run("b")) + "<w:tbl><w:tblGrid><w:gridCol w:w=\"3000\"/></w:tblGrid><w:tr><w:tc>${p(run("c"))}</w:tc></w:tr></w:tbl>" + p(run("d"))
        val ids1 = paras(parse(body)).map { it.id }
        val ids2 = paras(TestDocx.parse(tmp.newFolder("again"), body + TestDocx.sect())).map { it.id }
        assertEquals(listOf(0, 1, 2, 3), ids1)
        assertEquals(ids1, ids2)
    }

    // ---------- tables
    private val tableStyle = """<w:style w:type="table" w:styleId="Fancy"><w:tblPr><w:tblBorders>
        <w:insideH w:val="single" w:sz="4" w:color="999999"/></w:tblBorders></w:tblPr>
        <w:tblStylePr w:type="firstRow"><w:rPr><w:b/><w:color w:val="FFFFFF"/></w:rPr><w:tcPr><w:shd w:val="clear" w:fill="4472C4"/></w:tcPr></w:tblStylePr>
        <w:tblStylePr w:type="band1Horz"><w:tcPr><w:shd w:val="clear" w:fill="E7ECF6"/></w:tcPr></w:tblStylePr></w:style>"""

    private fun fancyTable(extraCell: String = "") = """<w:tbl><w:tblPr><w:tblStyle w:val="Fancy"/>
        <w:tblLook w:firstRow="1" w:lastRow="0" w:firstColumn="0" w:lastColumn="0" w:noHBand="0" w:noVBand="1"/></w:tblPr>
        <w:tblGrid><w:gridCol w:w="3000"/></w:tblGrid>
        <w:tr><w:tc>${p(run("H"))}</w:tc></w:tr>
        <w:tr><w:tc>$extraCell${p(run("one"))}</w:tc></w:tr>
        <w:tr><w:tc>${p(run("two"))}</w:tc></w:tr></w:tbl>"""

    @Test fun tableStyleHeaderRowGetsFillBoldAndWhiteText() {
        val t = parse(fancyTable(), styles = tableStyle).sections[0].blocks.filterIsInstance<TableBlock>().single()
        val head = t.rows[0].cells[0]
        assertEquals(0xFF4472C4.toInt(), head.fill)
        assertTrue(head.paras[0].runs[0].fmt.bold)
        assertEquals(0xFFFFFFFF.toInt(), head.paras[0].runs[0].fmt.color)
    }

    @Test fun tableStyleBandingAlternatesRows() {
        val t = parse(fancyTable(), styles = tableStyle).sections[0].blocks.filterIsInstance<TableBlock>().single()
        assertEquals(0xFFE7ECF6.toInt(), t.rows[1].cells[0].fill)    // first body row = band1
        assertNull(t.rows[2].cells[0].fill)                          // second body row = band2 (unshaded)
    }

    @Test fun tableStyleInsideBordersApplyOnlyBetweenRows() {
        val t = parse(fancyTable(), styles = tableStyle).sections[0].blocks.filterIsInstance<TableBlock>().single()
        assertNull(t.rows[0].cells[0].borders!!.top)                                   // outer edge: none defined
        assertEquals(0xFF999999.toInt(), t.rows[1].cells[0].borders!!.top!!.color)      // inner edge: insideH
    }

    @Test fun directCellFormattingOverridesStyle() {
        val body = """<w:tbl><w:tblPr><w:tblStyle w:val="Fancy"/></w:tblPr><w:tblGrid><w:gridCol w:w="3000"/></w:tblGrid>
            <w:tr><w:tc><w:tcPr><w:shd w:val="clear" w:fill="00FF00"/><w:vAlign w:val="center"/>
            <w:tcBorders><w:bottom w:val="single" w:sz="12" w:color="00AA00"/></w:tcBorders></w:tcPr>${p(run("x"))}</w:tc></w:tr></w:tbl>"""
        val c = parse(body, styles = tableStyle).sections[0].blocks.filterIsInstance<TableBlock>().single().rows[0].cells[0]
        assertEquals(0xFF00FF00.toInt(), c.fill)
        assertEquals("center", c.vAlign)
        assertEquals(12, c.borders!!.bottom!!.szEighth)
        assertEquals(0xFF00AA00.toInt(), c.borders!!.bottom!!.color)
    }

    @Test fun tableCellTypesFollowWordsPrecedence() {
        val look = TblLook(firstRow = true, lastRow = true, firstCol = true, lastCol = false, noHBand = false, noVBand = true)
        assertEquals(listOf("wholeTable", "firstCol", "firstRow", "nwCell"), tableCellTypes(0, 0, 4, 3, look))
        assertTrue(tableCellTypes(1, 1, 4, 3, look).contains("band1Horz"))
        assertTrue(tableCellTypes(2, 1, 4, 3, look).contains("band2Horz"))
        assertTrue(tableCellTypes(3, 1, 4, 3, look).contains("lastRow"))
        assertFalse(tableCellTypes(3, 1, 4, 3, look).any { it.startsWith("band") && it.endsWith("Horz") })
    }

    @Test fun verticalMergeHidesTheBorderBetweenMergedCells() {
        val bdr = "<w:tcBorders><w:top w:val=\"single\" w:sz=\"4\"/><w:bottom w:val=\"single\" w:sz=\"4\"/></w:tcBorders>"
        val body = """<w:tbl><w:tblGrid><w:gridCol w:w="3000"/></w:tblGrid>
            <w:tr><w:tc><w:tcPr><w:vMerge w:val="restart"/>$bdr</w:tcPr>${p(run("m"))}</w:tc></w:tr>
            <w:tr><w:tc><w:tcPr><w:vMerge/>$bdr</w:tcPr>${p()}</w:tc></w:tr></w:tbl>"""
        val t = parse(body).sections[0].blocks.filterIsInstance<TableBlock>().single()
        assertNull(t.rows[0].cells[0].borders!!.bottom)
        assertNull(t.rows[1].cells[0].borders!!.top)
        assertNotNull(t.rows[1].cells[0].borders!!.bottom)
    }

    // ---------- images
    @Test fun anchoredImageKeepsPositionAndWrapInformation() {
        val body = TestDocx.anchorImage("rId1", 100, 50, true, "page", 127000L, "page", "top")
        val img = paras(parse(body, media = mapOf("a.png" to TestDocx.PNG)))[0].images.single()
        val a = img.anchor!!
        assertTrue(a.behind); assertEquals("none", a.wrap)
        assertEquals("page", a.hRel); assertEquals(10f, a.hOffPt, 0.01f)
        assertEquals("top", a.vAlign)
        assertEquals(100f, img.wPt, 0.01f)
    }

    @Test fun squareWrapIsRecognised() {
        val body = TestDocx.anchorImage("rId1", 100, 50, false, "column", 0L, "paragraph", "top", wrap = "wrapSquare")
        assertEquals("square", paras(parse(body, media = mapOf("a.png" to TestDocx.PNG)))[0].images.single().anchor!!.wrap)
    }

    @Test fun inlineImageHasNoAnchor() {
        val body = TestDocx.image("rId1", 12700L * 80, 12700L * 40)
        assertNull(paras(parse(body, media = mapOf("a.png" to TestDocx.PNG)))[0].images.single().anchor)
    }
}
