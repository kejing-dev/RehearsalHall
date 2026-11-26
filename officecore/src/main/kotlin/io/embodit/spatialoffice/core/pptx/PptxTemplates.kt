package io.embodit.spatialoffice.core.pptx

import io.embodit.spatialoffice.core.docx.DocxTemplates
import io.embodit.spatialoffice.core.docx.XmlEx
import io.embodit.spatialoffice.core.pkg.OoxmlPackage
import io.embodit.spatialoffice.core.pkg.Xml

/** XML this module writes itself: the parts of a new deck, and the parts an edit may have to add. */
internal object PptxTemplates {
    const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val REL_PKG = "http://schemas.openxmlformats.org/package/2006/relationships"
    const val CT_PRES = "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
    const val CT_SLIDE = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
    const val CT_LAYOUT = "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"
    const val CT_MASTER = "application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"
    const val CT_NOTES = "application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml"
    const val CT_NOTES_MASTER = "application/vnd.openxmlformats-officedocument.presentationml.notesMaster+xml"
    const val CT_THEME = "application/vnd.openxmlformats-officedocument.theme+xml"
    const val NS = "xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\""

    private const val TREE_HEAD =
        "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
    private const val CLR_MAP =
        "bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" " +
            "accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\""
    private const val FONT_REFS = "<a:latin typeface=\"+mn-lt\"/><a:ea typeface=\"+mn-ea\"/><a:cs typeface=\"+mn-cs\"/>"
    private const val TX1 = "<a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill>"

    fun rels(vararg entries: Pair<String, String>): String {
        val sb = StringBuilder(XmlEx.XML_DECL).append("<Relationships xmlns=\"$REL_PKG\">")
        entries.forEachIndexed { i, (type, target) -> sb.append("<Relationship Id=\"rId${i + 1}\" Type=\"$type\" Target=\"$target\"/>") }
        return sb.append("</Relationships>").toString()
    }

    /** A placeholder shape. [ph] is the attribute text of `p:ph` (e.g. `type="title"`); an empty [xfrm] inherits the box. */
    fun placeholder(id: Int, name: String, ph: String, xfrm: String = "", bodyPr: String = "<a:bodyPr/>", lstStyle: String = "<a:lstStyle/>", paras: String = "<a:p/>", locks: String = "<a:spLocks noGrp=\"1\"/>", text: Boolean = true): String =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"${Xml.escape(name)}\"/><p:cNvSpPr>$locks</p:cNvSpPr>" +
            "<p:nvPr><p:ph${if (ph.isEmpty()) "" else " $ph"}/></p:nvPr></p:nvSpPr>" +
            (if (xfrm.isEmpty()) "<p:spPr/>" else "<p:spPr>$xfrm</p:spPr>") +
            (if (text) "<p:txBody>$bodyPr$lstStyle$paras</p:txBody>" else "") + "</p:sp>"

    /**
     * M5 p32: a footer-row placeholder (footer text or slide number). [ph] = the attribute text of its `p:ph`; an empty
     * [spPr] inherits the layout's box.
     */
    fun footerPlaceholder(id: Int, name: String, ph: String, spPr: String, bodyPr: String, paras: String, nsDecl: String): String =
        "<p:sp$nsDecl><p:nvSpPr><p:cNvPr id=\"$id\" name=\"${Xml.escape(name)}\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr>" +
            "<p:nvPr><p:ph $ph/></p:nvPr></p:nvSpPr>" + (if (spPr.isEmpty()) "<p:spPr/>" else "<p:spPr>$spPr</p:spPr>") +
            "<p:txBody>$bodyPr<a:lstStyle/>$paras</p:txBody></p:sp>"

    fun xfrm(x: Long, y: Long, w: Long, h: Long): String = "<a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm>"

    /** A slide whose shape tree holds [shapes] (already serialised). */
    fun slide(shapes: String): String = XmlEx.XML_DECL +
        "<p:sld $NS><p:cSld><p:spTree>" +
        "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>" +
        shapes + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"

