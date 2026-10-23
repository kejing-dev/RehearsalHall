package io.embodit.rehearsalhall.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design `acceptance.mustPass` rules that hold without a device: geometry, gaze buckets, report wording rules, voice. */
class HallTest {
    private val eye = 1.6f
    private val head = Vec3(0f, eye, 0f)

    @Test
    fun seatsAndRows() {
        assertEquals(168, HallLayout.seats.size)
        assertEquals(12.2f, HallLayout.rowRadius(8), 1e-4f)
        // design: row pitches -15.8 ... +7.0 at eye 1.6
        assertEquals(-15.8f, HallLayout.rowPitch(0, eye), 0.1f)
        assertEquals(7.0f, HallLayout.rowPitch(8, eye), 0.1f)
        val e = Zones.edges(eye)
        assertEquals(-3.6f, e.frontMid, 0.1f)
        assertEquals(3.6f, e.midBack, 0.1f)
        // every zone has seats; adjacent people in a row sit apart
        for (z in 0 until 9) assertTrue(HallLayout.seats.count { it.zone == z } >= 8)
    }

    private fun talk(yaw: Float, pitch: Float, sec: Float, rec: GazeRecorder) {
        val f = GazeClassifier.direction(yaw, pitch)
        repeat(Math.round(sec * 10)) { rec.step(0.1f, f, head, 0) }
    }

    private fun session(rec: GazeRecorder) =
        Session("s", "d", 0, rec.classTotals.sum(), 60, emptyList(), rec.zone.toList(), rec.seat.toList(), rec.classTotals.toList(), rec.samplesBase64(), null)

    @Test
    fun straightAheadOnlyCenter() {
        val rec = GazeRecorder(eye)
        talk(0f, 0f, 60f, rec)
        val r = session(rec).report
        assertEquals(ZoneLevel.HOT, r.levels[4])
        assertEquals(8, r.levels.count { it == ZoneLevel.COLD })
        val v = r.verdict as Report.Verdict.Cold
        assertEquals(2, v.zones.size)
        // ties at 0 s: back rows first
        assertTrue(v.zones.all { Zones.tier(it) == 2 })
        assertEquals(600, rec.sampleCount)
    }

    @Test
    fun frontAndBackTiers() {
        val rec = GazeRecorder(eye)
        talk(0f, -10f, 30f, rec)
        assertEquals(30f, rec.zone[1], 0.2f)
        val rec2 = GazeRecorder(eye)
        talk(0f, 6f, 30f, rec2)
        assertEquals(30f, rec2.zone[7], 0.2f)
        assertEquals(rec2.audienceSec, rec2.zone.sum(), 0.2f)
    }

    @Test
    fun allZonesCovered() {
        val rec = GazeRecorder(eye)
        for (z in 0 until 9) {
            val (y, p) = Zones.centre(z, eye)
            talk(y, p, 5f, rec)
        }
        val r = session(rec).report
        assertTrue(r.levels.none { it == ZoneLevel.COLD })
        assertEquals(9, r.covered)
    }

    @Test
    fun lecternAndBack() {
        val rec = GazeRecorder(eye)
        val toLectern = HallLayout.lecternPanel(eye) - head
        val f = toLectern.normalized()
        repeat(200) { rec.step(0.1f, f, head, 0) }
        repeat(100) { rec.step(0.1f, Vec3(0f, 0f, 1f), head, 0) }
        assertEquals(20f, rec.classTotals[LookClass.LECTERN.ordinal], 0.5f)
        assertEquals(10f, rec.classTotals[LookClass.BACK.ordinal], 0.5f)
        assertTrue(session(rec).report.verdict is Report.Verdict.NoAudience)
    }

    @Test
    fun samplesRoundTrip() {
        val list = listOf(Sample(12.34f, -5.67f, LookClass.AUDIENCE, 3), Sample(-170f, 20f, LookClass.BACK, 0))
        val back = Samples.decode(Samples.encode(list))
        assertEquals(2, back.size)
        assertEquals(12.34f, back[0].yawDeg, 0.01f)
        assertEquals(LookClass.BACK, back[1].cls)
        assertEquals(3, back[0].page)
    }

    @Test
    fun voiceMetrics() {
        val floor = VoiceMath.floor(List(30) { -60f })
        // 2 s speaking, 4 s pause on page 2, 4 s speaking
        val frames = List(20) { -30f } + List(40) { -60f } + List(40) { -42f }
        val pages = List(20) { 1 } + List(40) { 2 } + List(40) { 2 }
        val v = VoiceMath.metrics(frames, pages, floor) as Voice.Metrics
        assertEquals(0.6f, v.speakRatio, 0.01f)
        assertEquals(4f, v.longestPauseSec, 0.01f)
        assertEquals(2, v.longestPausePage)
        assertEquals(1, v.pauseCount)
        assertTrue(v.quiet)
        assertTrue(VoiceMath.metrics(List(50) { -61f }, List(50) { 0 }, floor) is Voice.NoSignal)
    }

    @Test
    fun notesTxt() {
        val n = Deck.parseNotes("one\n---\ntwo\r\n---\nthree\n---\nfour", 3)
        assertEquals(listOf("one", "two", "three"), n)
        assertEquals(listOf("a", ""), Deck.parseNotes("a", 2))
    }
}
