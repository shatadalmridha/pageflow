package com.pageflow

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ThemeTest {
    @Test fun parseHexAcceptsCommonForms() {
        assertEquals(0xFFFDF5E6.toInt(), parseHex("#FDF5E6"))
        assertEquals(0xFFFDF5E6.toInt(), parseHex("fdf5e6"))
        assertEquals(0xFFFFFFFF.toInt(), parseHex("#fff"))
        assertNull(parseHex("#12345")); assertNull(parseHex("zzzzzz")); assertNull(parseHex(""))
    }

    @Test fun oldLaceIsExactlyTheRequestedColour() {
        val t = Themes.presets.first { it.name == "Old Lace" }
        assertEquals(0xFFFDF5E6.toInt(), t.page)
        assertEquals(t.page, t.header); assertEquals(t.page, t.footer)
        assertEquals(253, (t.page shr 16) and 255); assertEquals(245, (t.page shr 8) and 255); assertEquals(230, t.page and 255)
    }

    @Test fun allNinePresetsExistWithComfortableContrast() {
        val names = Themes.presets.map { it.name }
        assertEquals(listOf("Classic White", "Old Lace", "Warm Cream", "Soft Ivory", "Sepia", "Soft Sage", "Mist Blue", "Dark Grey", "Pure Black"), names)
        assertEquals(9, Themes.presets.map { it.id }.toSet().size)
        for (t in Themes.presets) {
            assertTrue("${t.name} contrast ${contrast(t.page, t.ink)}", contrast(t.page, t.ink) >= 7f)
            assertNull("${t.name}: ${Themes.warning(t)}", Themes.warning(t))
        }
    }

    @Test fun darkThemesAreDetected() {
        assertTrue(Themes.presets.first { it.id == "black" }.dark)
        assertTrue(Themes.presets.first { it.id == "darkgrey" }.dark)
        assertFalse(Themes.presets.first { it.id == "oldlace" }.dark)
    }

    @Test fun findFallsBackToOldLaceAndHonoursCustom() {
        assertEquals("oldlace", Themes.find("nonsense", null).id)
        val c = ReaderTheme("custom", "Custom", 1, 2, 3, 4)
        assertEquals(c, Themes.find("custom", c))
        assertEquals("oldlace", Themes.find("custom", null).id)
    }

    @Test fun contrastMathMatchesWcag() {
        assertEquals(21f, contrast(BLACK, WHITE), 0.01f)
        assertEquals(1f, contrast(WHITE, WHITE), 0.001f)
        assertEquals(WHITE, bestOn(BLACK)); assertEquals(BLACK, bestOn(WHITE))
    }

    @Test fun lowContrastAndExtremeContrastAreFlagged() {
        assertNotNull(Themes.warning(ReaderTheme("x", "x", 0xFF808080.toInt(), 0xFF8A8A8A.toInt(), WHITE, WHITE)))
        assertNotNull(Themes.warning(ReaderTheme("x", "x", WHITE, BLACK, WHITE, WHITE)))
    }

    @Test fun hsvRoundTrip() {
        for (c in listOf(0xFF3366CC.toInt(), 0xFFFDF5E6.toInt(), 0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFF808080.toInt())) {
            val (h, s, v) = colorToHsv(c)
            val b = hsvToColor(h, s, v)
            for (sh in listOf(16, 8, 0)) assertTrue(Math.abs(((c shr sh) and 255) - ((b shr sh) and 255)) <= 1)
        }
    }

    @Test fun hexRoundTrip() = assertEquals("#FDF5E6", toHex(0xFFFDF5E6.toInt()))

    @Test fun blendEndpoints() {
        assertEquals(WHITE, blend(WHITE, BLACK, 0f)); assertEquals(BLACK, blend(WHITE, BLACK, 1f))
    }
}

class ColourPolicyTest {
    private val ink = 0xFF1A1A1A.toInt(); private val white = WHITE

    @Test fun automaticTextTakesThemeInk() = assertEquals(ink, resolveTextColor(null, null, ink, white))

