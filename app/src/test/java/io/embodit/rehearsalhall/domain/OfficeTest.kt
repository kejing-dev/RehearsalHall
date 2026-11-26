package io.embodit.rehearsalhall.domain

import io.embodit.spatialoffice.core.docx.DocxDocument
import io.embodit.spatialoffice.core.docx.ParaStyle
import io.embodit.spatialoffice.core.docx.Paragraph
import io.embodit.spatialoffice.core.docx.Run
import io.embodit.spatialoffice.core.docx.TableBlock
import io.embodit.spatialoffice.core.pptx.PptxDeck
import io.embodit.spatialoffice.core.pptx.Shape
import io.embodit.spatialoffice.core.pptx.ShapeKind
import io.embodit.spatialoffice.core.pptx.SlideLayoutKind
import io.embodit.spatialoffice.core.pptx.Slide
import io.embodit.spatialoffice.core.pptx.TextPara
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** I1: reading .pptx / .docx for a rehearsal (vendored Spatial Office core + our pure rules). */
class OfficeTest {
    private fun p(id: Int, text: String, style: ParaStyle = ParaStyle.BODY) = Paragraph(id, style, listOf(Run(text)), true, null)

    private fun shape(kind: ShapeKind, fill: Int? = null, text: String = "", image: String? = null) =
        Shape(1, kind, 0, 0, 100, 100, if (text.isEmpty()) emptyList() else listOf(TextPara(text)), null, false, null, 0, image, true, fillRgb = fill)

    @Test
    fun pptxNotesAndTitleRoundTrip() {
        val d = PptxDeck.blank()
        d.addSlide(0, SlideLayoutKind.TITLE_BODY)
        d.addSlide(1, SlideLayoutKind.TITLE_BODY)
        val title = d.slides[0].shapes.first { it.kind == ShapeKind.TITLE }
        d.setShapeText(0, title.id, listOf(TextPara("Q4 发布")))
        d.setNotes(0, "开场：先讲用户故事")
        d.setNotes(2, "收尾：三个数字")
        val back = PptxDeck.open(d.save())
        assertEquals(3, back.slides.size)
        assertEquals(listOf("开场：先讲用户故事", "", "收尾：三个数字"), back.slides.map { it.notes.trim() })
        assertEquals("Q4 发布", SlideCheck.title(back.slides, "file"))
        assertFalse(back.slides.any { SlideCheck.partial(it) })
    }

    @Test
    fun titleFallsBackToFileName() {
        assertEquals("deck", SlideCheck.title(listOf(Slide(0, listOf(shape(ShapeKind.BODY, text = "x")), "", null)), "deck"))
        assertEquals("deck", SlideCheck.title(emptyList(), "deck"))
    }

    @Test
    fun partialPagesAreChartsAndEmptyFrames() {
        fun slide(vararg s: Shape) = Slide(0, s.toList(), "", null)
        assertTrue(SlideCheck.partial(slide(shape(ShapeKind.CHART))))
        assertTrue(SlideCheck.partial(slide(shape(ShapeKind.OTHER)))) // SmartArt / unknown graphic frame
        assertTrue(SlideCheck.partial(slide(shape(ShapeKind.PICTURE)))) // picture whose part is missing
        assertFalse(SlideCheck.partial(slide(shape(ShapeKind.OTHER, fill = 0x3366CC)))) // a drawn rectangle
        assertFalse(SlideCheck.partial(slide(shape(ShapeKind.OTHER, text = "label"))))
        assertFalse(SlideCheck.partial(slide(shape(ShapeKind.PICTURE, image = "ppt/media/a.png"), shape(ShapeKind.TITLE, text = "T"))))
        val group = shape(ShapeKind.OTHER).copy(children = listOf(shape(ShapeKind.CHART)))
        assertTrue(SlideCheck.partial(slide(group)))
    }

