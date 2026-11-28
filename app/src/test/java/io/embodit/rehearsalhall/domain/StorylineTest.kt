package io.embodit.rehearsalhall.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The storyline strip (2026-11-27): where it sits, which pages it shows, what a card says. */
class StorylineTest {
    private val eyes = listOf(1.20f, 1.60f, 1.85f)

    private fun pitch(c: Vec3, e: Float) = Math.toDegrees(kotlin.math.atan2((c.y - e).toDouble(), kotlin.math.hypot(c.x, c.z).toDouble())).toFloat()

    @Test
    fun frontStripIsUnderTheFirstRowFacesAndAboveTheLectern() {
        for (e in eyes) {
            val p = pitch(Storyline.centre(Storyline.Place.FRONT, e), e)
            val faces = Math.toDegrees(kotlin.math.atan2((HallLayout.headY(0) - e).toDouble(), HallLayout.rowRadius(0).toDouble())).toFloat()
            assertTrue("eye $e", faces > p + Storyline.HEIGHT_DEG / 2)
            // the lectern (shrunk while the speaker looks at the hall) starts ~30 degrees down
            assertTrue("eye $e low ${p - Storyline.HEIGHT_DEG / 2}", p - Storyline.HEIGHT_DEG / 2 > -30f)
        }
    }

    @Test
    fun backStripIsAboveTheLastRowInsideTheHall() {
        for (e in eyes) {
            val c = Storyline.centre(Storyline.Place.BACK, e)
            val w = Storyline.widthM(Storyline.Place.BACK, e)
            val last = HallLayout.ROWS - 1
            val heads = Math.toDegrees(kotlin.math.atan2((HallLayout.headY(last) - e).toDouble(), HallLayout.rowRadius(last).toDouble())).toFloat()
            assertTrue("eye $e: lower edge over the last row's heads", pitch(c, e) - Storyline.HEIGHT_DEG / 2 > heads + 1.5f)
            assertTrue(c.y + w * Storyline.ASPECT / 2 < HallLayout.CEILING_Y)
            assertTrue("ends inside the curved back wall", kotlin.math.hypot(c.z, w / 2) < HallLayout.BACK_WALL_R - 0.3f)
            val d = (c - Vec3(0f, e, 0f)).length
            assertEquals(Storyline.WIDTH_DEG, Math.toDegrees(2 * kotlin.math.atan((w / 2 / d).toDouble())).toFloat(), 0.01f)
        }
    }

    @Test
    fun thePrompterKeepsItsSpot() {
        assertFalse(Storyline.shown(Storyline.Place.FRONT, PrompterPlace.FRONT))
        assertFalse(Storyline.shown(Storyline.Place.BACK, PrompterPlace.BACK))
        for (p in listOf(PrompterPlace.LECTERN, PrompterPlace.AIR)) {
            assertTrue(Storyline.shown(Storyline.Place.FRONT, p))
            assertTrue(Storyline.shown(Storyline.Place.BACK, p))
        }
        assertTrue(Storyline.shown(Storyline.Place.BACK, PrompterPlace.FRONT))
        assertTrue(Storyline.shown(Storyline.Place.FRONT, PrompterPlace.BACK))
    }

    @Test
    fun windowKeepsTheCurrentPageInViewAndCentred() {
        assertEquals(0 until 5, Storyline.window(3, 5))
        assertEquals(0 until 7, Storyline.window(0, 20))
        assertEquals(0 until 7, Storyline.window(3, 20))
        assertEquals(7 until 14, Storyline.window(10, 20))
        assertEquals(13 until 20, Storyline.window(19, 20))
        assertTrue(Storyline.window(0, 0).isEmpty())
        for (n in 1..60) for (p in 0 until n) assertTrue(p in Storyline.window(p, n))
    }

    @Test
    fun labelIsTheStartOfTheNotes() {
        assertEquals("", Storyline.label("  \n "))
        assertEquals("大家好", Storyline.label("\n大家好，我是今天的分享人。"))
        assertEquals("Why now", Storyline.label("Why now: three reasons."))
        assertEquals("今年我们一共做了三件很重…", Storyline.label("今年我们一共做了三件很重要的事情然后还有别的"))
        assertTrue(Storyline.label("a".repeat(80)).length <= Storyline.LABEL_BUDGET + 1)
    }
}