    @Test fun documentColoursArePreserved() {
        val red = 0xFFC00000.toInt()
        assertEquals(red, resolveTextColor(red, null, ink, white))
        val blue = 0xFF1F4E79.toInt()
        assertEquals(blue, resolveTextColor(blue, null, ink, 0xFFFDF5E6.toInt()))
    }

    @Test fun invisibleTextIsReplaced() {
        assertEquals(ink, resolveTextColor(WHITE, null, ink, white))
        assertEquals(0xFFE0E0E0.toInt(), resolveTextColor(BLACK, null, 0xFFE0E0E0.toInt(), 0xFF000000.toInt()))
    }

    @Test fun whiteTextOnADarkFillIsKept() = assertEquals(WHITE, resolveTextColor(WHITE, 0xFF1F3864.toInt(), ink, white))

    @Test fun automaticTextOnShadingPicksBlackOrWhite() {
        assertEquals(BLACK, resolveTextColor(null, 0xFFFFFF00.toInt(), 0xFFE0E0E0.toInt(), 0xFF202020.toInt()))
        assertEquals(WHITE, resolveTextColor(null, 0xFF1F3864.toInt(), ink, white))
    }
}

class ViewportMathTest {
    private fun base() = VpState(vw = 1000f, vh = 2000f, cw = 1000f, ch = 10000f).clamped()

    @Test fun pinchKeepsTheContentUnderTheFingersFixed() {
        var s = base().panBy(0f, -3000f)
        val (ax, ay) = 400f to 900f
        val cx = s.toContentX(ax); val cy = s.toContentY(ay)
        s = s.zoomBy(2.5f, ax, ay)
        assertEquals(cx, s.toContentX(ax), 0.01f); assertEquals(cy, s.toContentY(ay), 0.01f)
        s = s.zoomBy(1f / 2.5f, ax, ay)               // zoom back out: still anchored, not drifting to a corner
        assertEquals(cx, s.toContentX(ax), 0.01f); assertEquals(cy, s.toContentY(ay), 0.01f)
    }

    @Test fun zoomOutAroundAnyPointNeverJumps() {
        var s = base().panBy(0f, -5000f).zoomBy(3f, 500f, 1000f)
        for (anchor in listOf(100f to 200f, 900f to 1800f, 500f to 1000f)) {
            val cy = s.toContentY(anchor.second); val cx = s.toContentX(anchor.first)
            val z = s.zoomBy(0.8f, anchor.first, anchor.second)
            if (z.scale > 1f) { assertEquals(cy, z.toContentY(anchor.second), 0.5f); assertEquals(cx, z.toContentX(anchor.first), 0.5f) }
            s = z
        }
    }

    @Test fun panningIsClampedToTheContent() {
        val s = base().panBy(500f, 99999f)
        assertEquals(0f, s.ty, 0.01f)
        assertEquals(2000f - 10000f, base().panBy(0f, -99999f).ty, 0.01f)
        assertEquals(0f, s.tx, 0.01f)                                   // 1x width fits: centred, no sideways drift
    }

    @Test fun zoomedContentCanPanSidewaysWithinBounds() {
        val z = base().zoomBy(2f, 0f, 0f)
        val left = z.panBy(-99999f, 0f); val right = z.panBy(99999f, 0f)
        assertEquals(1000f - 2000f, left.tx, 0.01f); assertEquals(0f, right.tx, 0.01f)
    }

    @Test fun scaleIsLimited() {
        assertEquals(1f, base().zoomBy(0.1f, 0f, 0f).scale, 0.001f)
        assertEquals(6f, base().zoomBy(100f, 0f, 0f).scale, 0.001f)
    }

    @Test fun factorOneChangesNothing() {
        val s = base().panBy(0f, -1234f)
        val z = s.zoomBy(1f, 321f, 654f)
        assertEquals(s.tx, z.tx, 0.001f); assertEquals(s.ty, z.ty, 0.001f)
    }

