package com.pageflow

import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.security.MessageDigest

object HlColors {
    // yellow, light green, light blue, pink, orange
    val all = listOf(0xFFFFEB3B.toInt(), 0xFFAED581.toInt(), 0xFF81D4FA.toInt(), 0xFFF48FB1.toInt(), 0xFFFFB74D.toInt())
    val names = listOf("Yellow", "Light green", "Light blue", "Pink", "Orange")
}

/**
 * One highlighted piece. DOCX highlights are anchored to a paragraph id and character offsets, so they survive
 * font-size changes and re-pagination. PDF highlights keep the page number and the rectangles (in points).
 * [group] ties together the pieces created by one selection so they can be recoloured or removed together.
 */
class Hl(
    val id: String, val group: String, val page: Int, val para: Int, val start: Int, val end: Int,
    val color: Int, val text: String, val rects: List<FloatArray> = emptyList()
) {
    fun withColor(c: Int) = Hl(id, group, page, para, start, end, c, text, rects)
}

object HlCodec {
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")
    private fun unesc(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 't' -> sb.append('\t'); 'n' -> sb.append('\n'); else -> sb.append(s[i + 1]) }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    fun encode(h: Hl): String = listOf(
        h.id, h.group, h.page.toString(), h.para.toString(), h.start.toString(), h.end.toString(),
        (h.color and 0xFFFFFF).toString(16), esc(h.text),
        h.rects.joinToString(";") { it.joinToString(",") }
    ).joinToString("\t")

    fun decode(line: String): Hl? {
        val p = line.split("\t")
        if (p.size < 9) return null
        return try {
            Hl(p[0], p[1], p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toInt(),
                (0xFF000000L or p[6].toLong(16)).toInt(), unesc(p[7]),
                if (p[8].isEmpty()) emptyList() else p[8].split(";").map { r -> r.split(",").map { it.toFloat() }.toFloatArray() })
        } catch (_: Exception) { null }
    }

    fun docKey(uri: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(24)
    }
}

/** Highlights of one document, stored in the app's private folder, separate from the document itself. */
class HighlightStore(private val file: File?) {
    val items = mutableStateListOf<Hl>()

    init { load() }

    private fun load() {
        try {
            if (file != null && file.exists()) file.readLines().mapNotNull { HlCodec.decode(it) }.forEach { items.add(it) }
        } catch (_: Throwable) {}
    }

    private fun save() {
        if (file == null) return
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(items.joinToString("\n") { HlCodec.encode(it) })
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Throwable) {}
    }

    fun add(list: List<Hl>) { items.addAll(list); save() }
    fun removeGroup(group: String) { items.removeAll { it.group == group }; save() }
    fun recolorGroup(group: String, color: Int) {
        for (i in items.indices) if (items[i].group == group) items[i] = items[i].withColor(color)
        save()
    }
}