    fun textBox(id: Int, x: Long, y: Long, w: Long, h: Long, paras: String, nsDecl: String): String =
        "<p:sp$nsDecl><p:nvSpPr><p:cNvPr id=\"$id\" name=\"TextBox $id\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr>${xfrm(x, y, w, h)}<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>" +
            "<p:txBody><a:bodyPr wrap=\"square\" rtlCol=\"0\"><a:spAutoFit/></a:bodyPr><a:lstStyle/>$paras</p:txBody></p:sp>"

    /** A picture shape stretched over its box. */
    fun picture(id: Int, rid: String, x: Long, y: Long, w: Long, h: Long): String =
        "<p:pic xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\"><p:nvPicPr><p:cNvPr id=\"$id\" name=\"Picture $id\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>" +
            "<p:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>" +
            "<p:spPr>${xfrm(x, y, w, h)}<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>"

    const val URI_TABLE = "http://schemas.openxmlformats.org/drawingml/2006/table"

    /**
     * A plain table of [rows] × [cols] empty cells in a graphic frame: thin grey lines on every cell (no table style
     * part is needed), the first row tinted as a header.
     */
    fun table(id: Int, rows: Int, cols: Int, x: Long, y: Long, w: Long, h: Long, nsDecl: String): String {
        val colW = w / cols; val rowH = h / rows
        fun ln(tag: String) = "<a:$tag w=\"12700\"><a:solidFill><a:srgbClr val=\"9CA3AF\"/></a:solidFill></a:$tag>"
        val lines = ln("lnL") + ln("lnR") + ln("lnT") + ln("lnB")
        val sb = StringBuilder("<p:graphicFrame$nsDecl><p:nvGraphicFramePr><p:cNvPr id=\"$id\" name=\"Table $id\"/>")
        sb.append("<p:cNvGraphicFramePr><a:graphicFrameLocks noGrp=\"1\"/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr>")
        sb.append("<p:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"${colW * cols}\" cy=\"${rowH * rows}\"/></p:xfrm>")
        sb.append("<a:graphic><a:graphicData uri=\"$URI_TABLE\"><a:tbl><a:tblPr firstRow=\"1\"/><a:tblGrid>")
        repeat(cols) { sb.append("<a:gridCol w=\"$colW\"/>") }
        sb.append("</a:tblGrid>")
        repeat(rows) { r ->
            sb.append("<a:tr h=\"$rowH\">")
            val fill = if (r == 0) "<a:solidFill><a:srgbClr val=\"E5E7EB\"/></a:solidFill>" else "<a:noFill/>"
            repeat(cols) { sb.append("<a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"en-US\" sz=\"1600\"/></a:p></a:txBody><a:tcPr>$lines$fill</a:tcPr></a:tc>") }
            sb.append("</a:tr>")
        }
        return sb.append("</a:tbl></a:graphicData></a:graphic></p:graphicFrame>").toString()
    }

    /** A filled preset shape with an empty, centred text body. */
    fun shape(id: Int, geom: String, x: Long, y: Long, w: Long, h: Long, fillRgb: Int): String {
        val hex = String.format("%06X", fillRgb and 0xFFFFFF)
        return "<p:sp xmlns:a=\"$NS_A\"><p:nvSpPr><p:cNvPr id=\"$id\" name=\"Shape $id\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr>${xfrm(x, y, w, h)}<a:prstGeom prst=\"${Xml.escape(geom)}\"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val=\"$hex\"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr>" +
            "<p:txBody><a:bodyPr rtlCol=\"0\" anchor=\"ctr\"/><a:lstStyle/><a:p><a:pPr algn=\"ctr\"/><a:endParaRPr lang=\"en-US\" sz=\"1800\"><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill></a:endParaRPr></a:p></p:txBody></p:sp>"
    }

