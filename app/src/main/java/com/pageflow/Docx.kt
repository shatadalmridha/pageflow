package com.pageflow

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

// ---------- tiny XML DOM (namespace prefixes stripped) ----------
class Node(val name: String, val a: Map<String, String>) {
    val kids = ArrayList<Node>()
    val text = StringBuilder()
    fun c(n: String): Node? = kids.firstOrNull { it.name == n }
    fun all(n: String): List<Node> = kids.filter { it.name == n }
    fun v(): String? = a["val"]
}

fun parseXml(s: InputStream): Node {
    val f = XmlPullParserFactory.newInstance()
    f.isNamespaceAware = false
    val p = f.newPullParser()
    p.setInput(s, null)
    val stack = ArrayList<Node>()
    var root: Node? = null
    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        when (ev) {
            XmlPullParser.START_TAG -> {
                val m = HashMap<String, String>()
                for (i in 0 until p.attributeCount) m[p.getAttributeName(i).substringAfter(':')] = p.getAttributeValue(i)
                val n = Node(p.name.substringAfter(':'), m)
                if (stack.isEmpty()) root = n else stack[stack.size - 1].kids.add(n)
                stack.add(n)
            }
            XmlPullParser.TEXT -> if (stack.isNotEmpty()) stack[stack.size - 1].text.append(p.text)
            XmlPullParser.END_TAG -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
        }
        ev = p.next()
    }
    return root ?: error("Empty XML")
}

// ---------- document model ----------
class Border(val color: Int?, val szEighth: Int, val style: String) {
    val visible: Boolean get() = style != "none" && style != "nil" && szEighth > 0
}
class Borders(val top: Border? = null, val left: Border? = null, val bottom: Border? = null, val right: Border? = null) {
    fun isEmpty() = top == null && left == null && bottom == null && right == null
    fun any() = listOfNotNull(top, left, bottom, right).any { it.visible }
}

data class Fmt(
    var bold: Boolean = false, var italic: Boolean = false, var underline: Boolean = false,
    var sizeHp: Int = 22, var font: String? = null, var fontCs: String? = null, var color: Int? = null,
    var sizeCsHp: Int? = null, var boldCs: Boolean? = null, var italicCs: Boolean? = null,
    var strike: Boolean = false, var vert: Int = 0, var caps: Boolean = false, var highlight: Int? = null,
    var bgFill: Int? = null, var spacing: Int = 0
)

data class PFmt(
    var jc: String? = null, var before: Int = 0, var after: Int = 0,
    var line: Int = 240, var lineRule: String = "auto",
    var left: Int? = null, var right: Int = 0, var first: Int = 0, var pbb: Boolean = false,
    var numId: Int? = null, var ilvl: Int = 0,
    var keepNext: Boolean = false, var ctx: Boolean = false,
    var shd: Int? = null, var bdr: Borders? = null
)

class RunSpec(val text: String, val fmt: Fmt)

class ImgAnchor(
    val behind: Boolean, val wrap: String,
    val hRel: String, val hAlign: String?, val hOffPt: Float,
    val vRel: String, val vAlign: String?, val vOffPt: Float
)
class ImgSpec(val file: File, val wPt: Float, val hPt: Float, val anchor: ImgAnchor? = null)

sealed class Block
class ParaBlock(
    val runs: List<RunSpec>, val pf: PFmt, val bullet: String?,
    val images: List<ImgSpec>, val sizeHp: Int, val styleId: String? = null, val id: Int = -1
) : Block()
class CellSpec(
    val span: Int, val paras: List<ParaBlock>, val fill: Int?, val vMerge: Int = 0,
    val borders: Borders? = null, val vAlign: String = "top"
)
class RowSpec(val cells: List<CellSpec>, val minHTwips: Int = 0)
class TableBlock(
    val colW: List<Int>, val rows: List<RowSpec>,
    val indentTwips: Int = 0, val padLTwips: Int = 108, val padRTwips: Int = 108, val jc: String? = null
) : Block()
object PageBreak : Block()

class Section(
    val blocks: List<Block>,
    val pageW: Float, val pageH: Float,
    val ml: Float, val mr: Float, val mt: Float, val mb: Float,
    val continuous: Boolean = false
)

class Doc(val sections: List<Section>)

class DocxException(msg: String) : Exception(msg)

private fun on(k: Node): Boolean = k.v().let { it == null || (it != "0" && it != "false") }