    @Test fun shortContentIsCentredWhenAskedTo() {
        val s = VpState(vw = 1000f, vh = 2000f, cw = 1000f, ch = 1000f, centerY = true).clamped()
        assertEquals(500f, s.ty, 0.01f)
        assertEquals(0f, VpState(vw = 1000f, vh = 2000f, cw = 1000f, ch = 1000f, centerY = false).clamped().ty, 0.01f)
    }

    @Test fun positionIsPreservedWhenViewportIsResizedWithinBounds() {
        val s = base().panBy(0f, -3000f)
        assertEquals(-3000f, s.withViewport(1000f, 2000f).ty, 0.01f)
    }
}

class SelectionTest {
    private fun page(text: String = "Hello world foo bar"): Page {
        val pg = Page(595f, 842f, 800f)
        pg.items.add(TextItem(FakeLayout(text, 400, 24, 10), 100f, 200f, 0, FakeLayout(text, 400, 24, 10).lineCount, 5))
        return pg
    }
    private fun item(pg: Page) = pg.items[0] as TextItem

    @Test fun englishWordBounds() {
        assertEquals(6..10, wordBounds("Hello world", 7))
        assertEquals(0..4, wordBounds("Hello world", 0))
        assertEquals(6..10, wordBounds("Hello world", 11))          // end of text selects the last word
        assertNull(wordBounds("Hello  world", 6))                   // between words
        assertEquals(0 until 5, wordBounds("don't!", 1))
    }

    @Test fun bengaliWordBoundsKeepConjunctsAndVowelSigns() {
        val s = TestDocx.BN_SENTENCE                                // আমি বাংলায় লিখি।
        val w = s.indexOf("বাংলায়")
        val r = wordBounds(s, w + 3)!!
        assertEquals(w until w + "বাংলায়".length, r)
        val c = "ক্ষ জ্ঞ"
        assertEquals(0 until 3, wordBounds(c, 1))                   // ক + virama + ষ is one word
    }

    @Test fun dandaEndsAWord() {
        val s = "লিখি।"
        assertEquals(0 until 4, wordBounds(s, 1))
    }

    @Test fun docSelNormalisesOrder() {
        val s = DocSel(Anchor(0, 1, 5), Anchor(0, 0, 9))
        assertEquals(0, s.a.item); assertEquals(1, s.b.item)
        val t = DocSel(Anchor(0, 0, 7), Anchor(0, 0, 3))
        assertEquals(3, t.a.off); assertEquals(7, t.b.off)
    }

    @Test fun hitTestFindsTheLineAndOffsetUnderThePoint() {
        val pg = page()
        val a = hitTest(pg, 0, 100f + 25f, 200f + 5f, false)!!
        assertEquals(0, a.item); assertEquals(2, a.off)
        val b = hitTest(pg, 0, 100f + 0f, 200f + 30f, false)!!        // second line starts at offset 10
        assertEquals(10, b.off)
    }

    @Test fun hitTestRespectsLooseMode() {
        val pg = page()
        assertNull(hitTest(pg, 0, 100f, 700f, false))
        assertNotNull(hitTest(pg, 0, 100f, 700f, true))                // dragging a handle below the text still resolves
    }

    @Test fun longPressSelectsTheWordUnderTheFinger() {
        val pg = page()
        val a = hitTest(pg, 0, 100f + 25f, 205f, false)!!
        val sel = wordSelection(pg, a)!!
        assertEquals(0, sel.a.off); assertEquals(5, sel.b.off)
        assertEquals("Hello", selText(pg, sel))
    }

    @Test fun paragraphSelectionCoversTheWholeParagraph() {
        val pg = page()
        val sel = paragraphSelection(pg, Anchor(0, 0, 3))!!
        assertEquals("Hello world foo bar", selText(pg, sel))
    }

