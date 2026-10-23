package io.embodit.rehearsalhall.ui.hall.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pico.spatial.core.math.Vector2
import io.embodit.rehearsalhall.domain.HallLayout
import io.embodit.rehearsalhall.domain.Quat4
import io.embodit.rehearsalhall.domain.Vec3
import io.embodit.rehearsalhall.ui.theme.HallColors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The flat cartoon look (design `audienceStyle.material`, `hall.style`, deviation: no ShaderGraph). Every colour of the
 * scene is a cell of one palette texture; each cell holds a lit block and a shade block side by side. A vertex's U slides
 * between the two by how much its normal faces the key light, so a triangle that crosses the terminator shows a crisp
 * two-tone edge (toon shading baked into UVs, sampled by an Unlit material). One texture, three materials (house, stage,
 * glow) that differ only by tint, so the house lights dim by changing one colour.
 */
object Palette {
    const val CELL = 32
    const val PER_ROW = 8
    private const val ROWS = 8
    const val W = CELL * 2 * PER_ROW // 512: 8 colours x (lit + shade) x 32 px
    const val H = CELL * ROWS

    /** Light direction, hall-local (from above and from the stage side) and in head / body local frames. */
    val LIGHT = Vec3(0.25f, 0.85f, 0.45f).normalized()

    private val colors = ArrayList<Pair<Color, Color>>()
    private val index = HashMap<String, Int>()

    private fun add(name: String, c: Color, shade: Color? = null): Int {
        val i = colors.size
        colors += c to (shade ?: shadeOf(c))
        index[name] = i
        return i
    }

    /** Toon shade: darker and shifted a little towards plum. */
    private fun shadeOf(c: Color): Color = Color((c.red * 0.70f + 0.03f).coerceIn(0f, 1f), (c.green * 0.64f + 0.01f).coerceIn(0f, 1f), (c.blue * 0.74f + 0.05f).coerceIn(0f, 1f), 1f)

    val SKIN: List<Int> = HallColors.Skin.mapIndexed { i, c -> add("skin$i", c) }
    val HAIR: List<Int> = HallColors.Hair.mapIndexed { i, c -> add("hair$i", c) }
    val CLOTHES: List<Int> = HallColors.Clothes.mapIndexed { i, c -> add("clothes$i", c) }
    val PANTS = add("pants", Color(0xFF3B3446))
    val COLLAR = add("collar", Color(0xFFF3E9DC))
    val VELVET = add("velvet", HallColors.VelvetSeat)
    val SEAT_FRAME = add("seatFrame", Color(0xFF4A2A2E))
    val STAGE_WOOD = add("stageWood", HallColors.StageFloor)
    val LECTERN = add("lectern", HallColors.StageWood)
    val STEP_A = add("stepA", HallColors.StepA)
    val STEP_B = add("stepB", HallColors.StepB)
    val WALL_A = add("wallA", Color(0xFF8C5A3C))
    val WALL_B = add("wallB", Color(0xFF7A4C33))
    val CEILING = add("ceiling", Color(0xFF2E2234))
    val CURTAIN_A = add("curtainA", Color(0xFF5A1E2C))
    val CURTAIN_B = add("curtainB", Color(0xFF4A1824))
    val SCREEN_FRAME = add("screenFrame", Color(0xFF1E1820))
    val BACKSTAGE = add("backstage", Color(0xFF241A26))
    val EYE = add("eye", Color(0xFF2B2220), Color(0xFF2B2220))
    val WHITE = add("white", Color(0xFFFFFFFF), Color(0xFFFFFFFF))
    val MOUTH = add("mouth", Color(0xFF8A3A44), Color(0xFF8A3A44))
    val BLUSH = add("blush", Color(0xFFF2A0A0), Color(0xFFF2A0A0))
    val GLASSES = add("glasses", Color(0xFF3A3036), Color(0xFF3A3036))
    // glow (drawn with the untinted material)
    val AMBER = add("amber", HallColors.SpotAmber, HallColors.SpotAmber)
    val LAMP = add("lamp", Color(0xFFFFE9C8), Color(0xFFFFE9C8))
    val EXIT = add("exit", HallColors.ExitGreen, HallColors.ExitGreen)
    val PHONE = add("phone", Color(0xFFCFEFFF), Color(0xFFCFEFFF))
    val TINT: List<Int> = HallColors.SeatScale.mapIndexed { i, c -> add("tint$i", c, c) }