private val HIGHLIGHTS = mapOf(
    "yellow" to 0xFFFFFF00, "green" to 0xFF00FF00, "cyan" to 0xFF00FFFF, "magenta" to 0xFFFF00FF,
    "blue" to 0xFF0000FF, "red" to 0xFFFF0000, "darkBlue" to 0xFF000080, "darkCyan" to 0xFF008080,
    "darkGreen" to 0xFF008000, "darkMagenta" to 0xFF800080, "darkRed" to 0xFF800000,
    "darkYellow" to 0xFF808000, "darkGray" to 0xFF808080, "lightGray" to 0xFFC0C0C0,
    "black" to 0xFF000000, "white" to 0xFFFFFFFF
)

fun parseColor(s: String?): Int? {
    if (s == null || s.length != 6) return null
    val c = s.toLongOrNull(16) ?: return null
    return (0xFF000000L or c).toInt()
}

/** Theme colours/fonts from word/theme/theme1.xml, used when a colour or font is given by theme reference. */
class ThemeRes(private val colors: Map<String, Int> = emptyMap(), private val fonts: Map<String, String> = emptyMap()) {
    private val names = mapOf(
        "dark1" to "dk1", "light1" to "lt1", "dark2" to "dk2", "light2" to "lt2",
        "text1" to "dk1", "background1" to "lt1", "text2" to "dk2", "background2" to "lt2",
        "hyperlink" to "hlink", "followedHyperlink" to "folHlink"
    )

    private fun shade(c: Int, f: Float): Int {
        fun ch(v: Int) = (v * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch((c shr 16) and 255) shl 16) or (ch((c shr 8) and 255) shl 8) or ch(c and 255)
    }
    private fun tint(c: Int, f: Float): Int {
        fun ch(v: Int) = (255 - (255 - v) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch((c shr 16) and 255) shl 16) or (ch((c shr 8) and 255) shl 8) or ch(c and 255)
    }

    /** Explicit hex wins (Word pre-computes shade/tint into it); otherwise the theme reference is resolved. */
    fun colorOf(n: Node?, valKey: String = "val", themeKey: String = "themeColor"): Int? {
        if (n == null) return null
        parseColor(n.a[valKey])?.let { return it }
        val t = n.a[themeKey] ?: return null
        var c = colors[names[t] ?: t] ?: return null
        // Word names these attributes themeShade/themeTint for colours but themeFillShade/themeFillTint for fills.
        val shadeKey = if (themeKey == "themeFill") "themeFillShade" else "themeShade"
        val tintKey = if (themeKey == "themeFill") "themeFillTint" else "themeTint"
        n.a[shadeKey]?.toIntOrNull(16)?.let { c = shade(c, it / 255f) }
        n.a[tintKey]?.toIntOrNull(16)?.let { c = tint(c, it / 255f) }
        return c
    }

    fun fillOf(n: Node?): Int? = colorOf(n, "fill", "themeFill")

    fun latinFont(ref: String?): String? = when {
        ref == null -> null
        ref.startsWith("major") -> fonts["majorLatin"]
        ref.startsWith("minor") -> fonts["minorLatin"]
        else -> null
    }
    fun csFont(ref: String?): String? = when {
        ref == null -> null
        ref.startsWith("major") -> fonts["majorBeng"] ?: fonts["majorCs"]
        ref.startsWith("minor") -> fonts["minorBeng"] ?: fonts["minorCs"]
        else -> null
    }
}

fun applyR(n: Node?, f: Fmt, tr: ThemeRes) {
    if (n == null) return
    for (k in n.kids) when (k.name) {
        "b" -> f.bold = on(k)
        "bCs" -> f.boldCs = on(k)
        "i" -> f.italic = on(k)
        "iCs" -> f.italicCs = on(k)
        "u" -> f.underline = k.v().let { it != null && it != "none" }
        "strike", "dstrike" -> f.strike = on(k)
        "caps" -> f.caps = on(k)
        "vertAlign" -> f.vert = when (k.v()) { "superscript" -> 1; "subscript" -> -1; else -> 0 }
        "highlight" -> f.highlight = HIGHLIGHTS[k.v()]?.toInt()
        "shd" -> f.bgFill = tr.fillOf(k)
        "spacing" -> k.v()?.toIntOrNull()?.let { f.spacing = it }
        "sz" -> k.v()?.toIntOrNull()?.let { f.sizeHp = it.coerceIn(2, 1000) }
        "szCs" -> k.v()?.toIntOrNull()?.let { f.sizeCsHp = it.coerceIn(2, 1000) }
        "rFonts" -> {
            (k.a["ascii"] ?: k.a["hAnsi"] ?: tr.latinFont(k.a["asciiTheme"] ?: k.a["hAnsiTheme"]))?.let { f.font = it }
            (k.a["cs"] ?: tr.csFont(k.a["cstheme"]))?.let { f.fontCs = it }
        }
        "color" -> f.color = tr.colorOf(k)
    }
}

