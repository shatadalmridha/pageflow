package com.pageflow

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.speech.tts.TextToSpeech
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/** Pure text helpers for dictionary lookup (unit-tested). */
object DictText {
    fun normalize(w: String): String {
        var s = Normalizer.normalize(w, Normalizer.Form.NFC).trim()
        fun keep(c: Char): Boolean {
            if (Character.isLetterOrDigit(c)) return true
            val t = Character.getType(c)
            return t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()
        }
        var a = 0; var b = s.length
        while (a < b && !keep(s[a])) a++
        while (b > a && !keep(s[b - 1])) b--
        s = s.substring(a, b)
        return s.lowercase(Locale.ROOT)
    }

    fun hasBengali(s: String) = s.any { isBn(it) }

    fun englishCandidates(w: String): List<String> {
        val out = LinkedHashSet<String>()
        fun add(x: String) { if (x.length >= 2) out.add(x) }
        fun undouble(x: String) { if (x.length >= 3 && x[x.length - 1] == x[x.length - 2]) add(x.dropLast(1)) }
        add(w)
        if (w.endsWith("ies")) add(w.dropLast(3) + "y")
        if (w.endsWith("es")) { add(w.dropLast(2)); add(w.dropLast(1)) }
        if (w.endsWith("s") && !w.endsWith("ss")) add(w.dropLast(1))
        if (w.endsWith("ied")) add(w.dropLast(3) + "y")
        if (w.endsWith("ed")) { add(w.dropLast(2)); add(w.dropLast(1)); undouble(w.dropLast(2)) }
        if (w.endsWith("ing")) { add(w.dropLast(3)); add(w.dropLast(3) + "e"); undouble(w.dropLast(3)) }
        if (w.endsWith("ly")) add(w.dropLast(2))
        if (w.endsWith("er")) { add(w.dropLast(2)); add(w.dropLast(1)); undouble(w.dropLast(2)) }
        if (w.endsWith("est")) { add(w.dropLast(3)); add(w.dropLast(2)); undouble(w.dropLast(3)) }
        return out.toList()
    }

    private val BN_SUFFIXES = listOf(
        "গুলোকে", "গুলোর", "গুলোতে", "গুলির", "গুলো", "গুলি", "দেরকে", "দের", "টিকে", "টিতে", "টির", "টি", "টা",
        "খানা", "খানি", "েরা", "রা", "ের", "কে", "তে", "েও", "ও", "র", "ে", "য়"
    ).sortedByDescending { it.length }

    fun bengaliCandidates(w: String): List<String> {
        val out = LinkedHashSet<String>()
        out.add(w)
        var frontier = listOf(w)
        repeat(2) {
            val next = ArrayList<String>()
            for (x in frontier) for (suf in BN_SUFFIXES) if (x.endsWith(suf) && x.length > suf.length) {
                val r = x.substring(0, x.length - suf.length)
                if (out.add(r)) next.add(r)
            }
            frontier = next
        }
        return out.toList()
    }

    /** Exact form first, then likely root forms. */
    fun candidates(word: String): List<String> {
        val w = normalize(word)
        if (w.isEmpty()) return emptyList()
        return if (hasBengali(w)) bengaliCandidates(w) else englishCandidates(w)
    }

    fun splitMeanings(m: String): List<String> =
        m.split(';', '|', '\n').flatMap { it.split(", ") }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

class DictResult(val query: String, val matched: String?, val meanings: List<String>, val viaRoot: Boolean)

/** Offline dictionary in SQLite. It is filled by the user's own TSV/CSV file; PageFlow ships no dictionary data. */
class DictDb(private val ctx: Context) {
    private fun open(): SQLiteDatabase {
        val f = ctx.getDatabasePath("dictionary.db")
        f.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        db.execSQL("CREATE TABLE IF NOT EXISTS dict (k TEXT NOT NULL, word TEXT NOT NULL, meaning TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS dict_k ON dict (k)")
        return db
    }

    fun count(): Long = try {
        open().use { db -> db.compileStatement("SELECT COUNT(*) FROM dict").simpleQueryForLong() }
    } catch (_: Throwable) { 0L }