    fun atlas(): Bitmap {
        val bmp = Bitmap.createBitmap(CELL * 2 * PER_ROW, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint()
        colors.forEachIndexed { i, (lit, shade) ->
            val x = (i % PER_ROW) * CELL * 2f
            val y = (i / PER_ROW) * CELL.toFloat()
            p.color = lit.toArgb()
            canvas.drawRect(x, y, x + CELL, y + CELL, p)
            p.color = shade.toArgb()
            canvas.drawRect(x + CELL, y, x + CELL * 2, y + CELL, p)
        }
        return bmp
    }

    /** UV of colour [c] for a light factor [lit] (1 = fully lit, 0 = shade). V = 0 at the top of the bitmap. */
    fun uv(c: Int, lit: Float): Vector2 {
        val w = (CELL * 2 * PER_ROW).toFloat()
        val x = (c % PER_ROW) * CELL * 2f + CELL / 2f + (1f - lit.coerceIn(0f, 1f)) * CELL
        val y = (c / PER_ROW) * CELL + CELL / 2f
        return Vector2(x / w, y / H)
    }

    fun litOf(n: Vec3): Float = (0.5f + (n.dot(LIGHT) - 0.15f) * 2.5f).coerceIn(0f, 1f)
}

/** Toon geometry on top of [MeshBuilder]: every primitive takes a colour and a local transform (rotation, then offset). */
internal class Toon(capacity: Int = 256) {
    val b = MeshBuilder(capacity)
    var rot: Quat4 = Quat4.IDENTITY
    var off: Vec3 = Vec3.ZERO

    fun at(rotation: Quat4, offset: Vec3, block: Toon.() -> Unit) {
        val r0 = rot
        val o0 = off
        off = o0 + r0.rotate(offset)
        rot = r0 * rotation
        block()
        rot = r0
        off = o0
    }

    private fun v(p: Vec3, n: Vec3, c: Int): Int {
        val wn = rot.rotate(n).normalized()
        return b.vertex(off + rot.rotate(p), wn, Palette.uv(c, Palette.litOf(wn)))
    }

    fun quad(a: Vec3, bb: Vec3, cc: Vec3, d: Vec3, c: Int, n: Vec3? = null) {
        val nn = n ?: (bb - a).cross(d - a).normalized()
        b.quad(v(a, nn, c), v(bb, nn, c), v(cc, nn, c), v(d, nn, c))
    }

    fun tri(a: Vec3, bb: Vec3, cc: Vec3, c: Int) {
        val n = (bb - a).cross(cc - a).normalized()
        b.tri(v(a, n, c), v(bb, n, c), v(cc, n, c))
    }

    /** Axis-aligned box (before the transform) from [min] to [max]; outward faces. */
    fun box(min: Vec3, max: Vec3, c: Int) {
        val (x0, y0, z0) = Triple(min.x, min.y, min.z)
        val (x1, y1, z1) = Triple(max.x, max.y, max.z)
        quad(Vec3(x0, y0, z1), Vec3(x1, y0, z1), Vec3(x1, y1, z1), Vec3(x0, y1, z1), c, Vec3(0f, 0f, 1f))
        quad(Vec3(x1, y0, z0), Vec3(x0, y0, z0), Vec3(x0, y1, z0), Vec3(x1, y1, z0), c, Vec3(0f, 0f, -1f))
        quad(Vec3(x0, y1, z1), Vec3(x1, y1, z1), Vec3(x1, y1, z0), Vec3(x0, y1, z0), c, Vec3(0f, 1f, 0f))
        quad(Vec3(x0, y0, z0), Vec3(x1, y0, z0), Vec3(x1, y0, z1), Vec3(x0, y0, z1), c, Vec3(0f, -1f, 0f))
        quad(Vec3(x1, y0, z1), Vec3(x1, y0, z0), Vec3(x1, y1, z0), Vec3(x1, y1, z1), c, Vec3(1f, 0f, 0f))
        quad(Vec3(x0, y0, z0), Vec3(x0, y0, z1), Vec3(x0, y1, z1), Vec3(x0, y1, z0), c, Vec3(-1f, 0f, 0f))
    }

    /** Ellipsoid (smooth normals) around [center] with radii [r]; [keep] filters by the unit direction of a facet. */
    fun ellipsoid(center: Vec3, r: Vec3, c: Int, seg: Int = 12, rings: Int = 8, keep: ((Vec3) -> Boolean)? = null) {
        fun dir(ri: Int, si: Int): Vec3 {
            val th = PI * ri / rings
            val ph = 2 * PI * si / seg
            return Vec3((sin(th) * sin(ph)).toFloat(), cos(th).toFloat(), (sin(th) * cos(ph)).toFloat())
        }
        for (ri in 0 until rings) for (si in 0 until seg) {
            val d00 = dir(ri, si)
            val d01 = dir(ri, si + 1)
            val d11 = dir(ri + 1, si + 1)
            val d10 = dir(ri + 1, si)
            if (keep != null && !keep((d00 + d01 + d11 + d10).normalized())) continue
            fun p(d: Vec3) = center + Vec3(d.x * r.x, d.y * r.y, d.z * r.z)
            fun n(d: Vec3) = Vec3(d.x / r.x, d.y / r.y, d.z / r.z).normalized()
            b.quad(v(p(d00), n(d00), c), v(p(d10), n(d10), c), v(p(d11), n(d11), c), v(p(d01), n(d01), c))
        }
    }