fun borderOf(n: Node?, tr: ThemeRes): Border? {
    if (n == null) return null
    val style = n.a["val"] ?: "single"
    return Border(tr.colorOf(n, "color", "themeColor"), n.a["sz"]?.toIntOrNull() ?: 4, style)
}

fun bordersOf(n: Node?, tr: ThemeRes): Borders? =
    if (n == null) null else Borders(
        borderOf(n.c("top"), tr), borderOf(n.c("left") ?: n.c("start"), tr),
        borderOf(n.c("bottom"), tr), borderOf(n.c("right") ?: n.c("end"), tr)
    )

fun applyP(n: Node?, f: PFmt, tr: ThemeRes) {
    if (n == null) return
    for (k in n.kids) when (k.name) {
        "jc" -> f.jc = k.v()
        "spacing" -> {
            k.a["before"]?.toIntOrNull()?.let { f.before = it.coerceAtLeast(0) }
            k.a["after"]?.toIntOrNull()?.let { f.after = it.coerceAtLeast(0) }
            k.a["line"]?.toIntOrNull()?.let { f.line = it.coerceAtLeast(1) }
            k.a["lineRule"]?.let { f.lineRule = it }
        }
        "ind" -> {
            (k.a["left"] ?: k.a["start"])?.toIntOrNull()?.let { f.left = it }
            (k.a["right"] ?: k.a["end"])?.toIntOrNull()?.let { f.right = it.coerceAtLeast(0) }
            k.a["firstLine"]?.toIntOrNull()?.let { f.first = it }
            k.a["hanging"]?.toIntOrNull()?.let { f.first = -it }
        }
        "pageBreakBefore" -> f.pbb = on(k)
        "keepNext" -> f.keepNext = on(k)
        "contextualSpacing" -> f.ctx = on(k)
        "shd" -> f.shd = tr.fillOf(k)
        "pBdr" -> f.bdr = bordersOf(k, tr)
        "numPr" -> {
            k.c("numId")?.v()?.toIntOrNull()?.let { f.numId = it }
            k.c("ilvl")?.v()?.toIntOrNull()?.let { f.ilvl = it }
        }
    }
}

/** Which conditional table-style parts apply to a cell, in Word's order of precedence (later wins). */
class TblLook(val firstRow: Boolean, val lastRow: Boolean, val firstCol: Boolean, val lastCol: Boolean, val noHBand: Boolean, val noVBand: Boolean)

fun tblLookOf(n: Node?): TblLook {
    if (n == null) return TblLook(true, false, true, false, false, true)     // Word default 0x04A0
    fun flag(attr: String, bit: Int): Boolean {
        n.a[attr]?.let { return it == "1" || it == "true" }
        val v = n.a["val"]?.toIntOrNull(16) ?: 0x04A0
        return v and bit != 0
    }
    return TblLook(flag("firstRow", 0x20), flag("lastRow", 0x40), flag("firstColumn", 0x80), flag("lastColumn", 0x100),
        flag("noHBand", 0x200), flag("noVBand", 0x400))
}

fun tableCellTypes(r: Int, c: Int, rows: Int, cols: Int, look: TblLook): List<String> {
    val t = ArrayList<String>()
    t.add("wholeTable")
    val isFirstCol = look.firstCol && c == 0
    val isLastCol = look.lastCol && c == cols - 1
    val isFirstRow = look.firstRow && r == 0
    val isLastRow = look.lastRow && r == rows - 1
    if (!look.noVBand && !isFirstCol && !isLastCol) {
        val idx = c - (if (look.firstCol) 1 else 0)
        t.add(if (idx % 2 == 0) "band1Vert" else "band2Vert")
    }
    if (!look.noHBand && !isFirstRow && !isLastRow) {
        val idx = r - (if (look.firstRow) 1 else 0)
        t.add(if (idx % 2 == 0) "band1Horz" else "band2Horz")
    }
    if (isFirstCol) t.add("firstCol")
    if (isLastCol) t.add("lastCol")
    if (isFirstRow) t.add("firstRow")
    if (isLastRow) t.add("lastRow")
    if (isFirstRow && isFirstCol) t.add("nwCell")
    if (isFirstRow && isLastCol) t.add("neCell")
    if (isLastRow && isFirstCol) t.add("swCell")
    if (isLastRow && isLastCol) t.add("seCell")
    return t
}

