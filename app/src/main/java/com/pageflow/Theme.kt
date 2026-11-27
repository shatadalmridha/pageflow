package com.pageflow

/** A reading theme: colours for the page, text, header bar and footer bar. All are opaque ARGB ints. */
data class ReaderTheme(val id: String, val name: String, val page: Int, val ink: Int, val header: Int, val footer: Int) {
    val dark: Boolean get() = relLum(page) < 0.35f
}

private fun lin(v: Int): Float {
    val s = v / 255f
    return if (s <= 0.03928f) s / 12.92f else Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
}

/** WCAG relative luminance. */
fun relLum(c: Int): Float = 0.2126f * lin((c shr 16) and 255) + 0.7152f * lin((c shr 8) and 255) + 0.0722f * lin(c and 255)

/** WCAG contrast ratio, 1..21. */
fun contrast(a: Int, b: Int): Float {
    val l1 = relLum(a); val l2 = relLum(b)
    return (maxOf(l1, l2) + 0.05f) / (minOf(l1, l2) + 0.05f)
}

const val BLACK = 0xFF000000.toInt()
const val WHITE = 0xFFFFFFFF.toInt()

/** Black or white, whichever reads better on [bg]. */
fun bestOn(bg: Int): Int = if (contrast(BLACK, bg) >= contrast(WHITE, bg)) BLACK else WHITE

/** Parses #RGB, #RRGGBB (with or without '#'). Returns opaque ARGB or null. */
fun parseHex(s: String): Int? {
    var t = s.trim().removePrefix("#")
    if (t.length == 3) t = t.map { "$it$it" }.joinToString("")
    if (t.length != 6) return null
    val v = t.toLongOrNull(16) ?: return null
    return (0xFF000000L or v).toInt()
}

/** Mix of two colours, t = 0 gives [a], t = 1 gives [b]. */
fun blend(a: Int, b: Int, t: Float): Int {
    fun ch(sh: Int) = (((a shr sh) and 255) * (1 - t) + ((b shr sh) and 255) * t).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

fun toHex(c: Int): String = "#%06X".format(c and 0xFFFFFF)

fun hsvToColor(h: Float, s: Float, v: Float): Int {
    val hh = ((h % 360f) + 360f) % 360f / 60f
    val i = hh.toInt(); val f = hh - i
    val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
    val (r, g, b) = when (i) { 0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t); 3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q) }
    fun ch(x: Float) = (x * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

/** Returns (hue 0..360, saturation 0..1, value 0..1). */
fun colorToHsv(c: Int): Triple<Float, Float, Float> {
    val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
    val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
    val h = when {
        d == 0f -> 0f
        mx == r -> 60f * (((g - b) / d) % 6f)
        mx == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    return Triple(if (h < 0f) h + 360f else h, if (mx == 0f) 0f else d / mx, mx)
}

object Themes {
    const val CUSTOM_ID = "custom"
    const val OLD_LACE_HEX = 0xFFFDF5E6.toInt()   // #FDF5E6  (rgb 253, 245, 230)

    val presets: List<ReaderTheme> = listOf(
        ReaderTheme("white", "Classic White", 0xFFFFFFFF.toInt(), 0xFF1A1A1A.toInt(), 0xFFF7F7F7.toInt(), 0xFFF7F7F7.toInt()),
        ReaderTheme("oldlace", "Old Lace", OLD_LACE_HEX, 0xFF3B3226.toInt(), OLD_LACE_HEX, OLD_LACE_HEX),
        ReaderTheme("cream", "Warm Cream", 0xFFFFF8E7.toInt(), 0xFF33302A.toInt(), 0xFFF8EFD8.toInt(), 0xFFF8EFD8.toInt()),
        ReaderTheme("ivory", "Soft Ivory", 0xFFFFFFF0.toInt(), 0xFF2E2E2A.toInt(), 0xFFF5F5E6.toInt(), 0xFFF5F5E6.toInt()),
        ReaderTheme("sepia", "Sepia", 0xFFF4ECD8.toInt(), 0xFF5B4636.toInt(), 0xFFEADFC6.toInt(), 0xFFEADFC6.toInt()),
        ReaderTheme("sage", "Soft Sage", 0xFFE4EDE0.toInt(), 0xFF2D3A2E.toInt(), 0xFFD9E5D4.toInt(), 0xFFD9E5D4.toInt()),
        ReaderTheme("mist", "Mist Blue", 0xFFE6EEF5.toInt(), 0xFF22313F.toInt(), 0xFFD8E4EF.toInt(), 0xFFD8E4EF.toInt()),
        ReaderTheme("darkgrey", "Dark Grey", 0xFF2B2B2B.toInt(), 0xFFE0E0E0.toInt(), 0xFF1F1F1F.toInt(), 0xFF1F1F1F.toInt()),
        ReaderTheme("black", "Pure Black", 0xFF000000.toInt(), 0xFFCFCFCF.toInt(), 0xFF0D0D0D.toInt(), 0xFF0D0D0D.toInt())
    )

    fun find(id: String, custom: ReaderTheme?): ReaderTheme =
        if (id == CUSTOM_ID && custom != null) custom else presets.firstOrNull { it.id == id } ?: presets[1]

    /** Warning text for uncomfortable combinations, or null when fine. */
    fun warning(t: ReaderTheme): String? {
        val c = contrast(t.page, t.ink)
        if (c < 4.5f) return "Text contrast is low (%.1f:1). Aim for 4.5:1 or more.".format(c)
        if (c > 19f) return "Very high contrast can tire the eyes; consider a softer text colour."
        val hc = contrast(t.header, bestOn(t.header))
        if (hc < 4.5f) return "Header colour makes its icons hard to read."
        return null
    }
}
