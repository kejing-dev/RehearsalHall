package io.embodit.rehearsalhall.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The auditorium in hall-local metres (design `spatialLayout.hall`): origin on the floor at the speaker's place P,
 * +Y up, the audience ahead along -Z, +X to the speaker's right. The hall root entity carries this frame into the
 * Stage, so everything recorded here (yaw, pitch, seats, zones) survives a re-placement (R2-3).
 *
 * Raked theatre (R2-1): row 1 floor 0.9 m below the stage, each row 0.95 m further and 0.35 m higher; 10 ... 27 seats
 * per row over ±48°, a 1 m aisle in the middle, 168 seats.
 */
object HallLayout {
    const val ROWS = 9
    val SEATS_PER_ROW = intArrayOf(10, 12, 14, 17, 19, 21, 23, 25, 27)
    const val SEAT_COUNT = 168
    const val ROW1_R = 4.6f
    const val ROW_STEP = 0.95f
    const val FLOOR1_Y = -0.9f
    const val RISE = 0.35f
    /** Head centre of a seated figure above its row floor (design audienceStyle.proportions: sitting height 1.15 m + head). */
    const val HEAD_ABOVE_FLOOR = 1.2f
    const val FAN_DEG = 48f
    const val AISLE_M = 1.0f

    const val STAGE_W = 9f
    const val STAGE_FRONT_Z = -1.6f
    const val STAGE_BACK_Z = 3.4f
    const val SCREEN_Z = 3.4f
    const val SCREEN_W = 5.0f
    const val SCREEN_H = 2.8f
    const val SCREEN_BOTTOM = 1.2f
    const val LECTERN_Z = -0.50f
    const val LECTERN_W = 0.60f
    const val LECTERN_D = 0.45f
    const val BACK_WALL_R = 13.0f
    const val CEILING_Y = 7.5f
    const val SIDE_DEG = 54f

    fun rowRadius(row: Int): Float = ROW1_R + ROW_STEP * row

    fun rowFloor(row: Int): Float = FLOOR1_Y + RISE * row

    fun headY(row: Int): Float = rowFloor(row) + HEAD_ABOVE_FLOOR

    /**
     * Height of the lectern panel's centre (F1, 2026-11-25 colleague feedback "the panel blocks my view when seated"):
     * the panel's top edge stays at least [TOP_BELOW_DEG] under the line of sight for any eye height, standing or
     * seated (the old 1.05 m floor put it at eye level for a seated speaker). [h] = the "lectern height" setting.
     */
    fun panelY(eyeH: Float, h: LecternHeight = LecternHeight.MID): Float {
        val drop = PANEL_HALF_H + kotlin.math.tan(Math.toRadians(TOP_BELOW_DEG.toDouble())).toFloat() * -(LECTERN_Z + 0.02f)
        return (eyeH - drop - h.extraDrop).coerceIn(PANEL_MIN_Y, PANEL_MAX_Y)
    }

    /** Below this eye height the speaker is taken as seated (a one-line hint, nothing forced). */
    const val SEATED_EYE = 1.35f
    const val TOP_BELOW_DEG = 25f
    const val PANEL_MIN_Y = 0.70f
    const val PANEL_MAX_Y = 1.50f

    fun seated(eyeH: Float): Boolean = eyeH < SEATED_EYE

    /** Angle of the panel's top edge under the horizontal line of sight, in degrees (tests / audit). */
    fun topEdgeBelowDeg(eyeH: Float, h: LecternHeight = LecternHeight.MID): Float {
        val dz = -(LECTERN_Z + 0.02f)
        return Math.toDegrees(kotlin.math.atan2((eyeH - panelY(eyeH, h) - PANEL_HALF_H).toDouble(), dz.toDouble())).toFloat()
    }

    /**
     * Lectern top for an eye height. The lectern sits under the panel (QA q1-1: the panel's lower edge went into the
     * wood): the tilted top's highest edge stays [PANEL_CLEARANCE] below the lowest the panel's edge can go.
     */
    fun lecternTop(eyeH: Float, h: LecternHeight = LecternHeight.MID): Float = panelY(eyeH, h) - PANEL_HALF_H - PANEL_CLEARANCE - LECTERN_RISE

    /** Centre of the lectern panel (what "looking at the lectern" aims at). */
    fun lecternPanel(eyeH: Float, h: LecternHeight = LecternHeight.MID): Vec3 = Vec3(0f, panelY(eyeH, h), LECTERN_Z + 0.02f)

    /** Half the panel's height (0.29 m) and the gap kept under it; the 30° top rises 0.14 m above [lecternTop] at the far edge. */
    const val PANEL_HALF_H = 0.145f
    const val PANEL_CLEARANCE = 0.05f
    const val LECTERN_RISE = 0.11f

    data class Seat(
        val index: Int,
        val row: Int,
        val inRow: Int,
        /** Horizontal angle seen from P, degrees, + to the right. */
        val yawDeg: Float,
        val floor: Vec3,
        val head: Vec3,
        /** Yaw (domain [Yaw] convention about +Y) that turns local +Z towards P. */
        val facing: Float,
        val zone: Int,
    )