    fun clear() { try { open().use { it.execSQL("DELETE FROM dict") } } catch (_: Throwable) {} }

    fun lookup(word: String): DictResult {
        val cands = DictText.candidates(word)
        if (cands.isEmpty()) return DictResult(word, null, emptyList(), false)
        try {
            open().use { db ->
                for ((i, c) in cands.withIndex()) {
                    val ms = ArrayList<String>()
                    db.rawQuery("SELECT meaning FROM dict WHERE k = ? LIMIT 20", arrayOf(c)).use { cur ->
                        while (cur.moveToNext()) ms.addAll(DictText.splitMeanings(cur.getString(0)))
                    }
                    if (ms.isNotEmpty()) return DictResult(word, c, ms.distinct(), i > 0)
                }
            }
        } catch (_: Throwable) {}
        return DictResult(word, null, emptyList(), false)
    }

    /** Parses "word<TAB>meaning" or "word,meaning" lines. Call off the main thread. Returns entries added. */
    fun import(uri: Uri, onProgress: (Int) -> Unit = {}): Int {
        var n = 0
        val db = open()
        try {
            db.beginTransaction()
            val st = db.compileStatement("INSERT INTO dict (k, word, meaning) VALUES (?, ?, ?)")
            ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { r ->
                var first = true
                for (raw in r.lineSequence()) {
                    val line = raw.trimStart('\uFEFF')
                    if (line.isBlank() || line.startsWith("#")) continue
                    val (w, m) = parseLine(line) ?: continue
                    if (first) { first = false; if (w.equals("word", true) || w.equals("english", true) || w.equals("bangla", true)) continue }
                    val k = DictText.normalize(w)
                    if (k.isEmpty()) continue
                    st.clearBindings(); st.bindString(1, k); st.bindString(2, w.trim()); st.bindString(3, m.trim())
                    st.executeInsert()
                    n++
                    if (n % 5000 == 0) onProgress(n)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            try { db.endTransaction() } catch (_: Throwable) {}
            db.close()
        }
        onProgress(n)
        return n
    }

    companion object {
        fun parseLine(line: String): Pair<String, String>? {
            val t = line.indexOf('\t')
            if (t > 0) return Pair(line.substring(0, t), line.substring(t + 1)).takeIf { it.second.isNotBlank() }
            if (line.startsWith("\"")) {
                val q = line.indexOf("\",", 1)
                if (q > 0) return Pair(line.substring(1, q), line.substring(q + 2).trim('"')).takeIf { it.second.isNotBlank() }
            }
            val c = line.indexOf(',')
            if (c > 0) return Pair(line.substring(0, c), line.substring(c + 1).trim('"')).takeIf { it.second.isNotBlank() }
            return null
        }
    }
}

/** System text-to-speech for pronunciation (works offline when a voice is installed). */
class Speaker(ctx: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { ready = (it == TextToSpeech.SUCCESS) }

    /** Returns an error message, or null if speech started. */
    fun speak(text: String): String? {
        if (!ready) return "Text-to-speech is not ready."
        val loc = if (DictText.hasBengali(text)) Locale("bn", "BD") else Locale.US
        val r = tts.setLanguage(loc)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
            return "No voice for this language is installed (Android Settings → Text-to-speech)."
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pageflow")
        return null
    }

    fun shutdown() { try { tts.stop(); tts.shutdown() } catch (_: Throwable) {} }
}

object External {
    fun translateUrl(text: String): String =
        "https://translate.google.com/?sl=auto&tl=bn&op=translate&text=" + URLEncoder.encode(text.take(1500), "UTF-8")

    fun wiktionaryUrl(word: String): String =
        "https://en.wiktionary.org/wiki/" + URLEncoder.encode(DictText.normalize(word).ifEmpty { word }, "UTF-8").replace("+", "_")

    /** Opens a link in Google Translate (if installed and registered for the link) or the browser. */
    fun open(ctx: Context, url: String): Boolean = try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Throwable) { false }
}
