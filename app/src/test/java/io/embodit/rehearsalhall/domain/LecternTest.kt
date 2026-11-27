package io.embodit.rehearsalhall.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F1 (2026-11-25 colleague feedback): the lectern panel never blocks the view of the audience, seated or standing. */
class LecternTest {
    private val eyes = listOf(1.10f, 1.25f, 1.40f, 1.60f, 1.85f)

    @Test
    fun topEdgeStaysWellBelowTheLineOfSight() {
        for (e in eyes) {
            val mid = HallLayout.topEdgeBelowDeg(e, LecternHeight.MID)
            assertTrue("eye $e mid top at $mid°", mid >= HallLayout.TOP_BELOW_DEG - 0.01f || HallLayout.panelY(e) == HallLayout.PANEL_MAX_Y)
            assertTrue("eye $e low below mid", HallLayout.topEdgeBelowDeg(e, LecternHeight.LOW) >= mid - 0.01f)
            assertTrue("eye $e high still clear (>= 18°)", HallLayout.topEdgeBelowDeg(e, LecternHeight.HIGH) >= 18f)
        }
    }

    @Test
    fun seatedNoLongerSitsAtEyeLevel() {
        // the old rule (eye - 0.36 clamped to >= 1.05 m) put a seated speaker's panel top at ~6° under the eyes
        val old = Math.toDegrees(kotlin.math.atan2((1.20 - (1.20f - 0.36f).coerceIn(1.05f, 1.45f) - HallLayout.PANEL_HALF_H).toDouble(), 0.40)).toFloat()
        assertTrue("old $old°", old < 10f)
        assertTrue(HallLayout.topEdgeBelowDeg(1.20f) >= 25f)
        assertTrue(HallLayout.seated(1.20f))
        assertFalse(HallLayout.seated(1.60f))
    }

    @Test
    fun lecternWoodStaysUnderThePanel() {
        for (e in eyes) for (h in LecternHeight.entries) {
            val panelBottom = HallLayout.panelY(e, h) - HallLayout.PANEL_HALF_H
            val woodTop = HallLayout.lecternTop(e, h) + HallLayout.LECTERN_RISE
            assertTrue("eye $e $h", panelBottom - woodTop >= HallLayout.PANEL_CLEARANCE - 1e-4f)
            assertTrue("eye $e $h lectern above the floor", HallLayout.lecternTop(e, h) > 0.3f)
        }
    }

    @Test
    fun standingKeepsAboutTheOldHeight() {
        // standing at 1.60 m the panel moved only a little (was 1.24 m, 0.42 m away)
        assertEquals(1.23f, HallLayout.panelY(1.60f), 0.02f)
    }

    @Test
    fun lookingAtTheLecternFollowsTheHeightSetting() {
        val eye = 1.25f
        val head = Vec3(0f, eye, 0f)
        for (h in LecternHeight.entries) {
            val f = (HallLayout.lecternPanel(eye, h) - head).normalized()
            assertEquals(LookClass.LECTERN, GazeClassifier.classify(f, head, eye, Zones.edges(eye), h).cls)
        }
    }
}