private class TBorders(
    var top: Border? = null, var left: Border? = null, var bottom: Border? = null, var right: Border? = null,
    var insideH: Border? = null, var insideV: Border? = null
) {
    fun merge(n: Node?, tr: ThemeRes) {
        if (n == null) return
        borderOf(n.c("top"), tr)?.let { top = it }
        borderOf(n.c("left") ?: n.c("start"), tr)?.let { left = it }
        borderOf(n.c("bottom"), tr)?.let { bottom = it }
        borderOf(n.c("right") ?: n.c("end"), tr)?.let { right = it }
        borderOf(n.c("insideH"), tr)?.let { insideH = it }
        borderOf(n.c("insideV"), tr)?.let { insideV = it }
    }
}

private class TblCtx(val pPr: List<Node>, val rPr: List<Node>)

/**
 * Parses a DOCX into blocks grouped by section. Images are extracted to [mediaDir] (not held in RAM).
 * The source file is opened read-only.
 */
class DocxParser(file: File, private val mediaDir: File) {
    private val zip: ZipFile = try {
        ZipFile(file)
    } catch (e: Exception) {
        throw DocxException("This doesn't look like a .docx file (old .doc files and password-protected files are not supported).")
    }

    private class StyleDef(val basedOn: String?, val pPr: Node?, val rPr: Node?, val node: Node, val type: String)
    private class Lvl(val fmt: String, val text: String, val left: Int?, val first: Int?)

    private val styles = HashMap<String, StyleDef>()
    private var defR = Fmt()
    private var defP = PFmt()
    private var normalId: String? = null
    private var defaultTableStyle: String? = null
    private var tr = ThemeRes()
    private val rels = HashMap<String, String>()
    private val abstracts = HashMap<Int, HashMap<Int, Lvl>>()
    private val nums = HashMap<Int, Int>()
    private val counters = HashMap<Int, IntArray>()
    private val imgFiles = HashMap<String, File>()
    private var paraCounter = 0

    private fun readXml(name: String): Node? {
        val e = zip.getEntry(name) ?: return null
        if (e.size > 120_000_000L) throw DocxException("Document XML is too large to open safely on this device.")
        return zip.getInputStream(e).use { s -> parseXml(s) }
    }

    fun parse(): Doc {
        try {
            mediaDir.mkdirs()
            loadTheme(); loadStyles(); loadNumbering(); loadRels()
            val root = readXml("word/document.xml") ?: throw DocxException("Not a valid DOCX (word/document.xml missing).")
            val body = root.c("body") ?: throw DocxException("The document has no body.")
            val sections = ArrayList<Section>()
            var cur = ArrayList<Block>()
            fun close(sp: Node?) { sections.add(sectionOf(cur, sp)); cur = ArrayList() }
            fun walk(n: Node) {
                for (k in n.kids) when (k.name) {
                    "p" -> {
                        parseP(k, cur, null)
                        k.c("pPr")?.c("sectPr")?.let { close(it) }
                    }
                    "tbl" -> parseTbl(k, cur)
                    "sdt" -> k.c("sdtContent")?.let { walk(it) }
                }
            }
            walk(body)
            if (cur.isNotEmpty() || sections.isEmpty()) close(body.c("sectPr"))
            return Doc(sections)
        } finally {
            try { zip.close() } catch (_: Throwable) {}
        }
    }

