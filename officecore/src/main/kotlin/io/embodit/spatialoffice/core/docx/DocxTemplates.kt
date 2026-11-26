package io.embodit.spatialoffice.core.docx

import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** XML this module writes itself: the parts of a new file, and the few definitions an edit may have to add. */
internal object DocxTemplates {
    const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val REL_PKG = "http://schemas.openxmlformats.org/package/2006/relationships"
    const val CT_MAIN = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
    const val CT_STYLES = "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"
    const val CT_NUMBERING = "application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"
    const val CT_CORE = "application/vnd.openxmlformats-package.core-properties+xml"
    const val CT_APP = "application/vnd.openxmlformats-officedocument.extended-properties+xml"
    const val APP_NAME = "Spatial Office"

    fun appXml(): String = XmlEx.XML_DECL +
        "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">" +
        "<Application>$APP_NAME</Application></Properties>"

    /** Core properties with creation / modification time only — no author. */
    fun coreXml(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        val now = fmt.format(Date())
        return XmlEx.XML_DECL +
            "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">$now</dcterms:created>" +
            "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">$now</dcterms:modified></cp:coreProperties>"
    }

    fun rootRels(mainPart: String): String = XmlEx.XML_DECL +
        "<Relationships xmlns=\"$REL_PKG\">" +
        "<Relationship Id=\"rId1\" Type=\"$REL/officeDocument\" Target=\"$mainPart\"/>" +
        "<Relationship Id=\"rId2\" Type=\"$REL_PKG/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
        "<Relationship Id=\"rId3\" Type=\"$REL/extended-properties\" Target=\"docProps/app.xml\"/>" +
        "</Relationships>"

    /** Paragraph style definition for a title / heading, used in new files and when a file lacks the style. */
    fun headingStyle(target: ParaStyle, id: String, basedOn: String?): String {
        val based = if (basedOn != null) "<w:basedOn w:val=\"$basedOn\"/><w:next w:val=\"$basedOn\"/>" else ""
        return when (target) {
            ParaStyle.TITLE ->
                "<w:style w:type=\"paragraph\" w:styleId=\"$id\"><w:name w:val=\"Title\"/>$based<w:uiPriority w:val=\"10\"/><w:qFormat/>" +
                    "<w:pPr><w:spacing w:after=\"240\" w:line=\"240\" w:lineRule=\"auto\"/><w:contextualSpacing/></w:pPr>" +
                    "<w:rPr><w:b/><w:bCs/><w:sz w:val=\"52\"/><w:szCs w:val=\"52\"/></w:rPr></w:style>"
            else -> {
                val n = when (target) { ParaStyle.H1 -> 1; ParaStyle.H2 -> 2; else -> 3 }
                val size = when (n) { 1 -> 32; 2 -> 28; else -> 24 }
                val before = when (n) { 1 -> 360; 2 -> 240; else -> 200 }
                "<w:style w:type=\"paragraph\" w:styleId=\"$id\"><w:name w:val=\"heading $n\"/>$based<w:uiPriority w:val=\"9\"/><w:qFormat/>" +
                    "<w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before=\"$before\" w:after=\"120\"/><w:outlineLvl w:val=\"${n - 1}\"/></w:pPr>" +
                    "<w:rPr><w:b/><w:bCs/><w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/></w:rPr></w:style>"
            }
        }
    }

    fun stylesXml(): String = XmlEx.XML_DECL +
        "<w:styles xmlns:w=\"$NS_W\">" +
        "<w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"en-US\" w:eastAsia=\"zh-CN\"/></w:rPr></w:rPrDefault>" +
        "<w:pPrDefault><w:pPr><w:spacing w:after=\"160\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
        headingStyle(ParaStyle.H1, "Heading1", "Normal") +
        headingStyle(ParaStyle.H2, "Heading2", "Normal") +
        headingStyle(ParaStyle.H3, "Heading3", "Normal") +
        headingStyle(ParaStyle.TITLE, "Title", "Normal") +
        "<w:style w:type=\"character\" w:default=\"1\" w:styleId=\"DefaultParagraphFont\"><w:name w:val=\"Default Paragraph Font\"/>" +
        "<w:uiPriority w:val=\"1\"/><w:semiHidden/><w:unhideWhenUsed/></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/>" +
        "<w:uiPriority w:val=\"34\"/><w:qFormat/><w:pPr><w:ind w:left=\"720\"/><w:contextualSpacing/></w:pPr></w:style>" +
        "</w:styles>"

