package io.embodit.rehearsalhall.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Teleprompter places (2026-11-27) and the local file list. */
class PrompterTest {
    private val eyes = listOf(1.20f, 1.60f, 1.85f)

    @Test
    fun lecternPlaceHasNoExtraPanel() {
        assertNull(Prompter.centre(PrompterPlace.LECTERN, 1.6f))
    }

    @Test
    fun everyPlaceSpansTheSameAngle() {
        for (e in eyes) for (p in listOf(PrompterPlace.FRONT, PrompterPlace.AIR, PrompterPlace.BACK)) {
            val c = Prompter.centre(p, e)!!
            val d = (c - Vec3(0f, e, 0f)).length
            val deg = Math.toDegrees(2 * kotlin.math.atan((Prompter.widthM(p, e) / 2 / d).toDouble())).toFloat()
            assertEquals("$p eye $e", Prompter.WIDTH_DEG, deg, 0.01f)
            assertEquals(0f, c.x, 1e-4f)
            assertTrue("in front of the speaker", c.z < 0f)
        }
    }

    @Test
    fun frontIsUnderTheFirstRowFacesAndAboveTheLectern() {
        for (e in eyes) {
            val pitch = Prompter.pitchDeg(PrompterPlace.FRONT, e)
            assertTrue(pitch <= -Prompter.FRONT_DOWN_DEG + 0.01f)
            val halfH = Prompter.HEIGHT_DEG / 2
            // first-row faces (seen from the speaker) stay above the prompter's top edge
            val faces = Math.toDegrees(kotlin.math.atan2((HallLayout.headY(0) - e).toDouble(), HallLayout.rowRadius(0).toDouble())).toFloat()
            assertTrue("eye $e faces $faces top ${pitch + halfH}", faces > pitch + halfH)
        }
    }

    @Test
    fun airIsNearTheHorizonAndBackIsAboveTheLastRow() {
        for (e in eyes) {
            assertEquals(-Prompter.AIR_DOWN_DEG, Prompter.pitchDeg(PrompterPlace.AIR, e), 0.01f)
            val back = Prompter.centre(PrompterPlace.BACK, e)!!
            val lowEdge = back.y - Prompter.widthM(PrompterPlace.BACK, e) * PROMPTER_ASPECT / 2
            assertTrue("above the last row's heads", lowEdge > HallLayout.headY(HallLayout.ROWS - 1) + 0.1f)
            assertTrue("inside the hall", -back.z < HallLayout.BACK_WALL_R && back.y < HallLayout.CEILING_Y)
        }
    }

    @Test
    fun scrollFollowsReadingPace() {
        val zh = "字".repeat(500) // 2 minutes
        assertEquals(120f, Prompter.readSeconds(zh), 0.5f)
        assertEquals(0.5f, Prompter.scroll(zh, 60f), 0.01f)
        assertEquals(1f, Prompter.scroll(zh, 500f), 0f)
        assertEquals(8f, Prompter.readSeconds("短"), 0f)
    }

    @Test
    fun fileListKeepsDecksAndScriptsNewestFirst() {
        assertEquals(FileList.Kind.PPT, FileList.kind("Q4 发布.PPTX"))
        assertEquals(FileList.Kind.DOC, FileList.kind("讲稿.docx"))
        assertEquals(FileList.Kind.PDF, FileList.kind("a.pdf"))
        assertNull(FileList.kind("~\$Q4.pptx"))
        assertNull(FileList.kind("photo.png"))
        assertNull(FileList.kind("old.ppt"))
        val a = FileEntry("/d/a.pdf", "a.pdf", "Download", 1000, 60_000L * 10)
        val b = FileEntry("/d/b.pptx", "b.pptx", "Download", 2_400_000, 60_000L * 10 + 5)
        val c = FileEntry("/d/c.docx", "c.docx", "Documents", 10, 60_000L * 20)
        assertEquals(listOf("c.docx", "b.pptx", "a.pdf"), FileList.sort(listOf(a, b, c)).map { it.name })
        assertEquals("2.4 MB", FileList.size(2_400_000))
        assertEquals("1 KB", FileList.size(10))
    }

    private companion object {
        const val PROMPTER_ASPECT = 300f / 760f
    }
}