    private fun sectionOf(blocks: List<Block>, sp: Node?): Section {
        val sz = sp?.c("pgSz")
        val mar = sp?.c("pgMar")
        fun tw(n: Node?, k: String, d: Int) = n?.a?.get(k)?.toIntOrNull() ?: d
        var w = tw(sz, "w", 11906) / 20f
        var h = tw(sz, "h", 16838) / 20f
        if (w < 100f || h < 100f) { w = 595.3f; h = 841.9f }
        var ml = tw(mar, "left", 1440).coerceAtLeast(200) / 20f
        var mr = tw(mar, "right", 1440).coerceAtLeast(200) / 20f
        var mt = tw(mar, "top", 1440).coerceAtLeast(200) / 20f
        var mb = tw(mar, "bottom", 1440).coerceAtLeast(200) / 20f
        if (ml + mr > w * 0.8f) { ml = 72f; mr = 72f }
        if (mt + mb > h * 0.8f) { mt = 72f; mb = 72f }
        return Section(blocks, w, h, ml, mr, mt, mb, sp?.c("type")?.v() == "continuous")
    }

    private fun loadRels() {
        val r = readXml("word/_rels/document.xml.rels") ?: return
        for (k in r.all("Relationship")) {
            val id = k.a["Id"] ?: continue
            val t = k.a["Target"] ?: continue
            rels[id] = t
        }
    }

    private fun loadTheme() {
        val root = try { readXml("word/theme/theme1.xml") } catch (_: Throwable) { null } ?: return
        val te = root.c("themeElements") ?: return
        val colors = HashMap<String, Int>()
        te.c("clrScheme")?.kids?.forEach { k ->
            val c = k.kids.firstOrNull() ?: return@forEach
            val hex = if (c.name == "srgbClr") c.a["val"] else c.a["lastClr"]
            parseColor(hex)?.let { colors[k.name] = it }
        }
        val fonts = HashMap<String, String>()
        te.c("fontScheme")?.let { fs ->
            for (which in listOf("major", "minor")) {
                val f = fs.c(which + "Font") ?: continue
                f.c("latin")?.a?.get("typeface")?.takeIf { it.isNotEmpty() }?.let { fonts[which + "Latin"] = it }
                f.c("cs")?.a?.get("typeface")?.takeIf { it.isNotEmpty() }?.let { fonts[which + "Cs"] = it }
                f.all("font").firstOrNull { it.a["script"] == "Beng" }?.a?.get("typeface")?.let { fonts[which + "Beng"] = it }
            }
        }
        tr = ThemeRes(colors, fonts)
    }

    private fun loadStyles() {
        val root = readXml("word/styles.xml") ?: return
        root.c("docDefaults")?.let { dd ->
            applyR(dd.c("rPrDefault")?.c("rPr"), defR, tr)
            applyP(dd.c("pPrDefault")?.c("pPr"), defP, tr)
        }
        for (s in root.all("style")) {
            val id = s.a["styleId"] ?: continue
            val type = s.a["type"] ?: "paragraph"
            styles[id] = StyleDef(s.c("basedOn")?.v(), s.c("pPr"), s.c("rPr"), s, type)
            if (type == "paragraph" && s.a["default"] == "1") normalId = id
            if (type == "table" && s.a["default"] == "1") defaultTableStyle = id
        }
    }

    private fun chain(id: String?): List<StyleDef> {
        val out = ArrayList<StyleDef>()
        var cur = id
        var guard = 0
        while (cur != null && guard++ < 12) {
            val s = styles[cur] ?: break
            out.add(0, s)
            cur = s.basedOn
        }
        return out
    }

    private fun loadNumbering() {
        val root = readXml("word/numbering.xml") ?: return
        for (a in root.all("abstractNum")) {
            val id = a.a["abstractNumId"]?.toIntOrNull() ?: continue
            val m = HashMap<Int, Lvl>()
            for (l in a.all("lvl")) {
                val il = l.a["ilvl"]?.toIntOrNull() ?: continue
                val ind = l.c("pPr")?.c("ind")
                val left = (ind?.a?.get("left") ?: ind?.a?.get("start"))?.toIntOrNull()
                val first = ind?.a?.get("firstLine")?.toIntOrNull()
                    ?: ind?.a?.get("hanging")?.toIntOrNull()?.let { -it }
                m[il] = Lvl(l.c("numFmt")?.v() ?: "decimal", l.c("lvlText")?.v() ?: "", left, first)
            }
            abstracts[id] = m
        }
        for (n in root.all("num")) {
            val id = n.a["numId"]?.toIntOrNull() ?: continue
            val aid = n.c("abstractNumId")?.v()?.toIntOrNull() ?: continue
            nums[id] = aid
        }
    }

    private fun roman(n0: Int): String {
        var n = n0.coerceIn(1, 3999)
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        val sb = StringBuilder()
        for (i in v.indices) while (n >= v[i]) { sb.append(s[i]); n -= v[i] }
        return sb.toString()
    }