    fun numberingXml(): String = XmlEx.XML_DECL + "<w:numbering xmlns:w=\"$NS_W\"></w:numbering>"

    /** A three-level list definition: bullets, or decimal / letter / roman numbers. */
    fun abstractNum(id: Int, bullet: Boolean): String {
        val sb = StringBuilder("<w:abstractNum w:abstractNumId=\"$id\"><w:multiLevelType w:val=\"hybridMultilevel\"/>")
        val glyphs = listOf("•", "◦", "▪")
        // M2 (d17): all nine levels, so Tab can take a list item down to level 8
        for (lvl in 0..8) {
            val left = 720 * (lvl + 1)
            sb.append("<w:lvl w:ilvl=\"$lvl\"><w:start w:val=\"1\"/>")
            if (bullet) sb.append("<w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"${glyphs[lvl % 3]}\"/>")
            else sb.append("<w:numFmt w:val=\"${when (lvl % 3) { 0 -> "decimal"; 1 -> "lowerLetter"; else -> "lowerRoman" }}\"/><w:lvlText w:val=\"%${lvl + 1}.\"/>")
            sb.append("<w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"$left\" w:hanging=\"360\"/></w:pPr></w:lvl>")
        }
        return sb.append("</w:abstractNum>").toString()
    }

    fun documentXml(): String = XmlEx.XML_DECL +
        "<w:document xmlns:w=\"$NS_W\" xmlns:r=\"$NS_R\"><w:body><w:p/>" +
        "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
        "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>" +
        "</w:sectPr></w:body></w:document>"

    fun blankPackage(): OoxmlPackage {
        val p = OoxmlPackage.empty()
        p.putText(
            OoxmlPackage.CONTENT_TYPES,
            XmlEx.XML_DECL + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"$CT_MAIN\"/>" +
                "<Override PartName=\"/word/styles.xml\" ContentType=\"$CT_STYLES\"/>" +
                "<Override PartName=\"/docProps/core.xml\" ContentType=\"$CT_CORE\"/>" +
                "<Override PartName=\"/docProps/app.xml\" ContentType=\"$CT_APP\"/>" +
                "</Types>",
        )
        p.putText("_rels/.rels", rootRels("word/document.xml"))
        p.putText("docProps/app.xml", appXml())
        p.putText("docProps/core.xml", coreXml())
        p.putText("word/document.xml", documentXml())
        p.putText(
            "word/_rels/document.xml.rels",
            XmlEx.XML_DECL + "<Relationships xmlns=\"$REL_PKG\"><Relationship Id=\"rId1\" Type=\"$REL/styles\" Target=\"styles.xml\"/></Relationships>",
        )
        p.putText("word/styles.xml", stylesXml())
        return p
    }

    /** An inline picture run; namespaces are declared on the elements so it fits any document part. */
    fun inlinePicture(rid: String, id: Int, name: String, cx: Long, cy: Long): String {
        val wp = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
        val a = "http://schemas.openxmlformats.org/drawingml/2006/main"
        val pic = "http://schemas.openxmlformats.org/drawingml/2006/picture"
        return "<w:r><w:drawing><wp:inline xmlns:wp=\"$wp\" distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$id\" name=\"${name}\"/>" +
            "<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a=\"$a\" noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
            "<a:graphic xmlns:a=\"$a\"><a:graphicData uri=\"$pic\"><pic:pic xmlns:pic=\"$pic\">" +
            "<pic:nvPicPr><pic:cNvPr id=\"0\" name=\"${name}\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip xmlns:r=\"$NS_R\" r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"
    }

    /** An empty bordered table, columns of equal width filling [widthTwips]. */
    fun table(rows: Int, cols: Int, widthTwips: Int): String {
        val w = widthTwips / cols
        val border = "w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"A0A0A0\""
        val sb = StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"$widthTwips\" w:type=\"dxa\"/><w:tblBorders>")
        for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) sb.append("<w:$side $border/>")
        sb.append("</w:tblBorders><w:tblLook w:val=\"04A0\"/></w:tblPr><w:tblGrid>")
        repeat(cols) { sb.append("<w:gridCol w:w=\"$w\"/>") }
        sb.append("</w:tblGrid>")
        repeat(rows) {
            sb.append("<w:tr>")
            repeat(cols) { sb.append("<w:tc><w:tcPr><w:tcW w:w=\"$w\" w:type=\"dxa\"/></w:tcPr><w:p/></w:tc>") }
            sb.append("</w:tr>")
        }
        return sb.append("</w:tbl>").toString()
    }
}
