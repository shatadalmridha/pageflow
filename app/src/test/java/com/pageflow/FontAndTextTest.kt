package com.pageflow

import org.junit.Assert.*
import org.junit.Test

class FontAndTextTest {
    private val files = listOf(
        "SolaimanLipi.ttf", "NotoSerifBengali-Regular.ttf", "NotoSerifBengali-Bold.ttf",
        "TiroBangla-Regular.ttf", "TiroBangla-Italic.ttf", "ArialNarrow.ttf", "Arial.ttf"
    )

    // ---- file-name parsing / matching
    @Test fun parseStripsStyleSuffixes() {
        assertEquals("notoserifbengali", FontMatch.parse("NotoSerifBengali-Regular.ttf").family)
        val b = FontMatch.parse("NotoSerifBengali-Bold.ttf")
        assertEquals("notoserifbengali", b.family); assertTrue(b.bold); assertFalse(b.italic)
        val i = FontMatch.parse("TiroBangla-Italic.otf")
        assertTrue(i.italic)
        assertEquals("notoserifbengali", FontMatch.parse("NotoSerifBengali[wdth,wght].ttf").family)
    }

    @Test fun exactFamilyNeverMatchesLongerFamily() {
        // "Arial" must not pick "ArialNarrow" and vice versa
        assertEquals("Arial.ttf", FontMatch.pickFamily(files, "Arial", false, false)!!.file)
        assertEquals("ArialNarrow.ttf", FontMatch.pickFamily(files, "Arial Narrow", false, false)!!.file)
        assertNull(FontMatch.pickFamily(listOf("ArialNarrow.ttf"), "Arial", false, false))
    }

    @Test fun docFontNameWithSpacesMatchesFileName() {
        val p = FontMatch.pickFamily(files, "Noto Serif Bengali", false, false)
        assertEquals("NotoSerifBengali-Regular.ttf", p!!.file)
        assertFalse(p.synthBold)
    }

    @Test fun boldPrefersRealBoldFileAndAvoidsSynthesis() {
        val p = FontMatch.pickFamily(files, "Noto Serif Bengali", true, false)!!
        assertEquals("NotoSerifBengali-Bold.ttf", p.file)
        assertFalse(p.synthBold)
    }

    @Test fun missingStyleIsSynthesised() {
        val p = FontMatch.pickFamily(files, "SolaimanLipi", true, true)!!
        assertEquals("SolaimanLipi.ttf", p.file)
        assertTrue(p.synthBold); assertTrue(p.synthItalic)
    }

    @Test fun tooShortNamesDoNotMatch() {
        assertNull(FontMatch.pickFamily(listOf("Ab.ttf"), "Ab", false, false))
    }

    // ---- substitution decisions (Bengali & English)
    @Test fun bengaliTextWithMissingDocFontUsesReadingFont() {
        val c = FontMatch.decide(files, "Kalpurush", false, false, bengali = true, overrideFonts = false, reading = "TiroBangla-Regular.ttf")
        assertEquals("TiroBangla-Regular.ttf", (c as FontChoice.Custom).file)
    }

    @Test fun englishTextKeepsDocumentFontWhenOverrideOff() {
        val c = FontMatch.decide(files, "Times New Roman", false, false, bengali = false, overrideFonts = false, reading = "TiroBangla-Regular.ttf")
        assertTrue(c is FontChoice.System)
        assertEquals("serif", (c as FontChoice.System).family)
    }

    @Test fun installedDocFontBeatsReadingFontWhenOverrideOff() {
        val c = FontMatch.decide(files, "SolaimanLipi", false, false, true, false, "TiroBangla-Regular.ttf")
        assertEquals("SolaimanLipi.ttf", (c as FontChoice.Custom).file)
    }

    @Test fun overrideReplacesEverythingEvenEnglish() {
        val c = FontMatch.decide(files, "SolaimanLipi", false, false, false, true, "TiroBangla-Regular.ttf")
        assertEquals("TiroBangla-Regular.ttf", (c as FontChoice.Custom).file)
    }

    @Test fun overrideKeepsBoldAndItalicViaSiblingFiles() {
        val c = FontMatch.decide(files, null, true, false, true, true, "NotoSerifBengali-Regular.ttf") as FontChoice.Custom
        assertEquals("NotoSerifBengali-Bold.ttf", c.file)
        assertFalse(c.synthBold)
    }

    @Test fun deletedReadingFontFallsBackToSystem() {
        val c = FontMatch.decide(files, null, false, false, true, true, "Gone.ttf")
        assertTrue(c is FontChoice.System)
    }

    @Test fun noFontsAtAllIsSafe() {
        val c = FontMatch.decide(emptyList(), "Calibri", true, false, true, false, null)
        assertTrue(c is FontChoice.System)
        assertTrue((c as FontChoice.System).bold)
    }

    @Test fun monospaceAndSansClassification() {
        assertEquals("mono", (FontMatch.decide(emptyList(), "Courier New", false, false, false, false, null) as FontChoice.System).family)
        assertEquals("sans", (FontMatch.decide(emptyList(), "Arial", false, false, false, false, null) as FontChoice.System).family)
        assertEquals("sans", (FontMatch.decide(emptyList(), "Noto Sans Serif Display", false, false, false, false, null) as FontChoice.System).family)
    }

    @Test fun fontFileHeaderValidation() {
        assertTrue(FontMatch.isSfnt(byteArrayOf(0, 1, 0, 0)))
        assertTrue(FontMatch.isSfnt("OTTO".toByteArray()))
        assertTrue(FontMatch.isSfnt("ttcf".toByteArray()))
        assertFalse(FontMatch.isSfnt("%PDF".toByteArray()))
        assertFalse(FontMatch.isSfnt(byteArrayOf(1)))
    }

    // ---- script segmentation
    @Test fun segmentsSplitScriptsAndKeepClustersIntact() {
        val s = "Hello ${TestDocx.BN_WORD} world"
        val seg = segments(s)
        assertEquals(s, seg.joinToString("") { it.first })
        assertTrue(seg.any { it.second && it.first.contains(TestDocx.BN_WORD) })
        assertTrue(seg.any { !it.second && it.first.contains("Hello") })
        // the conjunct is never split
        val c = segments("x${TestDocx.BN_CONJUNCT}y").first { it.second }.first
        assertTrue(c.contains(TestDocx.BN_CONJUNCT))
    }

    @Test fun zwjAndZwnjStayInsideBengaliSegment() {
        val s = "র্\u200Dয" + " ক\u200Cখ"
        val seg = segments(s)
        assertEquals(1, seg.size)
        assertTrue(seg[0].second)
        assertEquals(s, seg[0].first)
    }

    @Test fun digitsAndPunctuationFollowSurroundingScript() {
        val seg = segments("পৃষ্ঠা 12, ok")
        assertEquals("পৃষ্ঠা 12, ", seg[0].first)
        assertTrue(seg[0].second)
    }

    @Test fun emptyTextHasNoSegments() = assertTrue(segments("").isEmpty())
}
