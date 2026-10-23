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
    const val LECTERN_Z = -0.42f
    const val LECTERN_W = 0.60f
    const val LECTERN_D = 0.45f
    const val BACK_WALL_R = 13.0f
    const val CEILING_Y = 7.5f
    const val SIDE_DEG = 54f

    fun rowRadius(row: Int): Float = ROW1_R + ROW_STEP * row

    fun rowFloor(row: Int): Float = FLOOR1_Y + RISE * row

    fun headY(row: Int): Float = rowFloor(row) + HEAD_ABOVE_FLOOR

    /** Height of the lectern panel's centre: about 0.36 m below the eyes (design: look down 35 - 45°). */
    fun panelY(eyeH: Float): Float = (eyeH - 0.36f).coerceIn(1.05f, 1.45f)

    /**
     * Lectern top for an eye height. The lectern sits under the panel (QA q1-1: the panel's lower edge went into the
     * wood): the tilted top's highest edge stays [PANEL_CLEARANCE] below the lowest the panel's edge can go.
     */
    fun lecternTop(eyeH: Float): Float = panelY(eyeH) - PANEL_HALF_H - PANEL_CLEARANCE - LECTERN_RISE

    /** Centre of the lectern panel (what "looking at the lectern" aims at). */
    fun lecternPanel(eyeH: Float): Vec3 = Vec3(0f, panelY(eyeH), LECTERN_Z + 0.02f)

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

    fun classify(f: Vec3, head: Vec3, eyeH: Float, edges: Zones.TierEdges): Look {
        val h = horizontalDeg(f)
        val p = pitchDeg(f)
        if (angleDeg(f, HallLayout.lecternPanel(eyeH) - head) < LECTERN_DEG) return Look(LookClass.LECTERN, -1, h, p)
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