    fun sphere(center: Vec3, radius: Float, c: Int, seg: Int = 10, rings: Int = 6) = ellipsoid(center, Vec3(radius, radius, radius), c, seg, rings)

    /** Cylinder between two points (with end caps). */
    fun cylinder(from: Vec3, to: Vec3, radius: Float, c: Int, seg: Int = 8) {
        val axis = (to - from).normalized()
        val helper = if (kotlin.math.abs(axis.y) < 0.9f) Vec3.UP else Vec3.RIGHT
        val u = axis.cross(helper).normalized()
        val w = axis.cross(u).normalized()
        for (i in 0 until seg) {
            val a0 = 2 * PI * i / seg
            val a1 = 2 * PI * (i + 1) / seg
            val n0 = u * cos(a0).toFloat() + w * sin(a0).toFloat()
            val n1 = u * cos(a1).toFloat() + w * sin(a1).toFloat()
            b.quad(v(from + n0 * radius, n0, c), v(from + n1 * radius, n1, c), v(to + n1 * radius, n1, c), v(to + n0 * radius, n0, c))
            b.tri(v(to, axis, c), v(to + n0 * radius, axis, c), v(to + n1 * radius, axis, c))
            b.tri(v(from, -axis, c), v(from + n1 * radius, -axis, c), v(from + n0 * radius, -axis, c))
        }
    }

    /** Flat ellipse in the plane through [center] with normal [n] (front face only, plus back so it shows both ways). */
    fun ellipse(center: Vec3, n: Vec3, rx: Float, ry: Float, c: Int, seg: Int = 12) {
        val nn = n.normalized()
        val up = if (kotlin.math.abs(nn.y) > 0.95f) Vec3(0f, 0f, -1f) else Vec3.UP
        val right = up.cross(nn).normalized()
        val upv = nn.cross(right).normalized()
        val ci = v(center, nn, c)
        val ring = (0..seg).map { i ->
            val a = 2 * PI * i / seg
            v(center + right * (rx * cos(a).toFloat()) + upv * (ry * sin(a).toFloat()), nn, c)
        }
        for (i in 0 until seg) b.tri(ci, ring[i], ring[i + 1])
    }

    /**
     * A 2D stroke on the front of a sphere of radius [r] (head-local, +Z front): the points (x, y) are lifted onto the
     * sphere at [lift] above the skin. Used for eyes, mouths and blush, which then follow the head's curve.
     */
    fun faceStroke(points: List<Pair<Float, Float>>, width: Float, r: Float, lift: Float, c: Int) {
        if (points.size < 2) return
        fun onSphere(x: Float, y: Float): Vec3 {
            val z = sqrt(max(0f, r * r - x * x - y * y))
            val n = Vec3(x, y, z).normalized()
            return n * (r + lift)
        }
        for (i in 0 until points.size - 1) {
            val (x0, y0) = points[i]
            val (x1, y1) = points[i + 1]
            val dx = x1 - x0
            val dy = y1 - y0
            val l = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-5f)
            val nx = -dy / l * width / 2
            val ny = dx / l * width / 2
            val a = onSphere(x0 + nx, y0 + ny)
            val bb = onSphere(x0 - nx, y0 - ny)
            val cc = onSphere(x1 - nx, y1 - ny)
            val d = onSphere(x1 + nx, y1 + ny)
            val n = ((a + cc) * 0.5f).normalized()
            b.quad(v(bb, n, c), v(cc, n, c), v(d, n, c), v(a, n, c))
        }
    }

    /** A filled ellipse lifted onto the head sphere. */
    fun faceDot(x: Float, y: Float, rx: Float, ry: Float, r: Float, lift: Float, c: Int, seg: Int = 12) {
        fun onSphere(px: Float, py: Float): Vec3 {
            val z = sqrt(max(0f, r * r - px * px - py * py))
            return Vec3(px, py, z).normalized() * (r + lift)
        }
        val center = onSphere(x, y)
        val n = center.normalized()
        val ci = v(center, n, c)
        val ring = (0..seg).map { i ->
            val a = 2 * PI * i / seg
            v(onSphere(x + rx * cos(a).toFloat(), y + ry * sin(a).toFloat()), n, c)
        }
        for (i in 0 until seg) b.tri(ci, ring[i], ring[i + 1])
    }

