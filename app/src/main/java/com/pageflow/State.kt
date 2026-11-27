package com.pageflow

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

// ------------------------------------------------------------ pure font matching (unit-tested)
sealed class FontChoice {
    class Custom(val file: String, val synthBold: Boolean, val synthItalic: Boolean) : FontChoice()
    class System(val family: String, val bold: Boolean, val italic: Boolean) : FontChoice()
}

object FontMatch {
    private val STYLE_TOKENS = listOf(
        "bolditalic", "boldoblique", "bold", "italic", "oblique", "regular", "book", "normal", "vf"
    )

    fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    class Parsed(val family: String, val bold: Boolean, val italic: Boolean)

    /** "NotoSerifBengali-BoldItalic.ttf" -> family "notoserifbengali", bold, italic. */
    fun parse(fileName: String): Parsed {
        var base = fileName.substringBeforeLast('.')
        base = base.replace(Regex("\\[.*?]"), "")            // variable-font axes: Name[wdth,wght]
        var n = norm(base)
        val bold = n.contains("bold")
        val italic = n.contains("italic") || n.contains("oblique")
        while (true) {
            val t = STYLE_TOKENS.firstOrNull { n.endsWith(it) && n.length > it.length } ?: break
            n = n.removeSuffix(t)
        }
        return Parsed(n, bold, italic)
    }

    class Pick(val file: String, val synthBold: Boolean, val synthItalic: Boolean)

    /** Picks the file of the family whose style best matches; reports what must be synthesised. */
    fun pickFamily(files: List<String>, family: String, bold: Boolean, italic: Boolean): Pick? {
        val fam = norm(family)
        if (fam.length < 3) return null
        val cands = files.map { it to parse(it) }.filter { it.second.family == fam }
        if (cands.isEmpty()) return null
        val best = cands.minByOrNull {
            (if (it.second.bold != bold) 2 else 0) + (if (it.second.italic != italic) 1 else 0)
        }!!
        return Pick(best.first, bold && !best.second.bold, italic && !best.second.italic)
    }

    fun decide(
        files: List<String>, docFont: String?, bold: Boolean, italic: Boolean,
        bengali: Boolean, overrideFonts: Boolean, reading: String?
    ): FontChoice {
        val readingOk = reading != null && reading in files
        fun readingChoice(): FontChoice.Custom {
            val fam = parse(reading!!).family
            val p = pickFamily(files, fam, bold, italic) ?: Pick(reading, bold, italic)
            return FontChoice.Custom(p.file, p.synthBold, p.synthItalic)
        }
        if (overrideFonts && readingOk) return readingChoice()
        if (docFont != null) {
            val p = pickFamily(files, docFont, bold, italic)
            if (p != null) return FontChoice.Custom(p.file, p.synthBold, p.synthItalic)
        }
        if (bengali && readingOk) return readingChoice()
        val l = docFont?.lowercase() ?: ""
        val family = when {
            l.contains("courier") || l.contains("consolas") || l.contains("mono") -> "mono"
            l.contains("times") || l.contains("cambria") || l.contains("georgia") ||
                l.contains("garamond") || l.contains("palatino") || (l.contains("serif") && !l.contains("sans")) -> "serif"
            else -> "sans"
        }
        return FontChoice.System(family, bold, italic)
    }

    /** True for TrueType / OpenType / collection headers. */
    fun isSfnt(h: ByteArray): Boolean {
        if (h.size < 4) return false
        val a = h[0].toInt() and 0xFF; val b = h[1].toInt() and 0xFF
        val c = h[2].toInt() and 0xFF; val d = h[3].toInt() and 0xFF
        if (a == 0 && b == 1 && c == 0 && d == 0) return true
        val tag = String(h, 0, 4, Charsets.ISO_8859_1)
        return tag == "OTTO" || tag == "true" || tag == "ttcf"
    }
}

// ------------------------------------------------------------ Android font resolver
class FontResolver(ctx: Context) {
    val dir = File(ctx.filesDir, "fonts").apply { mkdirs() }
    private val cache = HashMap<String, Typeface>()
    private val resolved = HashMap<String, Typeface>()
    @Volatile private var names: List<String> = scan()
    val list: List<File> get() = names.map { File(dir, it) }

    private fun scan(): List<String> =
        dir.listFiles()?.filter { it.extension.lowercase() in setOf("ttf", "otf") }?.map { it.name }?.sorted() ?: emptyList()

    @Synchronized fun refresh() { cache.clear(); resolved.clear(); names = scan() }

    @Synchronized fun load(name: String): Typeface? {
        cache[name]?.let { return it }
        return try {
            Typeface.createFromFile(File(dir, name)).also { cache[name] = it }
        } catch (e: Throwable) { null }
    }

    @Synchronized
    fun resolve(docFont: String?, bold: Boolean, italic: Boolean, bengali: Boolean, overrideFonts: Boolean, reading: String?): Typeface {
        val key = "$docFont|$bold|$italic|$bengali|$overrideFonts|$reading"
        resolved[key]?.let { return it }
        val tf = when (val c = FontMatch.decide(names, docFont, bold, italic, bengali, overrideFonts, reading)) {
            is FontChoice.Custom -> {
                val base = load(c.file)
                if (base == null) Typeface.DEFAULT
                else Typeface.create(base, (if (c.synthBold) Typeface.BOLD else 0) or (if (c.synthItalic) Typeface.ITALIC else 0))
            }
            is FontChoice.System -> {
                val fam = when (c.family) { "mono" -> Typeface.MONOSPACE; "serif" -> Typeface.SERIF; else -> Typeface.DEFAULT }
                Typeface.create(fam, (if (c.bold) Typeface.BOLD else 0) or (if (c.italic) Typeface.ITALIC else 0))
            }
        }
        resolved[key] = tf
        return tf
    }
}