    val seats: List<Seat> = buildSeats()

    private fun buildSeats(): List<Seat> {
        val out = ArrayList<Seat>(SEAT_COUNT)
        for (row in 0 until ROWS) {
            val n = SEATS_PER_ROW[row]
            val r = rowRadius(row)
            val gap = Math.toDegrees((AISLE_M / r).toDouble()).toFloat()
            val left = n / 2
            val right = n - left
            fun place(k: Int, count: Int, from: Float, to: Float): Float = from + (to - from) * (k + 0.5f) / count
            for (k in 0 until n) {
                val yaw = if (k < left) place(k, left, -FAN_DEG, -gap / 2) else place(k - left, right, gap / 2, FAN_DEG)
                val a = Math.toRadians(yaw.toDouble())
                val x = (r * sin(a)).toFloat()
                val z = (-r * cos(a)).toFloat()
                val floor = Vec3(x, rowFloor(row), z)
                val facing = atan2(-x, -z) // local +Z towards the origin
                out += Seat(out.size, row, k, yaw, floor, Vec3(x, headY(row), z), facing, Zones.zoneOf(Zones.columnOf(yaw), row / 3))
            }
        }
        return out
    }

    /** Pitch of a row's head points seen from an eye at [eyeH] above P, degrees. */
    fun rowPitch(row: Int, eyeH: Float): Float = Math.toDegrees(atan2((headY(row) - eyeH).toDouble(), rowRadius(row).toDouble())).toFloat()
}

/**
 * The nine zones (design `spatialLayout.hall.zones`): column by horizontal angle (left / centre / right at ±16°), tier by
 * pitch with edges halfway between the head points of rows 3 | 4 and 6 | 7, worked out once for the eye height.
 * Index = tier * 3 + column (0 = front left ... 8 = back right, the design's `zoneNames` order).
 */
object Zones {
    const val COUNT = 9
    const val COL_EDGE = 16f

    fun columnOf(yawDeg: Float): Int = when {
        yawDeg < -COL_EDGE -> 0
        yawDeg > COL_EDGE -> 2
        else -> 1
    }

    fun zoneOf(column: Int, tier: Int): Int = tier * 3 + column

    fun column(zone: Int): Int = zone % 3

    fun tier(zone: Int): Int = zone / 3

    data class TierEdges(val frontMid: Float, val midBack: Float)

    fun edges(eyeH: Float): TierEdges =
        TierEdges(
            (HallLayout.rowPitch(2, eyeH) + HallLayout.rowPitch(3, eyeH)) / 2f,
            (HallLayout.rowPitch(5, eyeH) + HallLayout.rowPitch(6, eyeH)) / 2f,
        )

    /** Centre direction of a zone for `setHead`-style checks: (yaw + right, pitch + up), degrees. */
    fun centre(zone: Int, eyeH: Float): Pair<Float, Float> {
        val yaw = when (column(zone)) { 0 -> -32f; 1 -> 0f; else -> 32f }
        val row = tier(zone) * 3 + 1
        return yaw to HallLayout.rowPitch(row, eyeH)
    }
}

/** What the speaker is looking at (design `gazeModel.classify`). */
enum class LookClass { AUDIENCE, LECTERN, BACK, OTHER }

/** Classifies one head sample; pure, hall-local. */
object GazeClassifier {
    const val LECTERN_DEG = 20f
    const val BACK_DEG = 100f
    const val FAN_H_DEG = 52f
    const val FAN_DOWN_DEG = -30f
    const val FAN_UP_DEG = 25f
    const val SEAT_CONE_DEG = 8f

    fun horizontalDeg(f: Vec3): Float = Math.toDegrees(atan2(f.x.toDouble(), (-f.z).toDouble())).toFloat()

    fun pitchDeg(f: Vec3): Float = Math.toDegrees(atan2(f.y.toDouble(), hypot(f.x, f.z).toDouble())).toFloat()