    /** Arc points (x, y) around ([cx], [cy]) from [a0] to [a1] degrees. */
    fun arc(cx: Float, cy: Float, rad: Float, a0: Float, a1: Float, n: Int = 8): List<Pair<Float, Float>> =
        (0..n).map { i ->
            val a = Math.toRadians((a0 + (a1 - a0) * i / n).toDouble())
            (cx + rad * cos(a).toFloat()) to (cy + rad * sin(a).toFloat())
        }

    // ---- hall pieces (hall-local, yaw in degrees + to the right of the audience direction -Z)

    private fun polar(r: Float, yawDeg: Float, y: Float): Vec3 {
        val a = Math.toRadians(yawDeg.toDouble())
        return Vec3((r * sin(a)).toFloat(), y, (-r * cos(a)).toFloat())
    }

    /** Horizontal ring sector (floor or ceiling), facing up or down. */
    fun sector(r0: Float, r1: Float, a0: Float, a1: Float, y: Float, c: Int, down: Boolean = false, seg: Int = 24) {
        for (i in 0 until seg) {
            val ya = a0 + (a1 - a0) * i / seg
            val yb = a0 + (a1 - a0) * (i + 1) / seg
            val p0 = polar(r0, ya, y)
            val p1 = polar(r1, ya, y)
            val p2 = polar(r1, yb, y)
            val p3 = polar(r0, yb, y)
            if (!down) quad(p0, p3, p2, p1, c, Vec3.UP) else quad(p0, p1, p2, p3, c, Vec3(0f, -1f, 0f))
        }
    }

    /** Vertical arc wall at radius [r] between [y0] and [y1], facing the centre (or outwards). */
    fun arcWall(r: Float, a0: Float, a1: Float, y0: Float, y1: Float, c: Int, inward: Boolean = true, seg: Int = 24, stripes: Int? = null) {
        for (i in 0 until seg) {
            val ya = a0 + (a1 - a0) * i / seg
            val yb = a0 + (a1 - a0) * (i + 1) / seg
            val n = polar(1f, (ya + yb) / 2, 0f).let { if (inward) -it else it }
            val col = if (stripes != null && i % 2 == 1) stripes else c
            val a = polar(r, ya, y0)
            val bb = polar(r, yb, y0)
            val cc = polar(r, yb, y1)
            val d = polar(r, ya, y1)
            if (inward) quad(bb, a, d, cc, col, n) else quad(a, bb, cc, d, col, n)
        }
    }

    /** Vertical straight wall along the ray at [yawDeg] from radius [r0] to [r1]; [faceRight] picks the visible side. */
    fun rayWall(yawDeg: Float, r0: Float, r1: Float, y0: Float, y1: Float, c: Int, stripe: Int, faceRight: Boolean, stripeM: Float = 0.6f) {
        val n = polar(1f, yawDeg + if (faceRight) 90f else -90f, 0f)
        var r = r0
        var k = 0
        while (r < r1 - 1e-3f) {
            val rn = minOf(r1, r + stripeM)
            val a = polar(r, yawDeg, y0)
            val bb = polar(rn, yawDeg, y0)
            val cc = polar(rn, yawDeg, y1)
            val d = polar(r, yawDeg, y1)
            val col = if (k % 2 == 0) c else stripe
            if (faceRight) quad(bb, a, d, cc, col, n) else quad(a, bb, cc, d, col, n)
            r = rn
            k++
        }
    }

    fun build(name: String) = b.build(name)
}

/** Face expressions (design `audienceStyle.parts`: 8 cells; v1 builds the five the behaviours use). */
enum class Face { NEUTRAL, BLINK, SMILE, PHONE, CLAP }

/** Programmatic cartoon figures and the hall shell (design `entities3d`, `audienceStyle`). */
internal object Figures {
    const val HEAD_R = 0.17f
    const val SHOULDER_Y = 0.93f

