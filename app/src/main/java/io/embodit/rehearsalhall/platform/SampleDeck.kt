package io.embodit.rehearsalhall.platform

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import io.embodit.rehearsalhall.R

/**
 * The built-in deck "三分钟自我介绍 / A three-minute introduction" (design `deckModel.sample`): five simple pages drawn
 * here in the current language, plus the name board shown on the screen when talking without slides (M7).
 * Slides are 1600 x 900.
 */
object SampleDeck {
    const val PAGES = 5
    const val W = 1600
    const val H = 900

    private const val PAPER = 0xFFF7EFE2.toInt()
    private const val INK = 0xFF2E2230.toInt()
    private const val SOFT = 0xFF7A6470.toInt()
    private const val AMBER = 0xFFF2A64A.toInt()
    private const val VELVET = 0xFF8E2433.toInt()
    private const val TEAL = 0xFF6BA292.toInt()

    fun title(r: Resources): String = r.getString(R.string.sample_title)

    fun notes(r: Resources): List<String> = r.getStringArray(R.array.sample_notes).toList()

    fun page(r: Resources, i: Int): Bitmap {
        val titles = r.getStringArray(R.array.sample_page_titles)
        val bullets = r.getStringArray(R.array.sample_page_bullets)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(PAPER)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // a warm band on the left and the page number
        p.color = if (i == 0) VELVET else AMBER
        c.drawRect(0f, 0f, 36f, H.toFloat(), p)
        p.color = SOFT
        p.textSize = 34f
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        c.drawText("${i + 1} / $PAGES", W - 190f, H - 60f, p)
        if (i == 0) {
            p.color = VELVET
            c.drawCircle(W / 2f, 300f, 110f, p)
            p.color = PAPER
            c.drawCircle(W / 2f, 270f, 46f, p)
            c.drawOval(RectF(W / 2f - 80f, 330f, W / 2f + 80f, 420f), p)
            p.color = INK
            p.textSize = 92f
            p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            p.textAlign = Paint.Align.CENTER
            c.drawText(titles[0], W / 2f, 570f, p)
            p.textSize = 44f
            p.color = SOFT
            p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            c.drawText(bullets[0], W / 2f, 660f, p)
            p.textAlign = Paint.Align.LEFT
            return bmp
        }
        p.color = INK
        p.textSize = 84f
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        c.drawText(titles[i], 130f, 210f, p)
        p.color = AMBER
        c.drawRect(130f, 250f, 330f, 262f, p)
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        p.textSize = 52f
        val lines = bullets[i].split('|')
        lines.forEachIndexed { k, line ->
            val y = 400f + k * 120f
            p.color = if (k % 2 == 0) TEAL else AMBER
            c.drawCircle(150f, y - 18f, 14f, p)
            p.color = INK
            c.drawText(line, 200f, y, p)
        }
        // a small pictogram on the right
        p.color = TEAL
        c.drawRoundRect(RectF(1180f, 360f, 1460f, 640f), 40f, 40f, p)
        p.color = PAPER
        c.drawCircle(1320f, 470f, 60f, p)
        c.drawRect(1250f, 560f, 1390f, 590f, p)
        return bmp
    }

    /** M7: the screen shows the hall's name board. */
    fun nameBoard(r: Resources): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF2A1E2C.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = AMBER
        c.drawRect(560f, 330f, 1040f, 342f, p)
        p.textAlign = Paint.Align.CENTER
        p.color = 0xFFFFE7C2.toInt()
        p.textSize = 120f
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        c.drawText(r.getString(R.string.app_name), W / 2f, 520f, p)
        p.textSize = 48f
        p.color = 0xFFC9B39A.toInt()
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        c.drawText(r.getString(R.string.board_sub), W / 2f, 620f, p)
        return bmp
    }
}