class Recent(val uri: String, val name: String, val type: String)

class AppState(val ctx: Context) {
    private val sp = ctx.getSharedPreferences("pageflow", Context.MODE_PRIVATE)
    val fonts = FontResolver(ctx)
    private val importedDir = File(ctx.filesDir, "imported").apply { mkdirs() }

    var themeId by mutableStateOf(sp.getString("themeId", "oldlace") ?: "oldlace")
    var custom by mutableStateOf<ReaderTheme?>(loadCustom())
    val theme: ReaderTheme get() = Themes.find(themeId, custom)
    var docContinuous by mutableStateOf(sp.getBoolean("docCont", false))
    var pdfContinuous by mutableStateOf(sp.getBoolean("pdfCont", false))
    val dict = DictDb(ctx)
    var readingFont by mutableStateOf<String?>(sp.getString("font", null))
    var overrideFonts by mutableStateOf(sp.getBoolean("override", false))
    var fontScale by mutableFloatStateOf(sp.getFloat("fs", 1f))
    var lineSpacing by mutableFloatStateOf(sp.getFloat("ls", 1f))
    var fontsVersion by mutableIntStateOf(0)
    val recents = mutableStateListOf<Recent>()

    init {
        (sp.getString("recents", "") ?: "").split("\n").forEach { line ->
            val p = line.split("\u0001")
            if (p.size == 3) recents.add(Recent(p[0], p[1], p[2]))
        }
        // a font that was deleted outside the app must not stay selected
        if (readingFont != null && fonts.list.none { it.name == readingFont }) readingFont = null
    }

    private fun loadCustom(): ReaderTheme? {
        if (!sp.contains("c_page")) return null
        return ReaderTheme(Themes.CUSTOM_ID, "Custom", sp.getInt("c_page", 0), sp.getInt("c_ink", 0), sp.getInt("c_head", 0), sp.getInt("c_foot", 0))
    }

    fun selectTheme(id: String) { themeId = id; sp.edit().putString("themeId", id).apply() }

    fun saveCustom(t: ReaderTheme) {
        custom = ReaderTheme(Themes.CUSTOM_ID, "Custom", t.page, t.ink, t.header, t.footer)
        sp.edit().putInt("c_page", t.page).putInt("c_ink", t.ink).putInt("c_head", t.header).putInt("c_foot", t.footer).apply()
        selectTheme(Themes.CUSTOM_ID)
    }

    fun resetTheme() {
        custom = null
        sp.edit().remove("c_page").remove("c_ink").remove("c_head").remove("c_foot").apply()
        selectTheme("oldlace")
    }

    fun setViewModes(doc: Boolean? = null, pdf: Boolean? = null) {
        doc?.let { docContinuous = it }; pdf?.let { pdfContinuous = it }
        sp.edit().putBoolean("docCont", docContinuous).putBoolean("pdfCont", pdfContinuous).apply()
    }

    /** Highlights live in the app's private folder, keyed by document, never inside the document. */
    fun highlights(uri: String): HighlightStore =
        HighlightStore(File(ctx.filesDir, "annotations/" + HlCodec.docKey(uri) + ".tsv"))

    fun persist() {
        sp.edit().putString("font", readingFont)
            .putBoolean("override", overrideFonts).putFloat("fs", fontScale).putFloat("ls", lineSpacing).apply()
    }

    fun resetFontSettings() {
        readingFont = null; overrideFonts = false; fontScale = 1f; lineSpacing = 1f; persist()
    }

    private fun saveRecents() {
        sp.edit().putString("recents", recents.joinToString("\n") { it.uri + "\u0001" + it.name.replace("\n", " ") + "\u0001" + it.type }).apply()
    }

    fun addRecent(uri: String, name: String, type: String) {
        recents.removeAll { it.uri == uri }
        recents.add(0, Recent(uri, name, type))
        while (recents.size > 30) release(recents.removeAt(recents.size - 1))
        saveRecents()
    }

    fun removeRecent(r: Recent) { recents.remove(r); release(r); saveRecents() }
    fun clearRecents() { recents.toList().forEach { release(it) }; recents.clear(); saveRecents() }

    private fun release(r: Recent) {
        val u = Uri.parse(r.uri)
        try {
            if (u.scheme == "file") {
                val f = File(u.path ?: return)
                if (f.parentFile?.canonicalPath == importedDir.canonicalPath) f.delete()
            } else ctx.contentResolver.releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {}
    }

    /** True if the document can still be opened (permission not revoked, file not deleted). */
    fun accessible(uri: Uri): Boolean = try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: Throwable) { false }

    /**
     * Keeps a document reachable from Recents. Picker URIs get a persistable permission. If the permission
     * can't be persisted (e.g. a file opened from another app) a private copy is kept instead.
     * Call off the main thread.
     */
    fun persistAccess(uri: Uri): Uri {
        if (uri.scheme == "file") return uri
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return uri
        } catch (_: Throwable) {}
        return try {
            val ext = nameOf(ctx, uri).substringAfterLast('.', "bin").lowercase().filter { it.isLetterOrDigit() }.take(5)
            val f = File(importedDir, "${System.currentTimeMillis()}.$ext")
            ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            pruneImported()
            Uri.fromFile(f)
        } catch (_: Throwable) { uri }
    }

    private fun pruneImported() {
        val keep = recents.map { Uri.parse(it.uri).path }.toSet()
        val files = importedDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= 200L * 1024 * 1024) break
            if (f.path !in keep) { total -= f.length(); f.delete() }
        }
    }

    fun lastPage(uri: String): Int = sp.getInt("pos_$uri", 0)
    fun savePage(uri: String, page: Int) { sp.edit().putInt("pos_$uri", page).apply() }
}
