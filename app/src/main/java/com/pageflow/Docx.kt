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
data class Fmt(
    var bold: Boolean = false, var italic: Boolean = false, var underline: Boolean = false,
    var sizeHp: Int = 22, var font: String? = null, var fontCs: String? = null, var color: Int? = null,
    // complex-script (Bengali) variants: Word stores a separate size/bold/italic for them
    var sizeCsHp: Int? = null, var boldCs: Boolean? = null, var italicCs: Boolean? = null,
    var strike: Boolean = false, var vert: Int = 0, var caps: Boolean = false, var highlight: Int? = null
)

data class PFmt(
    var jc: String? = null, var before: Int = 0, var after: Int = 0,
    var line: Int = 240, var lineRule: String = "auto",
    var left: Int? = null, var first: Int = 0, var pbb: Boolean = false,
    var numId: Int? = null, var ilvl: Int = 0,
    var keepNext: Boolean = false, var ctx: Boolean = false
)

class RunSpec(val text: String, val fmt: Fmt)
class ImgSpec(val file: File, val wPt: Float, val hPt: Float)

sealed class Block
class ParaBlock(
    val runs: List<RunSpec>, val pf: PFmt, val bullet: String?,
    val images: List<ImgSpec>, val sizeHp: Int, val styleId: String? = null
) : Block()
class CellSpec(val span: Int, val paras: List<ParaBlock>, val fill: Int?, val vMerge: Int = 0)
class RowSpec(val cells: List<CellSpec>, val minHTwips: Int = 0)
class TableBlock(
    val colW: List<Int>, val rows: List<RowSpec>, val borders: Boolean,
    val indentTwips: Int = 0, val padLTwips: Int = 108, val padRTwips: Int = 108
) : Block()
object PageBreak : Block()

class Section(
    val blocks: List<Block>,
    val pageW: Float, val pageH: Float,
    val ml: Float, val mr: Float, val mt: Float, val mb: Float,
    val continuous: Boolean = false
)

class Doc(val sections: List<Section>)

private fun on(k: Node): Boolean = k.v().let { it == null || (it != "0" && it != "false") }

private val HIGHLIGHTS = mapOf(
    "yellow" to 0xFFFFFF00, "green" to 0xFF00FF00, "cyan" to 0xFF00FFFF, "magenta" to 0xFFFF00FF,
    "blue" to 0xFF0000FF, "red" to 0xFFFF0000, "darkBlue" to 0xFF000080, "darkCyan" to 0xFF008080,
    "darkGreen" to 0xFF008000, "darkMagenta" to 0xFF800080, "darkRed" to 0xFF800000,
    "darkYellow" to 0xFF808000, "darkGray" to 0xFF808080, "lightGray" to 0xFFC0C0C0,
    "black" to 0xFF000000, "white" to 0xFFFFFFFF
)

fun applyR(n: Node?, f: Fmt) {
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
        "sz" -> k.v()?.toIntOrNull()?.let { f.sizeHp = it.coerceIn(2, 1000) }
        "szCs" -> k.v()?.toIntOrNull()?.let { f.sizeCsHp = it.coerceIn(2, 1000) }
        "rFonts" -> {
            (k.a["ascii"] ?: k.a["hAnsi"])?.let { f.font = it }
            k.a["cs"]?.let { f.fontCs = it }
        }
        "color" -> k.v()?.let { f.color = parseColor(it) }
    }
}

fun parseColor(s: String?): Int? {
    if (s == null || s.length != 6) return null
    val c = s.toLongOrNull(16) ?: return null
    return (0xFF000000L or c).toInt()
}

fun applyP(n: Node?, f: PFmt) {
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
            k.a["firstLine"]?.toIntOrNull()?.let { f.first = it }
            k.a["hanging"]?.toIntOrNull()?.let { f.first = -it }
        }
        "pageBreakBefore" -> f.pbb = on(k)
        "keepNext" -> f.keepNext = on(k)
        "contextualSpacing" -> f.ctx = on(k)
        "numPr" -> {
            k.c("numId")?.v()?.toIntOrNull()?.let { f.numId = it }
            k.c("ilvl")?.v()?.toIntOrNull()?.let { f.ilvl = it }
        }
    }
}

class DocxException(msg: String) : Exception(msg)

/**
 * Parses a DOCX into blocks grouped by section. Images are extracted to [mediaDir] (not held in RAM).
 * The source file is opened read-only.
 */
class DocxParser(private val file: File, private val mediaDir: File) {
    private val zip: ZipFile = try {
        ZipFile(file)
    } catch (e: Exception) {
        throw DocxException("This doesn't look like a .docx file (old .doc files and password-protected files are not supported).")
    }

    private class StyleDef(val basedOn: String?, val pPr: Node?, val rPr: Node?)
    private class Lvl(val fmt: String, val text: String, val left: Int?, val first: Int?)

    private val styles = HashMap<String, StyleDef>()
    private var defR = Fmt()
    private var defP = PFmt()
    private var normalId: String? = null
    private val rels = HashMap<String, String>()
    private val abstracts = HashMap<Int, HashMap<Int, Lvl>>()
    private val nums = HashMap<Int, Int>()
    private val counters = HashMap<Int, IntArray>()
    private val imgFiles = HashMap<String, File>()