    private fun fmtNum(n: Int, f: String): String = when (f) {
        "lowerLetter" -> ('a' + ((n - 1).coerceAtLeast(0) % 26)).toString()
        "upperLetter" -> ('A' + ((n - 1).coerceAtLeast(0) % 26)).toString()
        "lowerRoman" -> roman(n).lowercase()
        "upperRoman" -> roman(n)
        else -> n.toString()
    }

    private fun marker(numId: Int, il: Int): Pair<String, Lvl>? {
        val aid = nums[numId] ?: return null
        val lvls = abstracts[aid] ?: return null
        val lv = lvls[il] ?: return null
        if (lv.fmt == "bullet") return Pair("\u2022", lv)
        val c = counters.getOrPut(numId) { IntArray(9) }
        c[il]++
        for (i in il + 1..8) c[i] = 0
        var t = lv.text
        for (k in 0..8) {
            val tok = "%" + (k + 1)
            if (t.contains(tok)) t = t.replace(tok, fmtNum(c[k].coerceAtLeast(1), lvls[k]?.fmt ?: "decimal"))
        }
        if (t.isEmpty()) t = fmtNum(c[il], lv.fmt) + "."
        return Pair(t, lv)
    }

    private fun find(n: Node, name: String): Node? {
        if (n.name == name) return n
        for (k in n.kids) find(k, name)?.let { return it }
        return null
    }

    private fun mediaFile(entryName: String): File? {
        imgFiles[entryName]?.let { return it }
        val e = zip.getEntry(entryName) ?: return null
        if (e.size > 30_000_000L) return null
        val safe = ("img" + imgFiles.size + "_" + entryName.substringAfterLast('/')).replace(Regex("[^A-Za-z0-9._-]"), "_")
        val f = File(mediaDir, safe)
        zip.getInputStream(e).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        imgFiles[entryName] = f
        return f
    }

    private fun anchorOf(n: Node): ImgAnchor? {
        val a = find(n, "anchor") ?: return null
        fun pos(name: String): Triple<String, String?, Float> {
            val p = a.c(name)
            val rel = p?.a?.get("relativeFrom") ?: "page"
            val align = p?.c("align")?.text?.toString()?.trim()
            val off = p?.c("posOffset")?.text?.toString()?.trim()?.toFloatOrNull()?.div(12700f) ?: 0f
            return Triple(rel, align, off)
        }
        val h = pos("positionH"); val v = pos("positionV")
        val wrap = when {
            a.c("wrapNone") != null -> "none"
            a.c("wrapTopAndBottom") != null -> "topBottom"
            a.c("wrapSquare") != null || a.c("wrapTight") != null || a.c("wrapThrough") != null -> "square"
            else -> "none"
        }
        return ImgAnchor(a.a["behindDoc"] == "1" || a.a["behindDoc"] == "true", wrap, h.first, h.second, h.third, v.first, v.second, v.third)
    }

    private fun image(n: Node): ImgSpec? {
        try {
            val blip = find(n, "blip")
            val rid = blip?.a?.get("embed") ?: find(n, "imagedata")?.a?.get("id") ?: return null
            var t = rels[rid] ?: return null
            t = if (t.startsWith("/")) t.substring(1) else "word/" + t.removePrefix("../")
            val f = mediaFile(t) ?: return null
            val ext = find(n, "extent")
            val cx = ext?.a?.get("cx")?.toFloatOrNull()
            val cy = ext?.a?.get("cy")?.toFloatOrNull()
            val w = if (cx != null && cx > 0) cx / 12700f else 300f
            val h = if (cy != null && cy > 0) cy / 12700f else 200f
            return ImgSpec(f, w, h, anchorOf(n))
        } catch (_: Throwable) {
            return null
        }
    }

