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

    fun anchorImage(rid: String, wPt: Int, hPt: Int, behind: Boolean, hRel: String, hOffEmu: Long, vRel: String, vAlign: String, wrap: String = "wrapNone") =
        "<w:p><w:r><w:drawing><wp:anchor behindDoc=\"${if (behind) 1 else 0}\">" +
            "<wp:positionH relativeFrom=\"$hRel\"><wp:posOffset>$hOffEmu</wp:posOffset></wp:positionH>" +
            "<wp:positionV relativeFrom=\"$vRel\"><wp:align>$vAlign</wp:align></wp:positionV>" +
            "<wp:extent cx=\"${wPt * 12700L}\" cy=\"${hPt * 12700L}\"/><wp:$wrap/>" +
            "<a:graphic><a:graphicData><a:blip r:embed=\"$rid\"/></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r></w:p>"

    val THEME = """<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:themeElements>
        <a:clrScheme name="t"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>
        <a:dk2><a:srgbClr val="44546A"/></a:dk2><a:lt2><a:srgbClr val="E7E6E6"/></a:lt2>
        <a:accent1><a:srgbClr val="4472C4"/></a:accent1><a:accent2><a:srgbClr val="ED7D31"/></a:accent2></a:clrScheme>
        <a:fontScheme name="f"><a:majorFont><a:latin typeface="Cambria"/><a:cs typeface=""/></a:majorFont>
        <a:minorFont><a:latin typeface="Calibri"/><a:cs typeface=""/><a:font script="Beng" typeface="Vrinda"/></a:minorFont></a:fontScheme>
        </a:themeElements></a:theme>"""

    fun build(
        dir: File, body: String, styles: String? = null, numbering: String? = null,
        media: Map<String, ByteArray> = emptyMap(), name: String = "t.docx", theme: String? = null
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
            if (theme != null) put("word/theme/theme1.xml", theme.toByteArray(Charsets.UTF_8))
            for ((k, v) in media) put("word/media/$k", v)
        }
        return f
    }

    fun parse(dir: File, body: String, styles: String? = null, numbering: String? = null, media: Map<String, ByteArray> = emptyMap(), theme: String? = null): Doc {
        val f = build(dir, body, styles, numbering, media, theme = theme)
        return DocxParser(f, File(dir, "media")).parse()
    }

    // Bengali samples used across tests
    const val BN_WORD = "বাংলা"                    // বাংলা
    const val BN_CONJUNCT = "ক্ষ"       // ক্ষ  (ka + virama + ssa)
    const val BN_SENTENCE = "আমি বাংলায় লিখি।"
}