    /** Head-local (+Z front, origin = head centre): skull, ears, hair style, glasses. Unique per person. */
    fun head(skin: Int, hair: Int, style: Int, glasses: Boolean, beanie: Int): Toon {
        val t = Toon(900)
        val r = HEAD_R
        val keepSkull: ((Vec3) -> Boolean)? = null
        t.ellipsoid(Vec3.ZERO, Vec3(r, r * 0.97f, r), skin, 14, 10, keepSkull)
        t.sphere(Vec3(r * 0.97f, -0.01f, -0.01f), 0.035f, skin, 6, 4)
        t.sphere(Vec3(-r * 0.97f, -0.01f, -0.01f), 0.035f, skin, 6, 4)
        val cap: (Vec3) -> Boolean = { d -> d.y > 0.18f || (d.z < -0.25f && d.y > -0.35f) }
        when (style) {
            0 -> t.ellipsoid(Vec3(0f, 0.005f, -0.004f), Vec3(r * 1.07f, r * 1.05f, r * 1.07f), hair, 14, 10, cap)
            1 -> t.ellipsoid(Vec3(0f, 0f, -0.006f), Vec3(r * 1.11f, r * 1.07f, r * 1.10f), hair, 14, 10) { d -> d.y > -0.5f && !(d.z > 0.3f && d.y < 0.42f) }
            2 -> {
                t.ellipsoid(Vec3(0f, 0.005f, -0.004f), Vec3(r * 1.06f, r * 1.04f, r * 1.06f), hair, 14, 10, cap)
                t.sphere(Vec3(0f, r * 1.02f, -r * 0.45f), 0.072f, hair, 10, 6)
            }
            3 -> {
                t.ellipsoid(Vec3(0f, 0.005f, -0.004f), Vec3(r * 1.06f, r * 1.04f, r * 1.06f), hair, 14, 10, cap)
                t.ellipsoid(Vec3(0f, -0.02f, -r * 1.12f), Vec3(0.05f, 0.12f, 0.05f), hair, 8, 6)
            }
            4 -> {
                t.ellipsoid(Vec3(0f, 0.005f, -0.004f), Vec3(r * 1.03f, r * 1.02f, r * 1.03f), hair, 14, 10, cap)
                for (k in 0 until 9) {
                    val a = 2 * PI * k / 9
                    val ring = if (k % 2 == 0) 0.55f else 0.78f
                    val d = Vec3((ring * sin(a)).toFloat(), sqrt(1 - ring * ring), (ring * cos(a)).toFloat() * 0.85f - 0.12f).normalized()
                    if (d.z > 0.55f && d.y < 0.75f) continue
                    t.sphere(d * (r * 1.02f), 0.06f, hair, 8, 5)
                }
            }
            else -> {
                t.ellipsoid(Vec3(0f, 0.01f, -0.004f), Vec3(r * 1.1f, r * 1.1f, r * 1.1f), beanie, 14, 10) { d -> d.y > 0.32f || (d.z < -0.3f && d.y > -0.1f) }
                t.cylinder(Vec3(0f, r * 0.38f, -0.004f), Vec3(0f, r * 0.52f, -0.004f), r * 1.07f, beanie, 14)
                t.sphere(Vec3(0f, r * 1.14f, -0.01f), 0.045f, Palette.COLLAR, 8, 5)
            }
        }
        if (glasses) {
            for (sx in listOf(-1f, 1f)) {
                val pts = t.arc(sx * 0.062f, 0.025f, 0.036f, 0f, 360f, 14)
                t.faceStroke(pts, 0.008f, r, 0.016f, Palette.GLASSES)
            }
            t.faceStroke(listOf(-0.026f to 0.03f, 0.026f to 0.03f), 0.007f, r, 0.016f, Palette.GLASSES)
        }
        return t
    }

    /** Face features (shared by everyone): eyes, mouth and blush lifted onto the head sphere. */
    fun face(kind: Face): Toon {
        val t = Toon(300)
        val r = HEAD_R
        val lift = 0.004f
        val ex = 0.062f
        val ey = 0.025f
        for (sx in listOf(-1f, 1f)) {
            when (kind) {
                Face.NEUTRAL -> {
                    t.faceDot(sx * ex, ey, 0.015f, 0.021f, r, lift, Palette.EYE)
                    t.faceDot(sx * ex + 0.006f, ey + 0.008f, 0.0055f, 0.0055f, r, lift + 0.002f, Palette.WHITE, 8)
                }
                Face.BLINK -> t.faceStroke(t.arc(sx * ex, ey + 0.012f, 0.016f, 200f, 340f, 6), 0.006f, r, lift, Palette.EYE)
                Face.SMILE, Face.CLAP -> t.faceStroke(t.arc(sx * ex, ey - 0.006f, 0.016f, 20f, 160f, 6), 0.007f, r, lift, Palette.EYE)
                Face.PHONE -> {
                    t.faceDot(sx * ex, ey - 0.012f, 0.014f, 0.012f, r, lift, Palette.EYE)
                }
            }
            t.faceDot(sx * 0.098f, -0.022f, 0.024f, 0.014f, r, lift - 0.001f, Palette.BLUSH)
        }
        when (kind) {
            Face.NEUTRAL, Face.BLINK -> t.faceStroke(t.arc(0f, -0.028f, 0.03f, 220f, 320f, 8), 0.007f, r, lift, Palette.MOUTH)
            Face.SMILE -> t.faceStroke(t.arc(0f, -0.022f, 0.04f, 210f, 330f, 10), 0.01f, r, lift, Palette.MOUTH)
            Face.PHONE -> t.faceStroke(listOf(-0.016f to -0.06f, 0.016f to -0.06f), 0.006f, r, lift, Palette.MOUTH)
            Face.CLAP -> {
                // open "D" mouth
                val pts = t.arc(0f, -0.042f, 0.032f, 180f, 360f, 10)
                for (i in 0 until pts.size - 1) t.faceStroke(listOf(0f to -0.042f, pts[i], pts[i + 1]), 0.01f, r, lift, Palette.MOUTH)
                t.faceStroke(listOf(-0.032f to -0.042f, 0.032f to -0.042f), 0.012f, r, lift, Palette.MOUTH)
            }
        }
        return t
    }