    private fun parseP(p: Node, out: MutableList<Block>, ctx: TblCtx?) {
        val pPr = p.c("pPr")
        val pf = defP.copy()
        val rf = defR.copy()
        // precedence: document defaults < table style < paragraph style < direct formatting
        if (ctx != null) { for (x in ctx.pPr) applyP(x, pf, tr); for (x in ctx.rPr) applyR(x, rf, tr) }
        val sid = pPr?.c("pStyle")?.v() ?: normalId
        for (st in chain(sid)) { applyP(st.pPr, pf, tr); applyR(st.rPr, rf, tr) }
        applyP(pPr, pf, tr)
        val markF = rf.copy()
        applyR(pPr?.c("rPr"), markF, tr)

        var bullet: String? = null
        val nid = pf.numId
        if (nid != null && nid > 0) {
            val m = marker(nid, pf.ilvl.coerceIn(0, 8))
            if (m != null) {
                bullet = m.first
                if (pf.left == null) pf.left = m.second.left
                if (pf.first == 0 && m.second.first != null) pf.first = m.second.first!!
            }
        }

        var runs = ArrayList<RunSpec>()
        var imgs = ArrayList<ImgSpec>()
        var broke = false
        var b = bullet

        fun flush(isLast: Boolean) {
            if (runs.isNotEmpty() || imgs.isNotEmpty() || (isLast && !broke)) {
                out.add(ParaBlock(runs, pf, b, imgs, markF.sizeHp, sid, paraCounter++))
                b = null
            }
            runs = ArrayList()
            imgs = ArrayList()
        }

        fun addText(t: String, f: Fmt) {
            if (t.isEmpty()) return
            val l = runs.lastOrNull()
            // Merge adjacent identical runs: Word splits runs mid-word (proofing/rsid) and splitting a
            // Bengali cluster across shaping runs would break conjuncts and vowel signs.
            if (l != null && l.fmt == f) runs[runs.size - 1] = RunSpec(l.text + t, f) else runs.add(RunSpec(t, f))
        }

        fun doRun(r: Node) {
            val f = rf.copy()
            r.c("rPr")?.let { rp ->
                rp.c("rStyle")?.v()?.let { s2 -> for (st in chain(s2)) applyR(st.rPr, f, tr) }
                applyR(rp, f, tr)
            }
            for (k in r.kids) when (k.name) {
                "t" -> addText(k.text.toString(), f)
                "tab" -> addText("\t", f)
                "noBreakHyphen" -> addText("-", f)
                "cr" -> addText("\n", f)
                "br" -> if (k.a["type"] == "page") { flush(false); broke = true; out.add(PageBreak) } else addText("\n", f)
                "drawing", "pict" -> image(k)?.let { imgs.add(it) }
            }
        }

        fun walk(n: Node) {
            for (k in n.kids) when (k.name) {
                "r" -> doRun(k)
                "hyperlink", "ins", "smartTag", "sdt", "sdtContent", "fldSimple", "customXml" -> walk(k)
            }
        }

        walk(p)
        flush(true)
    }

    private fun collectParas(n: Node, out: MutableList<ParaBlock>, ctx: TblCtx?) {
        for (k in n.kids) when (k.name) {
            "p" -> { val tmp = ArrayList<Block>(); parseP(k, tmp, ctx); for (b in tmp) if (b is ParaBlock) out.add(b) }
            "tbl", "tr", "tc", "sdt", "sdtContent" -> collectParas(k, out, ctx)
        }
    }