    @Test fun selectionRectanglesFollowTheText() {
        val pg = page()
        val one = selRects(pg, DocSel(Anchor(0, 0, 0), Anchor(0, 0, 5)))
        assertEquals(1, one.size)
        assertEquals(100f, one[0].l, 0.01f); assertEquals(150f, one[0].r, 0.01f)
        assertEquals(200f, one[0].t, 0.01f); assertEquals(224f, one[0].b, 0.01f)
        val two = selRects(pg, DocSel(Anchor(0, 0, 3), Anchor(0, 0, 14)))   // spans the line break
        assertEquals(2, two.size)
        assertEquals(130f, two[0].l, 0.01f); assertEquals(200f, two[0].r, 0.01f)
        assertEquals(100f, two[1].l, 0.01f); assertEquals(140f, two[1].r, 0.01f)
        assertTrue(two[1].t >= two[0].b - 0.01f)
    }

    @Test fun copiedTextMatchesTheSelectedRange() {
        val pg = page()
        assertEquals("lo world fo", selText(pg, DocSel(Anchor(0, 0, 3), Anchor(0, 0, 14))))
    }

    @Test fun selectionAcrossParagraphsJoinsWithNewlines() {
        val pg = Page(595f, 842f, 800f)
        pg.items.add(TextItem(FakeLayout("First para", 400, 24, 40), 100f, 100f, 0, 1, 1))
        pg.items.add(TextItem(FakeLayout("Second para", 400, 24, 40), 100f, 150f, 0, 1, 2))
        assertEquals("para\nSecond", selText(pg, DocSel(Anchor(0, 0, 6), Anchor(0, 1, 6))))
    }

    @Test fun piecesCarryParagraphIdsForHighlights() {
        val pieces = selPieces(page(), DocSel(Anchor(0, 0, 0), Anchor(0, 0, 5)))
        assertEquals(1, pieces.size)
        assertEquals(5, pieces[0].paraId); assertEquals(0, pieces[0].start); assertEquals(5, pieces[0].end); assertEquals("Hello", pieces[0].text)
    }

    @Test fun itemRectsForHighlightsUseTheSameGeometry() {
        val r = itemRects(item(page()), 6, 9)                          // "wor", all on line 0
        assertEquals(1, r.size); assertEquals(160f, r[0].l, 0.01f); assertEquals(190f, r[0].r, 0.01f)
        assertEquals(2, itemRects(item(page()), 6, 11).size)           // "world" wraps: 'd' is on line 1
    }

    @Test fun snapToClusterNeverLeavesTextBounds() {
        assertEquals(0, snapToCluster("", 5)); assertEquals(3, snapToCluster("abc", 3)); assertEquals(1, snapToCluster("abc", 1))
    }

    // ---- page geometry
    @Test fun geometryStacksPagesFitToWidth() {
        val g = Geom(floatArrayOf(600f, 600f, 600f), floatArrayOf(800f, 800f, 800f), 720f, 10f, 12f)
        val s = 700f / 600f
        assertEquals(12f, g.top[0], 0.01f)
        assertEquals(12f + 800f * s + 12f, g.top[1], 0.01f)
        assertEquals(12f + 3 * (800f * s + 12f), g.totalH, 0.01f)
        assertEquals(1, g.firstVisible(g.top[1] + 5f))
        assertEquals(2, g.lastVisible(g.top[2] + 1f))
        assertEquals(0, g.firstVisible(-50f))
        assertEquals(50f, g.toPageX(0, g.fromPageX(0, 50f)), 0.01f)
        assertEquals(70f, g.toPageY(1, g.fromPageY(1, 70f)), 0.01f)
    }

    @Test fun mixedPageSizesAreEachFittedToWidth() {
        val g = Geom(floatArrayOf(600f, 800f), floatArrayOf(800f, 600f), 720f, 10f, 12f)
        assertEquals(700f / 600f, g.sc[0], 0.0001f); assertEquals(700f / 800f, g.sc[1], 0.0001f)
        assertEquals(g.bottom(1) + 12f, g.totalH, 0.01f)
    }
}

class HighlightTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun fiveRequestedColoursExist() {
        assertEquals(listOf("Yellow", "Light green", "Light blue", "Pink", "Orange"), HlColors.names)
        assertEquals(5, HlColors.all.toSet().size)
    }

    @Test fun codecRoundTripsAwkwardText() {
        val h = Hl("id1", "g1", 3, -1, 4, 9, 0xFFFFEB3B.toInt(), "tab\there\nnewline \\ back", listOf(floatArrayOf(1f, 2f, 3f, 4f), floatArrayOf(5.5f, 6f, 7f, 8f)))
        val d = HlCodec.decode(HlCodec.encode(h))!!
        assertEquals(h.text, d.text); assertEquals(h.page, d.page); assertEquals(h.color, d.color)
        assertEquals(2, d.rects.size); assertArrayEquals(floatArrayOf(5.5f, 6f, 7f, 8f), d.rects[1], 0.001f)
    }

    @Test fun bengaliTextRoundTrips() {
        val h = Hl("a", "g", -1, 12, 0, 5, 0xFF81D4FA.toInt(), TestDocx.BN_SENTENCE)
        assertEquals(TestDocx.BN_SENTENCE, HlCodec.decode(HlCodec.encode(h))!!.text)
    }

    @Test fun corruptLinesAreSkippedNotFatal() {
        assertNull(HlCodec.decode("garbage")); assertNull(HlCodec.decode("a\tb\tx\ty\tz\tw\tq\tr\ts"))
    }

    @Test fun documentKeyIsStableAndFilenameSafe() {
        val k = HlCodec.docKey("content://x/y%20z")
        assertEquals(k, HlCodec.docKey("content://x/y%20z"))
        assertNotEquals(k, HlCodec.docKey("content://x/other"))
        assertTrue(k.matches(Regex("[0-9a-f]{24}")))
    }

    @Test fun storePersistsAcrossReopenAndSupportsRecolourAndDelete() {
        val f = File(tmp.root, "ann/doc.tsv")
        val s1 = HighlightStore(f)
        s1.add(listOf(Hl("1", "G", -1, 3, 0, 4, HlColors.all[0], "abcd"), Hl("2", "G", -1, 4, 0, 2, HlColors.all[0], "ef"), Hl("3", "H", -1, 9, 1, 2, HlColors.all[1], "x")))
        val s2 = HighlightStore(f)
        assertEquals(3, s2.items.size)
        s2.recolorGroup("G", HlColors.all[3])
        assertEquals(setOf(HlColors.all[3], HlColors.all[1]), HighlightStore(f).items.map { it.color }.toSet())
        s2.removeGroup("G")
        val s3 = HighlightStore(f)
        assertEquals(1, s3.items.size); assertEquals("H", s3.items[0].group)
    }

    @Test fun storeWithoutFileStillWorksInMemory() {
        val s = HighlightStore(null); s.add(listOf(Hl("1", "G", 0, -1, 0, 1, 0, "a"))); assertEquals(1, s.items.size)
    }

    @Test fun docxHighlightsSurviveRepaginationBecauseTheyUseParagraphOffsets() {
        val h = Hl("1", "G", -1, 5, 6, 11, HlColors.all[0], "world")
        val pgA = Page(595f, 842f, 800f).also { it.items.add(TextItem(FakeLayout("Hello world foo bar", 400, 24, 10), 100f, 200f, 0, 2, 5)) }
        val pgB = Page(595f, 842f, 800f).also { it.items.add(TextItem(FakeLayout("Hello world foo bar", 400, 24, 5), 100f, 300f, 0, 4, 5)) }  // different wrapping
        for (pg in listOf(pgA, pgB)) {
            val rects = itemRects(pg.items[0] as TextItem, h.start, h.end)
            assertTrue(rects.isNotEmpty())
        }
    }
}

class DictionaryTextTest {
    @Test fun normaliseStripsPunctuationButKeepsBengaliMarks() {
        assertEquals("running", DictText.normalize("“Running,”"))
        assertEquals("বাংলা", DictText.normalize("বাংলা।"))
        assertEquals("ক্ষ", DictText.normalize("ক্ষ"))
        assertEquals("", DictText.normalize("...  "))
    }

