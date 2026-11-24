package com.pageflow

import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds small but realistic DOCX files in memory for tests (no binary fixtures needed). */
object TestDocx {
    const val NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
        "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
        "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""

    // 1x1 transparent PNG
    val PNG: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    )

    fun run(text: String, rpr: String = "") = "<w:r>${if (rpr.isEmpty()) "" else "<w:rPr>$rpr</w:rPr>"}<w:t xml:space=\"preserve\">$text</w:t></w:r>"
    fun p(vararg runs: String, ppr: String = "") = "<w:p>${if (ppr.isEmpty()) "" else "<w:pPr>$ppr</w:pPr>"}${runs.joinToString("")}</w:p>"
    fun pageBreak() = "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>"
    fun sect(w: Int = 11906, h: Int = 16838, m: Int = 1440, extra: String = "") =
        "<w:sectPr>$extra<w:pgSz w:w=\"$w\" w:h=\"$h\"/><w:pgMar w:top=\"$m\" w:bottom=\"$m\" w:left=\"$m\" w:right=\"$m\"/></w:sectPr>"

    fun image(rid: String, cxEmu: Long, cyEmu: Long) =
        "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"$cxEmu\" cy=\"$cyEmu\"/>" +
            "<a:graphic><a:graphicData><a:blip r:embed=\"$rid\"/></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"

    fun build(
        dir: File, body: String, styles: String? = null, numbering: String? = null,
        media: Map<String, ByteArray> = emptyMap(), name: String = "t.docx"
    ): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(n: String, data: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(data); z.closeEntry() }
            val rels = StringBuilder("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            media.keys.forEachIndexed { i, k -> rels.append("<Relationship Id=\"rId${i + 1}\" Type=\"image\" Target=\"media/$k\"/>") }
            rels.append("</Relationships>")
            put("word/_rels/document.xml.rels", rels.toString().toByteArray())
            put("word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document $NS><w:body>$body</w:body></w:document>".toByteArray(Charsets.UTF_8))
            if (styles != null) put("word/styles.xml", "<w:styles $NS>$styles</w:styles>".toByteArray(Charsets.UTF_8))
            if (numbering != null) put("word/numbering.xml", "<w:numbering $NS>$numbering</w:numbering>".toByteArray(Charsets.UTF_8))
            for ((k, v) in media) put("word/media/$k", v)
        }
        return f
    }

    fun parse(dir: File, body: String, styles: String? = null, numbering: String? = null, media: Map<String, ByteArray> = emptyMap()): Doc {
        val f = build(dir, body, styles, numbering, media)
        return DocxParser(f, File(dir, "media")).parse()
    }

    // Bengali samples used across tests
    const val BN_WORD = "বাংলা"                    // বাংলা
    const val BN_CONJUNCT = "ক্ষ"       // ক্ষ  (ka + virama + ssa)
    const val BN_SENTENCE = "আমি বাংলায় লিখি।"
}