    fun angleDeg(a: Vec3, b: Vec3): Float {
        val la = a.length
        val lb = b.length
        if (la < 1e-6f || lb < 1e-6f) return 180f
        return Math.toDegrees(kotlin.math.acos((a.dot(b) / (la * lb)).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    data class Look(val cls: LookClass, val zone: Int, val yawDeg: Float, val pitchDeg: Float)

    fun classify(f: Vec3, head: Vec3, eyeH: Float, edges: Zones.TierEdges, lh: LecternHeight = LecternHeight.MID): Look {
        val h = horizontalDeg(f)
        val p = pitchDeg(f)
        if (angleDeg(f, HallLayout.lecternPanel(eyeH, lh) - head) < LECTERN_DEG) return Look(LookClass.LECTERN, -1, h, p)
        if (hypot(f.x, f.z) > 0.05f && abs(h) > BACK_DEG) return Look(LookClass.BACK, -1, h, p)
        if (abs(h) <= FAN_H_DEG && p >= FAN_DOWN_DEG && p <= FAN_UP_DEG) {
            val tier = when {
                p < edges.frontMid -> 0
                p > edges.midBack -> 2
                else -> 1
            }
            return Look(LookClass.AUDIENCE, Zones.zoneOf(Zones.columnOf(h), tier), h, p)
        }
        return Look(LookClass.OTHER, -1, h, p)
    }

    /** Unit direction from (yaw + right, pitch + up) degrees, hall-local. */
    fun direction(yawDeg: Float, pitchDeg: Float): Vec3 {
        val y = yawDeg * PI.toFloat() / 180f
        val p = pitchDeg * PI.toFloat() / 180f
        return Vec3(sin(y) * cos(p), sin(p), -cos(y) * cos(p))
    }
}

/** The settings row "lectern height" (F1): how much further down (+) or up (-) the panel sits, metres. */
enum class LecternHeight(val extraDrop: Float) { LOW(0.08f), MID(0f), HIGH(-0.06f) }

/**
 * Where the teleprompter (current page's notes, timer, page number) is shown while talking (2026-11-27 user: "put the
 * prompter above the last row, or under the first row, or floating half transparent in mid-air; keep the look-down
 * lectern too; a setting picks the place"). LECTERN = only the lectern panel (look down), no extra panel.
 */
enum class PrompterPlace { LECTERN, FRONT, AIR, BACK }

object Prompter {
    /** Angular width of the prompter panel as seen from the speaker's place, any place: text stays the same size. */
    const val WIDTH_DEG = 30f
    /** FRONT: on the stage edge, under the first row's faces. */
    const val FRONT_DIST = 1.9f
    const val FRONT_DOWN_DEG = 22f
    /** AIR: over the aisle among the middle rows, a little under the horizon, half transparent. */
    const val AIR_DIST = 5.0f
    const val AIR_DOWN_DEG = 3f
    /** BACK: on the back wall above the last row's heads. */
    const val BACK_ABOVE_HEADS = 0.3f
    /** Panel height / width (300 / 760 dp) and its angular height. */
    const val ASPECT = 300f / 760f
    const val HEIGHT_DEG = WIDTH_DEG * ASPECT

    /** Centre of the prompter panel, hall-local (the audience is towards -z); null for [PrompterPlace.LECTERN]. */
    fun centre(place: PrompterPlace, eyeH: Float): Vec3? =
        when (place) {
            PrompterPlace.LECTERN -> null
            PrompterPlace.FRONT -> {
                // top edge just under the first row's faces (they sit lower the taller the speaker stands)
                val faces = Math.toDegrees(kotlin.math.atan2((HallLayout.headY(0) - eyeH).toDouble(), HallLayout.rowRadius(0).toDouble())).toFloat()
                polar(FRONT_DIST, minOf(-FRONT_DOWN_DEG, faces - HEIGHT_DEG / 2 - 0.5f), eyeH)
            }
            PrompterPlace.AIR -> polar(AIR_DIST, -AIR_DOWN_DEG, eyeH)
            PrompterPlace.BACK -> {
                // lower edge BACK_ABOVE_HEADS over the last row's heads: the panel is ~7 m wide out there
                val r = HallLayout.rowRadius(HallLayout.ROWS - 1) + 0.5f
                val heads = HallLayout.headY(HallLayout.ROWS - 1)
                val d = kotlin.math.hypot(r, heads + 1.6f - eyeH)
                val halfH = d * kotlin.math.tan(Math.toRadians(WIDTH_DEG / 2.0)).toFloat() * ASPECT
                Vec3(0f, heads + BACK_ABOVE_HEADS + halfH, -r)
            }
        }

    private fun polar(dist: Float, pitchDeg: Float, eyeH: Float): Vec3 {
        val a = Math.toRadians(pitchDeg.toDouble())
        return Vec3(0f, eyeH + (dist * kotlin.math.sin(a)).toFloat(), -(dist * kotlin.math.cos(a)).toFloat())
    }

    /** Panel width in metres so that it spans [WIDTH_DEG] from the eyes. */
    fun widthM(place: PrompterPlace, eyeH: Float): Float {
        val c = centre(place, eyeH) ?: return 0f
        val d = (c - Vec3(0f, eyeH, 0f)).length
        return 2f * d * kotlin.math.tan(Math.toRadians(WIDTH_DEG / 2.0)).toFloat()
    }

    /** Pitch of the panel centre from the eyes, degrees (+ up). */
    fun pitchDeg(place: PrompterPlace, eyeH: Float): Float {
        val c = centre(place, eyeH) ?: return 0f
        return Math.toDegrees(kotlin.math.atan2((c.y - eyeH).toDouble(), kotlin.math.hypot(c.x, c.z).toDouble())).toFloat()
    }

    /** Seconds a speaker needs for [text] at a calm pace (250 CJK characters or 130 words a minute), at least 8 s. */
    fun readSeconds(text: String): Float {
        val cjk = text.count { it.code in 0x3400..0x9FFF }
        val words = Regex("[A-Za-z0-9']+").findAll(text).count()
        return ((cjk / 250f + words / 130f) * 60f).coerceAtLeast(8f)
    }

    /** 0..1: how far the prompter text has scrolled [pageSec] into a page whose notes are [text]. */
    fun scroll(text: String, pageSec: Float): Float = (pageSec / readSeconds(text)).coerceIn(0f, 1f)
}