    private fun parseTbl(t: Node, out: MutableList<Block>) {
        var grid = t.c("tblGrid")?.all("gridCol")?.map { it.a["w"]?.toIntOrNull() ?: 0 } ?: emptyList()
        val tp = t.c("tblPr")
        val styleId = tp?.c("tblStyle")?.v() ?: defaultTableStyle
        val styleChain = chain(styleId)
        val look = tblLookOf(tp?.c("tblLook"))

        // table-level borders / cell margins: style chain first, direct formatting last
        val tb = TBorders()
        var padL = 108; var padR = 108
        fun mar(n: Node?) {
            val m = n?.c("tblCellMar") ?: return
            (m.c("left") ?: m.c("start"))?.a?.get("w")?.toIntOrNull()?.let { padL = it }
            (m.c("right") ?: m.c("end"))?.a?.get("w")?.toIntOrNull()?.let { padR = it }
        }
        for (st in styleChain) { tb.merge(st.node.c("tblPr")?.c("tblBorders"), tr); mar(st.node.c("tblPr")) }
        tb.merge(tp?.c("tblBorders"), tr); mar(tp)
        val indent = tp?.c("tblInd")?.a?.get("w")?.toIntOrNull() ?: 0
        val jc = tp?.c("jc")?.v()

        val trs = t.all("tr")
        val nRows = trs.size
        val rows = ArrayList<RowSpec>()
        var maxCols = 0
        var widest: List<Int> = emptyList()
        val starts = ArrayList<List<Int>>()           // grid start column of each cell, per row (for vertical merges)
        val cellsPerRow = ArrayList<MutableList<CellSpec>>()

        for ((r, tr0) in trs.withIndex()) {
            val tcs = tr0.all("tc")
            val cells = ArrayList<CellSpec>()
            val widths = ArrayList<Int>()
            val st = ArrayList<Int>()
            var gc = 0
            for ((c, tc) in tcs.withIndex()) {
                val tcp = tc.c("tcPr")
                val span = (tcp?.c("gridSpan")?.v()?.toIntOrNull() ?: 1).coerceAtLeast(1)
                val vm = tcp?.c("vMerge")?.let { if (it.v() == "restart") 1 else 2 } ?: 0

                // gather every applicable style layer for this cell, in precedence order
                val types = tableCellTypes(r, c, nRows, tcs.size, look)
                val layers = ArrayList<Node>()
                for (s in styleChain) {
                    layers.add(s.node)
                    for (t2 in types.drop(1)) s.node.all("tblStylePr").filter { it.a["type"] == t2 }.forEach { layers.add(it) }
                }
                var fill: Int? = null
                val cb = TBorders()
                val pPrs = ArrayList<Node>(); val rPrs = ArrayList<Node>()
                for (ly in layers) {
                    ly.c("tcPr")?.c("shd")?.let { s -> fill = tr.fillOf(s) }
                    cb.merge(ly.c("tcPr")?.c("tcBorders"), tr)
                    ly.c("pPr")?.let { pPrs.add(it) }; ly.c("rPr")?.let { rPrs.add(it) }
                }
                tcp?.c("shd")?.let { s -> fill = tr.fillOf(s) }
                cb.merge(tcp?.c("tcBorders"), tr)

                val firstRow = r == 0; val lastRow = r == nRows - 1
                val firstCol = gc == 0
                val lastCol = c == tcs.size - 1
                // outer edges use the table's outer borders, inner edges use insideH / insideV
                fun pick(cell: Border?, outer: Border?, inner: Border?, isOuter: Boolean) = cell ?: (if (isOuter) outer else inner)
                val borders = Borders(
                    pick(cb.top, tb.top, tb.insideH, firstRow), pick(cb.left, tb.left, tb.insideV, firstCol),
                    pick(cb.bottom, tb.bottom, tb.insideH, lastRow), pick(cb.right, tb.right, tb.insideV, lastCol)
                )
                val paras = ArrayList<ParaBlock>()
                collectParas(tc, paras, TblCtx(pPrs, rPrs))
                val va = tcp?.c("vAlign")?.v() ?: "top"
                cells.add(CellSpec(span, paras, fill, vm, borders, va))
                widths.add(tcp?.c("tcW")?.a?.get("w")?.toIntOrNull() ?: 0)
                st.add(gc); gc += span
            }
            if (gc > maxCols) { maxCols = gc; widest = widths }
            rows.add(RowSpec(cells, tr0.c("trPr")?.c("trHeight")?.v()?.toIntOrNull() ?: 0))
            starts.add(st); cellsPerRow.add(cells)
        }

        // vertical merges: hide the border between a merged cell and the one below it
        val fixed = ArrayList<RowSpec>()
        for ((r, row) in rows.withIndex()) {
            val newCells = ArrayList<CellSpec>()
            for ((i, c) in row.cells.withIndex()) {
                var b = c.borders
                if (b != null) {
                    val below = if (r + 1 < rows.size) rows[r + 1].cells.getOrNull(starts[r + 1].indexOf(starts[r][i])) else null
                    val dropBottom = (c.vMerge != 0) && below?.vMerge == 2
                    val dropTop = c.vMerge == 2
                    if (dropBottom || dropTop) b = Borders(if (dropTop) null else b.top, b.left, if (dropBottom) null else b.bottom, b.right)
                }
                newCells.add(if (b === c.borders) c else CellSpec(c.span, c.paras, c.fill, c.vMerge, b, c.vAlign))
            }
            fixed.add(RowSpec(newCells, row.minHTwips))
        }
        if (grid.isEmpty()) grid = if (widest.size == (maxCols) && widest.all { it > 0 }) widest else List(maxCols.coerceAtLeast(1)) { 0 }
        out.add(TableBlock(grid, fixed, indent, padL, padR, jc))
    }
}