    @Test fun englishRootFormsAreOfferedAfterTheExactWord() {
        val c = DictText.candidates("Running")
        assertEquals("running", c.first()); assertTrue(c.contains("run"))
        assertTrue(DictText.candidates("studies").contains("study"))
        assertTrue(DictText.candidates("walked").contains("walk"))
        assertTrue(DictText.candidates("stopped").contains("stop"))
        assertTrue(DictText.candidates("boxes").contains("box"))
        assertTrue(DictText.candidates("happily").contains("happi"))
    }

    @Test fun bengaliSuffixesAreStrippedForRootLookup() {
        assertTrue(DictText.candidates("বইগুলো").contains("বই"))
        assertTrue(DictText.candidates("ছেলেদের").contains("ছেলে"))
        assertEquals("বই", DictText.candidates("বই").first())
    }

    @Test fun nothingToLookUpGivesNoCandidates() = assertTrue(DictText.candidates("  !! ").isEmpty())

    @Test fun meaningsAreSplitIntoAlternatives() {
        assertEquals(listOf("a", "b", "c", "d"), DictText.splitMeanings("a; b|c, d"))
        assertEquals(listOf("x"), DictText.splitMeanings("x;x"))
    }

    @Test fun importLinesAreParsedFromTsvAndCsv() {
        assertEquals("go" to "যাওয়া", DictDb.parseLine("go\tযাওয়া"))
        assertEquals("go" to "যাওয়া; চলা", DictDb.parseLine("go,যাওয়া; চলা"))
        assertEquals("a, b" to "মান", DictDb.parseLine("\"a, b\",\"মান\""))
        assertNull(DictDb.parseLine("novalue")); assertNull(DictDb.parseLine("word\t"))
    }

    @Test fun externalLinksEncodeTheText() {
        assertTrue(External.translateUrl("hello world").contains("tl=bn"))
        assertTrue(External.translateUrl("hello world").contains("sl=auto"))
        assertTrue(External.translateUrl("hello world").endsWith("text=hello+world"))
        assertTrue(External.translateUrl("x".repeat(5000)).length < 1700)
        assertTrue(External.wiktionaryUrl("Running").endsWith("/wiki/running"))
    }
}

class PdfTextLogicTest {
    private fun g(s: String, l: Float, t: Float, ci: Int) = PdfGlyph(s, l, t, l + 10f, t + 12f, ci)
    // "Hi you" on line 1 (text "Hi you"), "ok" on line 2 (after '\n')
    private fun sample() = PdfPageText(
        listOf(g("H", 0f, 0f, 0), g("i", 10f, 0f, 1), g("y", 30f, 0f, 3), g("o", 40f, 0f, 4), g("u", 50f, 0f, 5), g("o", 0f, 20f, 7), g("k", 10f, 20f, 8)),
        "Hi you\nok"
    )

    @Test fun glyphUnderThePointIsFound() {
        val t = sample()
        assertEquals(2, t.glyphAt(32f, 5f, false))
        assertEquals(-1, t.glyphAt(300f, 300f, false))
        assertTrue(t.glyphAt(300f, 300f, true) >= 0)
    }

    @Test fun wordRangeStopsAtSpacesAndLines() {
        val t = sample()
        assertEquals(0..1, t.wordRange(0)); assertEquals(2..4, t.wordRange(3)); assertEquals(5..6, t.wordRange(6))
    }

    @Test fun copiedPdfTextIsExactlyTheSelectedGlyphs() {
        val t = sample()
        assertEquals("you", t.textOf(2, 4)); assertEquals("you", t.textOf(4, 2))
        assertEquals("Hi you", t.textOf(0, 4))
    }

    @Test fun rectanglesMergePerLine() {
        val r = sample().rects(0, 6)
        assertEquals(2, r.size)
        assertEquals(0f, r[0][0], 0.01f); assertEquals(60f, r[0][2], 0.01f)
        assertEquals(20f, r[1][1], 0.01f)
    }
}
