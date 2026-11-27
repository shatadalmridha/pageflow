package com.pageflow

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.pageflow.TestDocx.p
import com.pageflow.TestDocx.run

class DocxParserTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun paras(d: Doc) = d.sections.flatMap { it.blocks }.filterIsInstance<ParaBlock>()

    @Test fun englishParagraphKeepsFormatting() {
        val d = TestDocx.parse(tmp.root, p(run("Hello ", "<w:b/>"), run("world", "<w:i/><w:u w:val=\"single\"/>")) + TestDocx.sect())
        val ps = paras(d)
        assertEquals(1, ps.size)
        assertEquals(2, ps[0].runs.size)
        assertTrue(ps[0].runs[0].fmt.bold)
        assertTrue(ps[0].runs[1].fmt.italic)
        assertTrue(ps[0].runs[1].fmt.underline)
        assertEquals("Hello ", ps[0].runs[0].text)       // trailing space preserved
    }

    @Test fun bengaliTextSurvivesUtf8RoundTrip() {
        val d = TestDocx.parse(tmp.root, p(run(TestDocx.BN_SENTENCE)) + TestDocx.sect())
        assertEquals(TestDocx.BN_SENTENCE, paras(d)[0].runs[0].text)
    }

    @Test fun conjunctSplitAcrossRunsIsMergedForShaping() {
        // Word often splits one word into several runs with identical formatting.
        val d = TestDocx.parse(tmp.root, p(run("ক"), run("্"), run("ষ")) + TestDocx.sect())
        val r = paras(d)[0].runs
        assertEquals(1, r.size)
        assertEquals(TestDocx.BN_CONJUNCT, r[0].text)
    }

    @Test fun differentFormattingIsNotMerged() {
        val d = TestDocx.parse(tmp.root, p(run("ক"), run("খ", "<w:b/>")) + TestDocx.sect())
        assertEquals(2, paras(d)[0].runs.size)
    }

    @Test fun complexScriptFontSizeAndBoldAreRead() {
        val rpr = "<w:rFonts w:ascii=\"Calibri\" w:cs=\"SolaimanLipi\"/><w:sz w:val=\"24\"/><w:szCs w:val=\"32\"/><w:bCs/>"
        val d = TestDocx.parse(tmp.root, p(run(TestDocx.BN_WORD, rpr)) + TestDocx.sect())
        val f = paras(d)[0].runs[0].fmt
        assertEquals("Calibri", f.font)
        assertEquals("SolaimanLipi", f.fontCs)
        assertEquals(24, f.sizeHp)
        assertEquals(32, f.sizeCsHp)
        assertEquals(true, f.boldCs)
        assertFalse(f.bold)
    }

    @Test fun styleInheritanceAndDefaults() {
        val styles = """
            <w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="22"/></w:rPr></w:rPrDefault>
              <w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>
            <w:style w:type="paragraph" w:default="1" w:styleId="Normal"/>
            <w:style w:type="paragraph" w:styleId="Heading1"><w:basedOn w:val="Normal"/>
              <w:pPr><w:keepNext/><w:spacing w:before="240"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style>
        """.trimIndent()
        val d = TestDocx.parse(tmp.root,
            p(run("Title"), ppr = "<w:pStyle w:val=\"Heading1\"/>") + p(run("Body")) + TestDocx.sect(), styles = styles)
        val (h, b) = paras(d)
        assertTrue(h.runs[0].fmt.bold); assertEquals(32, h.runs[0].fmt.sizeHp)
        assertTrue(h.pf.keepNext); assertEquals(240, h.pf.before); assertEquals(160, h.pf.after)
        assertFalse(b.runs[0].fmt.bold); assertEquals(22, b.runs[0].fmt.sizeHp)
        assertEquals(259, b.pf.line)
    }

    @Test fun explicitPageBreakIsEmittedWithoutBlankParagraph() {
        val d = TestDocx.parse(tmp.root, p(run("A")) + TestDocx.pageBreak() + p(run("B")) + TestDocx.sect())
        val blocks = d.sections[0].blocks
        assertEquals(3, blocks.size)
        assertTrue(blocks[1] === PageBreak)
    }

    @Test fun tableGridSpanAndShadingAreParsed() {
        val styles = """<w:style w:type="table" w:styleId="TableGrid"><w:tblPr><w:tblBorders>
            <w:top w:val="single" w:sz="4"/><w:left w:val="single" w:sz="4"/><w:bottom w:val="single" w:sz="4"/>
            <w:right w:val="single" w:sz="4"/><w:insideH w:val="single" w:sz="4"/><w:insideV w:val="single" w:sz="4"/>
            </w:tblBorders></w:tblPr></w:style>"""
        val tbl = """<w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/></w:tblPr>
            <w:tblGrid><w:gridCol w:w="3000"/><w:gridCol w:w="3000"/></w:tblGrid>
            <w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/><w:shd w:fill="FFFF00"/></w:tcPr>${p(run("Merged"))}</w:tc></w:tr>
            <w:tr><w:tc>${p(run("ক"))}</w:tc><w:tc>${p(run("B"))}</w:tc></w:tr></w:tbl>"""
        val d = TestDocx.parse(tmp.root, tbl + TestDocx.sect(), styles = styles)
        val t = d.sections[0].blocks.filterIsInstance<TableBlock>().single()
        assertTrue(t.rows[1].cells[0].borders!!.any())
        assertEquals(listOf(3000, 3000), t.colW)
        assertEquals(2, t.rows.size)
        assertEquals(2, t.rows[0].cells[0].span)
        assertEquals(0xFFFFFF00.toInt(), t.rows[0].cells[0].fill)
        assertEquals("ক", t.rows[1].cells[0].paras[0].runs[0].text)
    }

    @Test fun bulletAndNumberedLists() {
        val numbering = """
            <w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/><w:lvlText w:val="o"/>
              <w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl></w:abstractNum>
            <w:abstractNum w:abstractNumId="1"><w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/>
              <w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl></w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num><w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>
        """.trimIndent()
        fun li(n: Int, t: String) = p(run(t), ppr = "<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"$n\"/></w:numPr>")
        val d = TestDocx.parse(tmp.root, li(1, "a") + li(2, "x") + li(2, "y") + TestDocx.sect(), numbering = numbering)
        val ps = paras(d)
        assertEquals("\u2022", ps[0].bullet)
        assertEquals("1.", ps[1].bullet)
        assertEquals("2.", ps[2].bullet)
        assertEquals(720, ps[1].pf.left)
        assertEquals(-360, ps[1].pf.first)
    }

    @Test fun sectionGeometryAndMultipleSections() {
        val body = p(run("one"), ppr = TestDocx.sect(w = 12240, h = 15840, m = 720)) +
            p(run("two")) + TestDocx.sect(w = 16838, h = 11906)
        val d = TestDocx.parse(tmp.root, body)
        assertEquals(2, d.sections.size)
        assertEquals(612f, d.sections[0].pageW, 0.01f)
        assertEquals(36f, d.sections[0].ml, 0.01f)
        assertEquals(841.9f, d.sections[1].pageW, 0.1f)        // landscape second section
        assertTrue(d.sections[1].pageW > d.sections[1].pageH)
    }

    @Test fun missingSectPrFallsBackToA4() {
        val d = TestDocx.parse(tmp.root, p(run("x")))
        assertEquals(595.3f, d.sections[0].pageW, 0.1f)
        assertEquals(72f, d.sections[0].ml, 0.01f)
    }

    @Test fun imageIsExtractedToDiskWithSize() {
        val body = TestDocx.image("rId1", 12700L * 200, 12700L * 100) + TestDocx.sect()
        val d = TestDocx.parse(tmp.root, body, media = mapOf("pic.png" to TestDocx.PNG))
        val img = paras(d)[0].images.single()
        assertEquals(200f, img.wPt, 0.01f)
        assertEquals(100f, img.hPt, 0.01f)
        assertTrue(img.file.exists())
        assertArrayEquals(TestDocx.PNG, img.file.readBytes())
    }

    @Test fun deletedTextIsIgnoredInsertedTextIsKept() {
        val body = "<w:p><w:del><w:r><w:delText>gone</w:delText></w:r></w:del><w:ins>${run("kept")}</w:ins></w:p>" + TestDocx.sect()
        assertEquals("kept", paras(TestDocx.parse(tmp.root, body))[0].runs.joinToString("") { it.text })
    }

    @Test fun notAZipGivesFriendlyError() {
        val f = tmp.newFile("bad.docx"); f.writeText("this is not a zip")
        try { DocxParser(f, tmp.newFolder("m")).parse(); fail("expected DocxException") }
        catch (e: DocxException) { assertTrue(e.message!!.contains(".docx")) }
    }

    @Test fun zipWithoutDocumentXmlGivesFriendlyError() {
        val f = tmp.newFile("empty.docx")
        java.util.zip.ZipOutputStream(f.outputStream()).use { z -> z.putNextEntry(java.util.zip.ZipEntry("x.txt")); z.write(1); z.closeEntry() }
        try { DocxParser(f, tmp.newFolder("m2")).parse(); fail("expected DocxException") }
        catch (e: DocxException) { assertTrue(e.message!!.contains("document.xml")) }
    }
}