    /** Both arms, pivot at the shoulder line (seat-local frame shifted to (0, SHOULDER_Y, 0)); hands rest together on the lap. */
    fun arms(clothes: Int, skin: Int): Toon {
        val t = Toon(400)
        for (sx in listOf(-1f, 1f)) {
            val shoulder = Vec3(sx * 0.19f, 0f, 0.01f)
            val elbow = Vec3(sx * 0.22f, -0.30f, 0.06f)
            val hand = Vec3(sx * 0.07f, -0.34f, 0.30f)
            t.cylinder(shoulder, elbow, 0.05f, clothes, 8)
            t.sphere(elbow, 0.05f, clothes, 8, 5)
            t.cylinder(elbow, hand, 0.045f, clothes, 8)
            t.sphere(hand, 0.052f, skin, 8, 5)
            t.sphere(shoulder, 0.055f, clothes, 8, 5)
        }
        return t
    }

    /** The phone held in both hands (arms-local), glowing face up towards the eyes. */
    fun phone(): Toon {
        val t = Toon(24)
        t.at(Quat4.axisAngle(Vec3.RIGHT, Math.toRadians(-40.0).toFloat()), Vec3(0f, -0.29f, 0.30f)) {
            box(Vec3(-0.04f, -0.003f, -0.065f), Vec3(0.04f, 0.006f, 0.065f), Palette.PHONE)
        }
        return t
    }

    /** One seated torso with legs and neck (seat-local: origin on the row floor, +Z towards the stage). */
    fun torso(t: Toon, clothes: Int, skin: Int) {
        val y0 = 0.47f
        val y1 = 0.98f
        val bw = 0.21f
        val tw = 0.17f
        val d = 0.12f
        val bl = Vec3(-bw, y0, d); val br = Vec3(bw, y0, d); val tr = Vec3(tw, y1, d * 0.9f); val tl = Vec3(-tw, y1, d * 0.9f)
        val blb = Vec3(-bw, y0, -d); val brb = Vec3(bw, y0, -d); val trb = Vec3(tw, y1, -d * 0.9f); val tlb = Vec3(-tw, y1, -d * 0.9f)
        t.quad(bl, br, tr, tl, clothes)
        t.quad(brb, blb, tlb, trb, clothes)
        t.quad(br, brb, trb, tr, clothes)
        t.quad(blb, bl, tl, tlb, clothes)
        t.quad(tl, tr, trb, tlb, clothes)
        // rounded shoulders
        t.cylinder(Vec3(-tw, y1 - 0.03f, 0f), Vec3(tw, y1 - 0.03f, 0f), 0.06f, clothes, 8)
        // collar V
        t.tri(Vec3(-0.06f, y1 + 0.001f, d * 0.9f + 0.003f), Vec3(0f, y1 - 0.09f, d * 0.95f + 0.004f), Vec3(0.06f, y1 + 0.001f, d * 0.9f + 0.003f), Palette.COLLAR)
        t.cylinder(Vec3(0f, y1 - 0.02f, 0f), Vec3(0f, y1 + 0.08f, 0f), 0.05f, skin, 8)
        // lap and shins
        t.box(Vec3(-0.19f, 0.42f, -0.05f), Vec3(0.19f, 0.54f, 0.36f), Palette.PANTS)
        t.box(Vec3(-0.18f, 0.0f, 0.30f), Vec3(0.18f, 0.44f, 0.40f), Palette.PANTS)
    }

    /** One theatre seat (seat-local), rounded back top. */
    fun seat(t: Toon) {
        t.box(Vec3(-0.25f, 0.36f, -0.22f), Vec3(0.25f, 0.46f, 0.24f), Palette.VELVET)
        t.box(Vec3(-0.26f, 0.40f, -0.32f), Vec3(0.26f, 0.98f, -0.22f), Palette.VELVET)
        t.cylinder(Vec3(-0.26f, 0.98f, -0.27f), Vec3(0.26f, 0.98f, -0.27f), 0.05f, Palette.VELVET, 8)
        t.box(Vec3(-0.30f, 0.0f, -0.30f), Vec3(-0.26f, 0.62f, 0.20f), Palette.SEAT_FRAME)
        t.box(Vec3(0.26f, 0.0f, -0.30f), Vec3(0.30f, 0.62f, 0.20f), Palette.SEAT_FRAME)
    }

