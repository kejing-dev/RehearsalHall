package io.embodit.rehearsalhall.platform.office

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import io.embodit.rehearsalhall.domain.Script

/**
 * The projection-screen picture of one section of a .docx talk script: a calm 16:9 card with the section heading and
 * the first sentence of its first paragraphs (the full text goes to the lectern as notes).
 */
object ScriptCard {
    fun draw(canvas: Canvas, s: Script.Section, index: Int, total: Int, w: Float, h: Float) {
        canvas.drawColor(Color.rgb(0xF7, 0xF5, 0xF0))
        val pad = w * 0.07f
        canvas.drawRect(RectF(0f, 0f, w * 0.012f, h), Paint().apply { color = Color.rgb(0x3D, 0x6B, 0xD9) })
        val num = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x8A, 0x8F, 0x98); textSize = h * 0.04f }
        canvas.drawText("${index + 1} / $total", pad, pad, num)
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x17, 0x18, 0x1C); textSize = h * 0.085f; typeface = Typeface.DEFAULT_BOLD }
        val tl = StaticLayout.Builder.obtain(s.title, 0, s.title.length, title, (w - 2 * pad).toInt()).setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build()
        canvas.save(); canvas.translate(pad, pad + h * 0.05f); tl.draw(canvas); canvas.restore()
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x3A, 0x3D, 0x44); textSize = h * 0.045f }
        var y = pad + h * 0.05f + tl.height + h * 0.06f
        for (line in Script.cardLines(s)) {
            val text = "•  $line"
            val l = StaticLayout.Builder.obtain(text, 0, text.length, body, (w - 2 * pad).toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
            if (y + l.height > h - pad * 0.6f) break
            canvas.save(); canvas.translate(pad, y); l.draw(canvas); canvas.restore()
            y += l.height + h * 0.02f
        }
    }
}
