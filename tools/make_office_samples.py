"""Test decks for I1 (open PPT / Word in the hall). Needs python-pptx + python-docx (dev only, not shipped).

    python tools/make_office_samples.py .scratch/office

Writes:
  talk.pptx   6 slides 16:9: title, bullets, drawn shapes, table, chart (-> "may be incomplete"), picture; notes on 1-5
  talk.docx   a script with a title, three H1 sections (one with H2 + a table) and a page break
  plain.docx  no headings: long paragraphs, cut into ~600-character parts
  talk.pdf    6 plain pages ("PDF n"): the deck exported as PDF; short.pdf = 5 pages (page-count mismatch)
"""
import io
import struct
import sys
import zlib
from pathlib import Path

from docx import Document
from docx.enum.text import WD_BREAK
from pptx import Presentation
from pptx.chart.data import CategoryChartData
from pptx.dml.color import RGBColor
from pptx.enum.chart import XL_CHART_TYPE
from pptx.enum.shapes import MSO_SHAPE
from pptx.util import Emu, Inches, Pt


def png(w, h, rgb):
    raw = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


def pptx(out: Path):
    p = Presentation()
    p.slide_width, p.slide_height = Inches(13.333), Inches(7.5)
    s = p.slides.add_slide(p.slide_layouts[0])
    s.shapes.title.text = "2026 年产品发布"
    s.placeholders[1].text = "排练用示例 · 6 页"
    s.notes_slide.notes_text_frame.text = "开场：先自我介绍，再用一个用户故事引出今天的主题。"

    s = p.slides.add_slide(p.slide_layouts[1])
    s.shapes.title.text = "今天讲三件事"
    tf = s.placeholders[1].text_frame
    tf.text = "去年做了什么"
    for t, lvl in (("用户增长 3 倍", 1), ("今年的计划", 0), ("需要大家支持的地方", 0)):
        para = tf.add_paragraph(); para.text = t; para.level = lvl
    s.notes_slide.notes_text_frame.text = "三件事各讲两分钟，别在第一件上停太久。"

    s = p.slides.add_slide(p.slide_layouts[5])
    s.shapes.title.text = "流程：从想法到上线"
    x = Inches(0.8)
    for i, (name, col) in enumerate((("想法", (0x3D, 0x6B, 0xD9)), ("原型", (0x2E, 0x9E, 0x6B)), ("上线", (0xE0, 0x8A, 0x1E)))):
        box = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, x + Inches(i * 4.1), Inches(3), Inches(3.2), Inches(1.6))
        box.fill.solid(); box.fill.fore_color.rgb = RGBColor(*col)
        box.text_frame.text = name
        box.text_frame.paragraphs[0].runs[0].font.size = Pt(32)
        if i < 2:
            arrow = s.shapes.add_shape(MSO_SHAPE.RIGHT_ARROW, x + Inches(i * 4.1 + 3.3), Inches(3.5), Inches(0.7), Inches(0.6))
            arrow.fill.solid(); arrow.fill.fore_color.rgb = RGBColor(0x9A, 0xA0, 0xA8)
    s.notes_slide.notes_text_frame.text = "强调原型阶段只用了两周。"

    s = p.slides.add_slide(p.slide_layouts[5])
    s.shapes.title.text = "关键数字"
    t = s.shapes.add_table(3, 3, Inches(1.5), Inches(2.2), Inches(10), Inches(2.4)).table
    for r, row in enumerate((("指标", "2025", "2026 目标"), ("月活", "120 万", "300 万"), ("留存", "38%", "45%"))):
        for c, v in enumerate(row):
            t.cell(r, c).text = v
    s.notes_slide.notes_text_frame.text = "留存是重点，月活只是顺带。"

    s = p.slides.add_slide(p.slide_layouts[5])
    s.shapes.title.text = "收入走势"
    cd = CategoryChartData(); cd.categories = ["Q1", "Q2", "Q3", "Q4"]; cd.add_series("收入", (1.2, 1.8, 2.6, 3.2))
    s.shapes.add_chart(XL_CHART_TYPE.COLUMN_CLUSTERED, Inches(1.5), Inches(1.8), Inches(10), Inches(5), cd)
    s.notes_slide.notes_text_frame.text = "Q4 有一次性收入，要说明。"

    s = p.slides.add_slide(p.slide_layouts[5])
    s.shapes.title.text = "谢谢"
    s.shapes.add_picture(io.BytesIO(png(320, 180, (0x3D, 0x6B, 0xD9))), Inches(4.2), Inches(2.5), Inches(4.8), Inches(2.7))
    p.save(out / "talk.pptx")


def docx(out: Path):
    d = Document()
    d.add_heading("年度分享讲稿", 0)
    d.add_paragraph("各位同事，大家好。今天占用大家十分钟，聊聊这一年。")
    d.add_heading("一、我们做了什么", 1)
    d.add_paragraph("去年我们把核心流程重做了一遍。上线后投诉少了一半，这是大家一起的结果。")
    d.add_heading("细节", 2)
    d.add_paragraph("最难的是迁移老数据，花了整整一个月。")
    t = d.add_table(rows=2, cols=2)
    for r, row in enumerate((("指标", "变化"), ("投诉", "-52%"))):
        for c, v in enumerate(row):
            t.cell(r, c).text = v
    d.add_heading("二、今年的计划", 1)
    d.add_paragraph("今年只做三件事：稳定、提速、出海。每件事都有明确的负责人。")
    d.add_paragraph().add_run().add_break(WD_BREAK.PAGE)
    d.add_heading("三、需要大家支持的", 1)
    d.add_paragraph("希望各组每月给一次反馈。谢谢大家。")
    d.save(out / "talk.docx")

    d = Document()
    for i in range(10):
        d.add_paragraph(f"第 {i + 1} 段。" + "这是一段没有标题的讲稿，用来检查按长度自动分段。" * 6)
    d.save(out / "plain.docx")


def pdf(path: Path, pages: int):
    """A plain 16:9 PDF, one big "PDF n" per page (stands in for the deck exported as PDF; no dependencies)."""
    objs = ["<< /Type /Catalog /Pages 2 0 R >>", None, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>"]
    kids = []
    for i in range(pages):
        stream = f"0.24 0.42 0.85 rg 0 0 960 540 re f 1 1 1 rg BT /F1 96 Tf 300 230 Td (PDF {i + 1}) Tj ET".encode()
        objs.append(f"<< /Length {len(stream)} >>\nstream\n" + stream.decode() + "\nendstream")
        content = len(objs)
        objs.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 960 540] /Resources << /Font << /F1 3 0 R >> >> /Contents {content} 0 R >>")
        kids.append(len(objs))
    objs[1] = "<< /Type /Pages /Kids [" + " ".join(f"{k} 0 R" for k in kids) + f"] /Count {pages} >>"
    out = bytearray(b"%PDF-1.4\n")
    offs = []
    for n, o in enumerate(objs, 1):
        offs.append(len(out))
        out += f"{n} 0 obj\n{o}\nendobj\n".encode()
    xref = len(out)
    out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n".encode() + b"".join(f"{o:010d} 00000 n \n".encode() for o in offs)
    out += f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    path.write_bytes(bytes(out))


if __name__ == "__main__":
    out = Path(sys.argv[1] if len(sys.argv) > 1 else ".scratch/office")
    out.mkdir(parents=True, exist_ok=True)
    pptx(out)
    docx(out)
    pdf(out / "talk.pdf", 6)
    pdf(out / "short.pdf", 5)
    print("wrote", sorted(p.name for p in out.iterdir()))