    /** Shell of the audience side (house material). */
    fun house(): Toon {
        val t = Toon(4000)
        val side = HallLayout.SIDE_DEG
        val r0 = HallLayout.ROW1_R - HallLayout.ROW_STEP / 2
        t.sector(0.5f, r0, -side, side, HallLayout.FLOOR1_Y, Palette.STEP_B)
        for (row in 0 until HallLayout.ROWS) {
            val ra = HallLayout.rowRadius(row) - HallLayout.ROW_STEP / 2
            val rb = if (row == HallLayout.ROWS - 1) HallLayout.BACK_WALL_R else HallLayout.rowRadius(row) + HallLayout.ROW_STEP / 2
            val col = if (row % 2 == 0) Palette.STEP_A else Palette.STEP_B
            t.sector(ra, rb, -side, side, HallLayout.rowFloor(row), col)
            if (row > 0) t.arcWall(ra, -side, side, HallLayout.rowFloor(row - 1), HallLayout.rowFloor(row), if (row % 2 == 0) Palette.STEP_B else Palette.STEP_A)
        }
        val top = HallLayout.CEILING_Y
        t.arcWall(HallLayout.BACK_WALL_R, -side, side, HallLayout.rowFloor(HallLayout.ROWS - 1), top, Palette.WALL_A, stripes = Palette.WALL_B, seg = 36)
        val rs = HallLayout.STAGE_W / 2 / sin(Math.toRadians(side.toDouble())).toFloat()
        t.rayWall(-side, rs, HallLayout.BACK_WALL_R, HallLayout.FLOOR1_Y, top, Palette.WALL_A, Palette.WALL_B, faceRight = true)
        t.rayWall(side, rs, HallLayout.BACK_WALL_R, HallLayout.FLOOR1_Y, top, Palette.WALL_A, Palette.WALL_B, faceRight = false)
        t.sector(0.2f, HallLayout.BACK_WALL_R, -side, side, top, Palette.CEILING, down = true, seg = 36)
        // proscenium returns: from the stage corners out to where the side walls start
        val zs = -rs * cos(Math.toRadians(side.toDouble())).toFloat()
        for (sx in listOf(-1f, 1f)) {
            val x = sx * HallLayout.STAGE_W / 2
            val a = Vec3(x, HallLayout.FLOOR1_Y, HallLayout.STAGE_FRONT_Z)
            val bb = Vec3(x, HallLayout.FLOOR1_Y, zs)
            val c = Vec3(x, top, zs)
            val d = Vec3(x, top, HallLayout.STAGE_FRONT_Z)
            if (sx < 0) t.quad(bb, a, d, c, Palette.WALL_B, Vec3(1f, 0f, 0f)) else t.quad(a, bb, c, d, Palette.WALL_B, Vec3(-1f, 0f, 0f))
        }
        return t
    }

    /** Stage, curtains, back wall, screen frame, lectern (stage material). */
    fun stage(eyeH: Float): Toon {
        val t = Toon(1200)
        val hw = HallLayout.STAGE_W / 2
        t.box(Vec3(-hw, HallLayout.FLOOR1_Y, HallLayout.STAGE_FRONT_Z), Vec3(hw, 0f, HallLayout.STAGE_BACK_Z + 0.4f), Palette.STAGE_WOOD)
        // floor boards: thin darker lines across the stage top
        var z = HallLayout.STAGE_FRONT_Z + 0.5f
        while (z < HallLayout.STAGE_BACK_Z) {
            t.box(Vec3(-hw, 0f, z), Vec3(hw, 0.002f, z + 0.02f), Palette.LECTERN)
            z += 0.5f
        }
        // side curtains with folds
        for (sx in listOf(-1f, 1f)) {
            val x = sx * hw
            var zz = HallLayout.STAGE_FRONT_Z + 0.2f
            var k = 0
            while (zz < HallLayout.STAGE_BACK_Z) {
                val zn = zz + 0.35f
                val inset = if (k % 2 == 0) 0f else 0.08f
                val col = if (k % 2 == 0) Palette.CURTAIN_A else Palette.CURTAIN_B
                val xi = x - sx * inset
                val a = Vec3(xi, 0f, zz); val bb = Vec3(xi, 0f, zn); val c = Vec3(xi, 6.5f, zn); val d = Vec3(xi, 6.5f, zz)
                if (sx < 0) t.quad(bb, a, d, c, col, Vec3(1f, 0f, 0f)) else t.quad(a, bb, c, d, col, Vec3(-1f, 0f, 0f))
                zz = zn
                k++
            }
            // valance on top of the stage opening
        }
        t.box(Vec3(-hw, 6.2f, HallLayout.STAGE_FRONT_Z - 0.1f), Vec3(hw, 7.5f, HallLayout.STAGE_FRONT_Z + 0.1f), Palette.CURTAIN_A)
        t.box(Vec3(-hw, 0f, HallLayout.STAGE_BACK_Z + 0.3f), Vec3(hw, 6.5f, HallLayout.STAGE_BACK_Z + 0.4f), Palette.BACKSTAGE)
        // screen frame
        val sw = HallLayout.SCREEN_W / 2 + 0.12f
        t.box(Vec3(-sw, HallLayout.SCREEN_BOTTOM - 0.12f, HallLayout.SCREEN_Z + 0.02f), Vec3(sw, HallLayout.SCREEN_BOTTOM + HallLayout.SCREEN_H + 0.12f, HallLayout.SCREEN_Z + 0.08f), Palette.SCREEN_FRAME)
        // lectern: body and a top tilted 30° towards the speaker
        val top = HallLayout.lecternTop(eyeH)
        val lz = HallLayout.LECTERN_Z
        t.box(Vec3(-0.26f, 0f, lz - 0.20f), Vec3(0.26f, top - 0.05f, lz + 0.16f), Palette.LECTERN)
        t.box(Vec3(-0.32f, 0f, lz - 0.24f), Vec3(0.32f, 0.06f, lz + 0.20f), Palette.LECTERN)
        t.at(Quat4.axisAngle(Vec3.RIGHT, Math.toRadians(30.0).toFloat()), Vec3(0f, top - 0.03f, lz)) {
            box(Vec3(-HallLayout.LECTERN_W / 2, -0.02f, -HallLayout.LECTERN_D / 2), Vec3(HallLayout.LECTERN_W / 2, 0.02f, HallLayout.LECTERN_D / 2), Palette.STAGE_WOOD)
        }
        return t
    }