    fun notesSlide(imgPh: String, bodyPh: String, paras: String): String = XmlEx.XML_DECL +
        "<p:notes $NS><p:cSld><p:spTree>$TREE_HEAD" +
        placeholder(2, "Slide Image Placeholder 1", imgPh, locks = "<a:spLocks noGrp=\"1\" noRot=\"1\" noChangeAspect=\"1\"/>", text = false) +
        placeholder(3, "Notes Placeholder 2", bodyPh, paras = paras) +
        "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:notes>"

    fun notesMaster(): String = XmlEx.XML_DECL +
        "<p:notesMaster $NS><p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree>$TREE_HEAD" +
        placeholder(
            2, "Slide Image Placeholder 1", "type=\"sldImg\" idx=\"2\"",
            xfrm = xfrm(685800, 1143000, 5486400, 3086100) + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/>" +
                "<a:ln w=\"12700\"><a:solidFill><a:prstClr val=\"black\"/></a:solidFill></a:ln>",
            locks = "<a:spLocks noGrp=\"1\" noRot=\"1\" noChangeAspect=\"1\"/>", text = false,
        ) +
        placeholder(
            3, "Notes Placeholder 2", "type=\"body\" sz=\"quarter\" idx=\"3\"",
            xfrm = xfrm(685800, 4400550, 5486400, 3600450) + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>",
            bodyPr = "<a:bodyPr vert=\"horz\" lIns=\"91440\" tIns=\"45720\" rIns=\"91440\" bIns=\"45720\" rtlCol=\"0\"/>",
        ) +
        "</p:spTree></p:cSld><p:clrMap $CLR_MAP/>" +
        "<p:notesStyle><a:lvl1pPr marL=\"0\" algn=\"l\" defTabSz=\"914400\" rtl=\"0\" eaLnBrk=\"1\" latinLnBrk=\"0\" hangingPunct=\"1\">" +
        "<a:defRPr sz=\"1200\" kern=\"1200\">$TX1$FONT_REFS</a:defRPr></a:lvl1pPr></p:notesStyle></p:notesMaster>"

    fun theme(): String {
        val name = DocxTemplates.APP_NAME
        val fill = "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
        fun ln(w: Int) = "<a:ln w=\"$w\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\">$fill<a:prstDash val=\"solid\"/></a:ln>"
        val effect = "<a:effectStyle><a:effectLst/></a:effectStyle>"
        fun c(tag: String, rgb: String) = "<a:$tag><a:srgbClr val=\"$rgb\"/></a:$tag>"
        val fonts = "<a:latin typeface=\"$TYPEFACE\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/>"
        return XmlEx.XML_DECL +
            "<a:theme xmlns:a=\"$NS_A\" name=\"$name\"><a:themeElements>" +
            "<a:clrScheme name=\"$name\">" + c("dk1", "000000") + c("lt1", "FFFFFF") + c("dk2", "1F2937") + c("lt2", "F3F4F6") +
            c("accent1", "2563EB") + c("accent2", "F97316") + c("accent3", "10B981") + c("accent4", "EAB308") +
            c("accent5", "8B5CF6") + c("accent6", "EC4899") + c("hlink", "2563EB") + c("folHlink", "7C3AED") + "</a:clrScheme>" +
            "<a:fontScheme name=\"$name\"><a:majorFont>$fonts</a:majorFont><a:minorFont>$fonts</a:minorFont></a:fontScheme>" +
            "<a:fmtScheme name=\"$name\"><a:fillStyleLst>$fill$fill$fill</a:fillStyleLst>" +
            "<a:lnStyleLst>${ln(6350)}${ln(12700)}${ln(19050)}</a:lnStyleLst>" +
            "<a:effectStyleLst>$effect$effect$effect</a:effectStyleLst>" +
            "<a:bgFillStyleLst>$fill$fill$fill</a:bgFillStyleLst></a:fmtScheme>" +
            "</a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"
    }

    /** Latin typeface named by the theme of a new deck; readers substitute their own when it is not installed. */
    private const val TYPEFACE = "Noto Sans" // an open font; other software substitutes its default when it is missing