    @Test
    fun scriptSplitsAtH1() {
        val blocks = listOf(
            p(1, "演讲稿", ParaStyle.TITLE),
            p(2, "大家好。"),
            p(3, "背景", ParaStyle.H1),
            p(4, "去年我们做了三件事。第一件是……"),
            p(5, "小节", ParaStyle.H2),
            p(6, "细节"),
            p(7, "结论", ParaStyle.H1),
            p(8, "谢谢。"),
        )
        val s = Script.sections(blocks, "f")
        assertEquals(listOf("演讲稿", "背景", "结论"), s.map { it.title })
        assertEquals("大家好。", s[0].text)
        assertEquals(listOf("去年我们做了三件事。第一件是……", "小节", "细节"), s[1].lines) // H2 stays inside its H1
        assertEquals("谢谢。", s[2].text)
    }

    @Test
    fun scriptUsesH2WhenNoH1AndPageBreaks() {
        val s = Script.sections(listOf(p(1, "一", ParaStyle.H2), p(2, "a"), p(3, "二", ParaStyle.H2), p(4, "b")), "f")
        assertEquals(listOf("一", "二"), s.map { it.title })
        val br = Script.sections(listOf(p(1, "第一页内容"), p(2, "\u000C"), p(3, "第二页内容")), "f")
        assertEquals(2, br.size)
        assertEquals("第一页内容", br[0].title) // no heading: the first line names the part
        assertEquals("第二页内容", br[1].text)
    }

    @Test
    fun scriptWithoutHeadingsIsChunked() {
        val para = "这是一段比较长的讲稿内容。".repeat(10) // 130 chars
        val blocks = (1..12).map { p(it, para) } // 1560 chars
        val s = Script.sections(blocks, "f")
        assertTrue("got ${s.size}", s.size in 2..4)
        assertEquals(blocks.size, s.sumOf { it.lines.size })
    }

    @Test
    fun scriptKeepsTablesAsLinesAndCapsAt60() {
        val s = Script.sections(listOf(p(1, "T", ParaStyle.H1), TableBlock(2, listOf(listOf("年份", "收入"), listOf("2025", "3.2")))), "f")
        assertEquals(listOf("年份\t收入", "2025\t3.2"), s[0].lines)
        val many = (1..80).flatMap { listOf(p(it * 2, "H$it", ParaStyle.H1), p(it * 2 + 1, "x")) }
        assertEquals(Deck.MAX_PAGES, Script.sections(many, "f").size)
    }

    @Test
    fun docxRoundTripThroughCore() {
        val d = DocxDocument.blank()
        val first = d.blocks.first().id
        val a = d.insertParagraphAfter(first, listOf(Run("开场")), ParaStyle.H1)
        val b = d.insertParagraphAfter(a, listOf(Run("各位好，今天讲三件事。")), ParaStyle.BODY)
        val c = d.insertParagraphAfter(b, listOf(Run("第二部分")), ParaStyle.H1)
        d.insertParagraphAfter(c, listOf(Run("具体数字。")), ParaStyle.BODY)
        val back = DocxDocument.open(d.save())
        val s = Script.sections(back.blocks, "f")
        assertEquals(listOf("开场", "第二部分"), s.map { it.title })
        assertEquals("各位好，今天讲三件事。", s[0].text)
    }

    @Test
    fun paceGivesMinutes() {
        val zh = Script.Section("t", listOf("字".repeat(1000)))
        assertEquals(5, Script.minutes(listOf(zh))) // 1001 chars / 250 -> 4.004 -> 5
        val en = Script.Section("t", listOf((1..260).joinToString(" ") { "word" }))
        assertEquals(3, Script.minutes(listOf(en))) // 261 words / 130 -> 2.0 -> 3
        assertEquals(1, Script.minutes(emptyList()))
    }

    @Test
    fun scriptTitleNeverABodySentence() {
        assertEquals("讲稿", Script.title(listOf(p(1, "正文第一句很长很长"), p(2, "讲稿", ParaStyle.TITLE)), "file"))
        assertEquals("背景", Script.title(listOf(p(1, "正文"), p(2, "背景", ParaStyle.H1)), "file"))
        assertEquals("file", Script.title(listOf(p(1, "正文第一句很长很长")), "file"))
    }

    @Test
    fun cardLinesTakeFirstSentence() {
        val l = Script.cardLines(Script.Section("t", listOf("去年我们做了三件事。第一件是用户增长。", "", "短句")))
        assertEquals(listOf("去年我们做了三件事。", "短句"), l)
    }
}