    private fun readXml(name: String): Node? {
        val e = zip.getEntry(name) ?: return null
        if (e.size > 120_000_000L) throw DocxException("Document XML is too large to open safely on this device.")
        return zip.getInputStream(e).use { s -> parseXml(s) }
    }

    fun parse(): Doc {
        try {
            mediaDir.mkdirs()
            loadStyles(); loadNumbering(); loadRels()
            val root = readXml("word/document.xml") ?: throw DocxException("Not a valid DOCX (word/document.xml missing).")
            val body = root.c("body") ?: throw DocxException("The document has no body.")
            val sections = ArrayList<Section>()
            var cur = ArrayList<Block>()
            fun close(sp: Node?) { sections.add(sectionOf(cur, sp)); cur = ArrayList() }
            fun walk(n: Node) {
                for (k in n.kids) when (k.name) {
                    "p" -> {
                        parseP(k, cur)
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

    private fun loadStyles() {
        val root = readXml("word/styles.xml") ?: return
        root.c("docDefaults")?.let { dd ->
            applyR(dd.c("rPrDefault")?.c("rPr"), defR)
            applyP(dd.c("pPrDefault")?.c("pPr"), defP)
        }
        for (s in root.all("style")) {
            val id = s.a["styleId"] ?: continue
            styles[id] = StyleDef(s.c("basedOn")?.v(), s.c("pPr"), s.c("rPr"))
            if (s.a["type"] == "paragraph" && s.a["default"] == "1") normalId = id
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
            return ImgSpec(f, w, h)
        } catch (_: Throwable) {
            return null
        }
    }

    private fun parseP(p: Node, out: MutableList<Block>) {
        val pPr = p.c("pPr")
        val pf = defP.copy()
        val rf = defR.copy()
        val sid = pPr?.c("pStyle")?.v() ?: normalId
        for (st in chain(sid)) { applyP(st.pPr, pf); applyR(st.rPr, rf) }
        applyP(pPr, pf)
        val markF = rf.copy()
        applyR(pPr?.c("rPr"), markF)

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
                out.add(ParaBlock(runs, pf, b, imgs, markF.sizeHp, sid))
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
                rp.c("rStyle")?.v()?.let { s2 -> for (st in chain(s2)) applyR(st.rPr, f) }
                applyR(rp, f)
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

    private fun collectParas(n: Node, out: MutableList<ParaBlock>) {
        for (k in n.kids) when (k.name) {
            "p" -> { val tmp = ArrayList<Block>(); parseP(k, tmp); for (b in tmp) if (b is ParaBlock) out.add(b) }
            "tbl", "tr", "tc", "sdt", "sdtContent" -> collectParas(k, out)
        }
    }

    private fun parseTbl(t: Node, out: MutableList<Block>) {
        var grid = t.c("tblGrid")?.all("gridCol")?.map { it.a["w"]?.toIntOrNull() ?: 0 } ?: emptyList()
        val tp = t.c("tblPr")
        val borders = (tp?.c("tblBorders")?.kids?.any { it.v() != "none" && it.v() != "nil" } == true) ||
            (tp?.c("tblStyle")?.v()?.contains("Grid", true) == true)
        val indent = tp?.c("tblInd")?.a?.get("w")?.toIntOrNull() ?: 0
        val mar = tp?.c("tblCellMar")
        val padL = (mar?.c("left") ?: mar?.c("start"))?.a?.get("w")?.toIntOrNull() ?: 108
        val padR = (mar?.c("right") ?: mar?.c("end"))?.a?.get("w")?.toIntOrNull() ?: 108
        val rows = ArrayList<RowSpec>()
        var maxCols = 0
        var widest: List<Int> = emptyList()
        for (tr in t.all("tr")) {
            val cells = ArrayList<CellSpec>()
            val widths = ArrayList<Int>()
            for (tc in tr.all("tc")) {
                val tcp = tc.c("tcPr")
                val span = (tcp?.c("gridSpan")?.v()?.toIntOrNull() ?: 1).coerceAtLeast(1)
                val fill = parseColor(tcp?.c("shd")?.a?.get("fill"))
                val vm = tcp?.c("vMerge")?.let { if (it.v() == "restart") 1 else 2 } ?: 0
                val paras = ArrayList<ParaBlock>()
                collectParas(tc, paras)
                cells.add(CellSpec(span, paras, fill, vm))
                widths.add(tcp?.c("tcW")?.a?.get("w")?.toIntOrNull() ?: 0)
            }
            val cols = cells.sumOf { it.span }
            if (cols > maxCols) { maxCols = cols; widest = widths }
            rows.add(RowSpec(cells, tr.c("trPr")?.c("trHeight")?.v()?.toIntOrNull() ?: 0))
        }
        if (grid.isEmpty()) grid = if (widest.size == maxCols) widest else List(maxCols.coerceAtLeast(1)) { 0 }
        out.add(TableBlock(grid, rows, borders, indent, padL, padR))
    }
}