    private fun lvl(n: Int, attrs: String, inner: String, sz: Int, major: Boolean = false): String {
        val refs = if (major) "<a:latin typeface=\"+mj-lt\"/><a:ea typeface=\"+mj-ea\"/><a:cs typeface=\"+mj-cs\"/>" else FONT_REFS
        return "<a:lvl${n}pPr $attrs defTabSz=\"914400\" rtl=\"0\" eaLnBrk=\"1\" latinLnBrk=\"0\" hangingPunct=\"1\">$inner" +
            "<a:defRPr sz=\"$sz\" kern=\"1200\">$TX1$refs</a:defRPr></a:lvl${n}pPr>"
    }

    private fun master(): String {
        val body = StringBuilder()
        val sizes = listOf(2800, 2400, 2000, 1800, 1800)
        for (n in 1..5) {
            body.append(lvl(n, "marL=\"${228600 + 457200 * (n - 1)}\" indent=\"-228600\" algn=\"l\"",
                "<a:lnSpc><a:spcPct val=\"90000\"/></a:lnSpc><a:spcBef><a:spcPts val=\"${if (n == 1) 1000 else 500}\"/></a:spcBef><a:buChar char=\"•\"/>", sizes[n - 1]))
        }
        return XmlEx.XML_DECL +
            "<p:sldMaster $NS><p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree>$TREE_HEAD" +
            placeholder(2, "Title Placeholder 1", "type=\"title\"", xfrm = xfrm(838200, 365125, 10515600, 1325563) + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>",
                bodyPr = "<a:bodyPr vert=\"horz\" lIns=\"91440\" tIns=\"45720\" rIns=\"91440\" bIns=\"45720\" rtlCol=\"0\" anchor=\"ctr\"><a:normAutofit/></a:bodyPr>") +
            placeholder(3, "Text Placeholder 2", "type=\"body\" idx=\"1\"", xfrm = xfrm(838200, 1825625, 10515600, 4351338) + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>",
                bodyPr = "<a:bodyPr vert=\"horz\" lIns=\"91440\" tIns=\"45720\" rIns=\"91440\" bIns=\"45720\" rtlCol=\"0\"><a:normAutofit/></a:bodyPr>") +
            "</p:spTree></p:cSld><p:clrMap $CLR_MAP/>" +
            "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/><p:sldLayoutId id=\"2147483650\" r:id=\"rId2\"/></p:sldLayoutIdLst>" +
            "<p:txStyles><p:titleStyle>" +
            lvl(1, "algn=\"l\"", "<a:lnSpc><a:spcPct val=\"90000\"/></a:lnSpc><a:spcBef><a:spcPct val=\"0\"/></a:spcBef><a:buNone/>", 4400, major = true) +
            "</p:titleStyle><p:bodyStyle>$body</p:bodyStyle><p:otherStyle>" + lvl(1, "marL=\"0\" algn=\"l\"", "", 1800) +
            "</p:otherStyle></p:txStyles></p:sldMaster>"
    }

    private fun layout(type: String, name: String, shapes: String): String = XmlEx.XML_DECL +
        "<p:sldLayout $NS type=\"$type\" preserve=\"1\"><p:cSld name=\"$name\"><p:spTree>$TREE_HEAD$shapes</p:spTree></p:cSld>" +
        "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"

    private fun titleLayout(): String = layout(
        "title", "Title Slide",
        placeholder(2, "Title 1", "type=\"ctrTitle\"", xfrm = xfrm(1524000, 1122363, 9144000, 2387600), bodyPr = "<a:bodyPr anchor=\"b\"/>",
            lstStyle = "<a:lstStyle><a:lvl1pPr algn=\"ctr\"><a:defRPr sz=\"6000\"/></a:lvl1pPr></a:lstStyle>") +
            placeholder(3, "Subtitle 2", "type=\"subTitle\" idx=\"1\"", xfrm = xfrm(1524000, 3602038, 9144000, 1655762),
                lstStyle = "<a:lstStyle><a:lvl1pPr marL=\"0\" indent=\"0\" algn=\"ctr\"><a:buNone/><a:defRPr sz=\"2400\"/></a:lvl1pPr></a:lstStyle>"),
    )