    /** Lights that stay lit in the dark (glow material): the stage edge strip, ceiling lamp strips, exit signs. */
    fun glow(): Toon {
        val t = Toon(600)
        val hw = HallLayout.STAGE_W / 2
        t.box(Vec3(-hw, -0.03f, HallLayout.STAGE_FRONT_Z - 0.02f), Vec3(hw, 0.0f, HallLayout.STAGE_FRONT_Z + 0.01f), Palette.AMBER)
        for (r in listOf(5.5f, 8.5f, 11.5f)) t.sector(r - 0.09f, r + 0.09f, -46f, 46f, HallLayout.CEILING_Y - 0.02f, Palette.LAMP, down = true, seg = 24)
        for (sx in listOf(-1f, 1f)) {
            val yaw = sx * (HallLayout.SIDE_DEG - 1.2f)
            val a = Math.toRadians(yaw.toDouble())
            val r = 11.8f
            val p = Vec3((r * sin(a)).toFloat(), HallLayout.rowFloor(8) + 2.3f, (-r * cos(a)).toFloat())
            t.box(p - Vec3(0.18f, 0.08f, 0.18f), p + Vec3(0.18f, 0.08f, 0.18f), Palette.EXIT)
        }
        // aisle step lights
        for (row in 0 until HallLayout.ROWS) {
            val r = HallLayout.rowRadius(row) - HallLayout.ROW_STEP / 2 + 0.02f
            t.box(Vec3(-0.04f, HallLayout.rowFloor(row) + 0.005f, -r - 0.02f), Vec3(0.04f, HallLayout.rowFloor(row) + 0.03f, -r + 0.02f), Palette.AMBER)
        }
        return t
    }

    /** Report overlay: a small tag above every head, coloured by its smoothed look time (5 levels, glow). */
    fun tints(levels: IntArray): Toon {
        val t = Toon(HallLayout.SEAT_COUNT * 8)
        for (s in HallLayout.seats) {
            val c = Palette.TINT[levels.getOrElse(s.index) { 0 }.coerceIn(0, 4)]
            t.at(Quat4.axisAngle(Vec3.UP, s.facing), s.floor) {
                box(Vec3(-0.15f, 1.445f, -0.02f), Vec3(0.15f, 1.50f, 0.0f), c)
            }
        }
        return t
    }

    /** A cone along local +Z from the apex, half-angle [halfDeg], length [len] (beam, spot). */
    fun cone(len: Float, halfDeg: Float, c: Int, seg: Int = 20): Toon {
        val t = Toon(seg * 3)
        val rr = len * kotlin.math.tan(Math.toRadians(halfDeg.toDouble())).toFloat()
        for (i in 0 until seg) {
            val a0 = 2 * PI * i / seg
            val a1 = 2 * PI * (i + 1) / seg
            t.tri(Vec3.ZERO, Vec3((rr * cos(a0)).toFloat(), (rr * sin(a0)).toFloat(), len), Vec3((rr * cos(a1)).toFloat(), (rr * sin(a1)).toFloat(), len), c)
        }
        return t
    }

    fun yawOf(d: Vec3): Float = atan2(d.x, d.z)
}
