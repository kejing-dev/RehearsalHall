package io.embodit.rehearsalhall.platform.office

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import io.embodit.rehearsalhall.domain.SlideCheck
import io.embodit.spatialoffice.core.pptx.PptxDeck
import io.embodit.spatialoffice.core.pptx.Shape
import io.embodit.spatialoffice.core.pptx.ShapeKind
import io.embodit.spatialoffice.core.pptx.TextPara

/**
 * Draws one slide of a [PptxDeck] onto a canvas (started from Spatial Office's `Exporter.drawSlide`, extended for
 * rehearsal: drawn shapes with plain fill / outline, tables, groups, rotation, per-run bold / italic / colour / size).
 * What it cannot draw (charts, SmartArt, other graphic frames) becomes a light dashed frame; [SlideCheck] counts those
 * pages so the deck list can say "may look incomplete".
 */
class SlidePainter(private val deck: PptxDeck) {
    private val images = HashMap<String, Bitmap?>()

    fun draw(canvas: Canvas, index: Int, w: Float, h: Float) {
        val slide = deck.slides[index]
        canvas.drawColor(slide.backgroundRgb?.let { opaque(it) } ?: Color.WHITE)
        slide.backgroundImage?.let { image(it) }?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, w, h), BMP) }
        val sx = w / deck.widthEmu
        val sy = h / deck.heightEmu
        val ptPx = h / (deck.heightEmu / 12700f)
        for (sh in slide.shapes) shape(canvas, sh, sx, sy, ptPx)
    }

    private fun shape(canvas: Canvas, sh: Shape, sx: Float, sy: Float, ptPx: Float) {
        val r = RectF(sh.x * sx, sh.y * sy, (sh.x + sh.w) * sx, (sh.y + sh.h) * sy)
        canvas.save()
        if (sh.rotation != 0) canvas.rotate(sh.rotation.toFloat(), r.centerX(), r.centerY())
        when {
            sh.children != null -> sh.children!!.forEach { shape(canvas, it, sx, sy, ptPx) }
            sh.table != null -> table(canvas, sh, r, ptPx)
            sh.kind == ShapeKind.PICTURE -> sh.imagePart?.let { image(it) }?.let { canvas.drawBitmap(it, null, r, BMP) } ?: frame(canvas, r)
            sh.kind == ShapeKind.CHART -> { frame(canvas, r); chartGlyph(canvas, r) }
            else -> {
                val drawn = geometry(canvas, sh, r)
                val hasText = sh.paras.any { it.text.isNotBlank() }
                if (hasText) text(canvas, sh, r, ptPx)
                if (!drawn && !hasText && sh.kind == ShapeKind.OTHER) frame(canvas, r)
            }
        }
        canvas.restore()
    }

    /** Plain fill / outline of a preset shape. False when the shape has neither (nothing drawn). */
    private fun geometry(canvas: Canvas, sh: Shape, r: RectF): Boolean {
        if (sh.fillRgb == null && sh.lineRgb == null) return false
        val path = Path()
        when (sh.geom) {
            "ellipse" -> path.addOval(r, Path.Direction.CW)
            "roundRect" -> path.addRoundRect(r, r.height() * 0.16f, r.height() * 0.16f, Path.Direction.CW)
            "triangle" -> { path.moveTo(r.centerX(), r.top); path.lineTo(r.right, r.bottom); path.lineTo(r.left, r.bottom); path.close() }
            "line", "straightConnector1" -> { path.moveTo(r.left, r.top); path.lineTo(r.right, r.bottom) }
            "rightArrow" -> {
                val head = r.width() * 0.3f
                path.moveTo(r.left, r.top + r.height() * 0.25f); path.lineTo(r.right - head, r.top + r.height() * 0.25f)
                path.lineTo(r.right - head, r.top); path.lineTo(r.right, r.centerY()); path.lineTo(r.right - head, r.bottom)
                path.lineTo(r.right - head, r.bottom - r.height() * 0.25f); path.lineTo(r.left, r.bottom - r.height() * 0.25f); path.close()
            }
            else -> path.addRect(r, Path.Direction.CW)
        }
        sh.fillRgb?.let { canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = opaque(it) }) }
        sh.lineRgb?.let {
            val wpx = ((sh.lineWidthEmu ?: 12700L) / 12700f).coerceIn(0.75f, 12f) * 1.5f // pt -> px at ~1600 px wide
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = opaque(it); style = Paint.Style.STROKE; strokeWidth = wpx })
        }
        return true
    }

    private fun text(canvas: Canvas, sh: Shape, r: RectF, ptPx: Float) {
        if (r.width() < 4) return
        val baseSize = (sh.fontPt ?: if (sh.kind == ShapeKind.TITLE) 40f else 20f) * ptPx
        // A filled drawn shape: its text colour usually comes from the shape style (`p:style fontRef`, not read by the
        // core) and PowerPoint centres it; keep the file's colour only when it stands out against the fill.
        val onFill = sh.fillRgb != null && sh.geom != null
        val contrast = sh.fillRgb?.let { if (SlideCheck.luma(it) < 0.65f) Color.WHITE else Color.rgb(0x17, 0x18, 0x1C) }
        val baseColor =
            when {
                // black / unset = the theme's default text colour, not a choice: PowerPoint's shape style draws it light
                onFill && (sh.colorRgb == null || sh.colorRgb == 0 || kotlin.math.abs(SlideCheck.luma(sh.colorRgb!!) - SlideCheck.luma(sh.fillRgb!!)) < 0.35f) -> contrast!!
                sh.colorRgb != null -> opaque(sh.colorRgb!!)
                else -> contrast ?: Color.rgb(0x17, 0x18, 0x1C)
            }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = baseColor
            textSize = baseSize
            typeface = if (sh.bold || sh.kind == ShapeKind.TITLE) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val sb = SpannableStringBuilder()
        sh.paras.forEachIndexed { i, p -> if (i > 0) sb.append('\n'); para(sb, p, ptPx, keepColor = !onFill) }
        val a = sh.paras.firstOrNull()?.align ?: sh.align
        val align = when { a == 1 || (onFill && a == 0) -> Layout.Alignment.ALIGN_CENTER; a == 2 -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
        val inset = 0.05f * baseSize
        val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, tp, (r.width() - 2 * inset).toInt().coerceAtLeast(8)).setAlignment(align).build()
        canvas.save()
        canvas.clipRect(r.left, r.top, r.right, r.bottom + baseSize * 0.6f) // PowerPoint lets text spill a little; keep it near the box
        val centred = sh.kind == ShapeKind.TITLE || (sh.fillRgb != null && sh.geom != null)
        canvas.translate(r.left + inset, if (centred) r.top + ((r.height() - layout.height) / 2f).coerceAtLeast(0f) else r.top + inset)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun para(sb: SpannableStringBuilder, p: TextPara, ptPx: Float, keepColor: Boolean) {
        if (p.bullet) sb.append("    ".repeat(p.level)).append("• ")
        val runs = p.runs
        if (runs.isNullOrEmpty()) { sb.append(p.text); return }
        for (s in runs) {
            val start = sb.length
            sb.append(s.text)
            val end = sb.length
            if (end == start) continue
            if (s.bold && s.italic) sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            else if (s.bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            else if (s.italic) sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (s.underline) sb.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (s.strike) sb.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (keepColor) s.color?.let { sb.setSpan(ForegroundColorSpan(opaque(it)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            s.size?.let { sb.setSpan(AbsoluteSizeSpan((it / 2f * ptPx).toInt().coerceAtLeast(1)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
    }

    private fun table(canvas: Canvas, sh: Shape, r: RectF, ptPx: Float) {
        val rows = sh.table ?: return
        if (rows.isEmpty()) return
        val cols = rows.maxOf { it.size }.coerceAtLeast(1)
        val colW = sh.tableColW?.takeIf { it.size == cols }?.let { ws -> val t = ws.sum().toFloat().coerceAtLeast(1f); ws.map { it / t * r.width() } } ?: List(cols) { r.width() / cols }
        val rowH = sh.tableRowH?.takeIf { it.size == rows.size }?.let { hs -> val t = hs.sum().toFloat().coerceAtLeast(1f); hs.map { it / t * r.height() } } ?: List(rows.size) { r.height() / rows.size }
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x9A, 0xA0, 0xA8); style = Paint.Style.STROKE; strokeWidth = 1.5f }
        val head = Paint().apply { color = Color.rgb(0xE6, 0xEA, 0xF0) }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x17, 0x18, 0x1C); textSize = (sh.fontPt ?: 14f) * ptPx }
        var y = r.top
        rows.forEachIndexed { ri, row ->
            var x = r.left
            for (ci in 0 until cols) {
                val cell = RectF(x, y, x + colW[ci], y + rowH[ri])
                if (ri == 0) canvas.drawRect(cell, head)
                canvas.drawRect(cell, grid)
                val t = row.getOrNull(ci).orEmpty()
                if (t.isNotBlank() && cell.width() > 12) {
                    val l = StaticLayout.Builder.obtain(t, 0, t.length, tp, (cell.width() - 12).toInt()).build()
                    canvas.save(); canvas.clipRect(cell); canvas.translate(cell.left + 6, cell.top + 4); l.draw(canvas); canvas.restore()
                }
                x += colW[ci]
            }
            y += rowH[ri]
        }
    }

    private fun frame(canvas: Canvas, r: RectF) {
        canvas.drawRoundRect(r, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xF2, 0xF3, 0xF5) })
        canvas.drawRoundRect(r, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f) })
    }

    /** Four grey bars in the middle of a chart's frame: "a chart was here" (the real chart needs the PDF pictures). */
    private fun chartGlyph(canvas: Canvas, r: RectF) {
        val s = minOf(r.width(), r.height()) * 0.35f
        if (s < 16) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xC4, 0xC8, 0xCE) }
        val bw = s / 6f
        val base = r.centerY() + s / 2f
        floatArrayOf(0.45f, 0.7f, 0.55f, 1f).forEachIndexed { i, k ->
            val x = r.centerX() - s / 2f + i * bw * 1.6f
            canvas.drawRoundRect(RectF(x, base - s * k, x + bw, base), 4f, 4f, p)
        }
    }

    private fun image(part: String): Bitmap? =
        images.getOrPut(part) {
            val bytes = deck.partBytes(part) ?: return@getOrPut null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= 1600 || o.outHeight / (sample * 2) >= 1600) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }

    fun recycle() {
        images.values.forEach { it?.recycle() }
        images.clear()
    }

    private companion object {
        val BMP = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        fun opaque(rgb: Int) = 0xFF000000.toInt() or rgb
    }
}