    private fun bodyLayout(): String = layout(
        "obj", "Title and Content",
        placeholder(2, "Title 1", "type=\"title\"") + placeholder(3, "Content Placeholder 2", "idx=\"1\""),
    )

    private fun presentation(): String = XmlEx.XML_DECL +
        "<p:presentation $NS saveSubsetFonts=\"1\"><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
        "<p:sldIdLst><p:sldId id=\"256\" r:id=\"rId2\"/></p:sldIdLst><p:sldSz cx=\"12192000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/>" +
        "<p:defaultTextStyle><a:defPPr><a:defRPr lang=\"en-US\"/></a:defPPr>" + lvl(1, "marL=\"0\" algn=\"l\"", "", 1800) +
        "</p:defaultTextStyle></p:presentation>"

    fun blankPackage(): OoxmlPackage {
        val p = OoxmlPackage.empty()
        p.putText(
            OoxmlPackage.CONTENT_TYPES,
            XmlEx.XML_DECL + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"$CT_PRES\"/>" +
                "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"$CT_MASTER\"/>" +
                "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"$CT_LAYOUT\"/>" +
                "<Override PartName=\"/ppt/slideLayouts/slideLayout2.xml\" ContentType=\"$CT_LAYOUT\"/>" +
                "<Override PartName=\"/ppt/slides/slide1.xml\" ContentType=\"$CT_SLIDE\"/>" +
                "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"$CT_THEME\"/>" +
                "<Override PartName=\"/docProps/core.xml\" ContentType=\"${DocxTemplates.CT_CORE}\"/>" +
                "<Override PartName=\"/docProps/app.xml\" ContentType=\"${DocxTemplates.CT_APP}\"/>" +
                "</Types>",
        )
        p.putText("_rels/.rels", DocxTemplates.rootRels("ppt/presentation.xml"))
        p.putText("docProps/app.xml", DocxTemplates.appXml())
        p.putText("docProps/core.xml", DocxTemplates.coreXml())
        p.putText("ppt/presentation.xml", presentation())
        p.putText("ppt/_rels/presentation.xml.rels", rels("$REL/slideMaster" to "slideMasters/slideMaster1.xml", "$REL/slide" to "slides/slide1.xml", "$REL/theme" to "theme/theme1.xml"))
        p.putText("ppt/theme/theme1.xml", theme())
        p.putText("ppt/slideMasters/slideMaster1.xml", master())
        p.putText("ppt/slideMasters/_rels/slideMaster1.xml.rels", rels("$REL/slideLayout" to "../slideLayouts/slideLayout1.xml", "$REL/slideLayout" to "../slideLayouts/slideLayout2.xml", "$REL/theme" to "../theme/theme1.xml"))
        p.putText("ppt/slideLayouts/slideLayout1.xml", titleLayout())
        p.putText("ppt/slideLayouts/_rels/slideLayout1.xml.rels", rels("$REL/slideMaster" to "../slideMasters/slideMaster1.xml"))
        p.putText("ppt/slideLayouts/slideLayout2.xml", bodyLayout())
        p.putText("ppt/slideLayouts/_rels/slideLayout2.xml.rels", rels("$REL/slideMaster" to "../slideMasters/slideMaster1.xml"))
        p.putText("ppt/slides/slide1.xml", slide(placeholder(2, "Title 1", "type=\"ctrTitle\"") + placeholder(3, "Subtitle 2", "type=\"subTitle\" idx=\"1\"")))
        p.putText("ppt/slides/_rels/slide1.xml.rels", rels("$REL/slideLayout" to "../slideLayouts/slideLayout1.xml"))
        return p
    }
}
