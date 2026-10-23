package io.embodit.rehearsalhall.domain

import java.util.Base64
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Per-rehearsal statistics (design `gazeModel`), fed once per frame from the ECS system while the talk runs
 * (S06 only, never while paused / counting down / confirming: R2-4). Plain object, not Compose state.
 */
class GazeRecorder(val eyeH: Float) {
    val edges = Zones.edges(eyeH)
    val zone = FloatArray(Zones.COUNT)
    val seat = FloatArray(HallLayout.SEAT_COUNT)
    val classTotals = FloatArray(LookClass.entries.size)
    private val samples = java.io.ByteArrayOutputStream()
    private var sampleAcc = 0f
    var sampleCount = 0
        private set
    var lastLook: GazeClassifier.Look? = null
        private set

    /** One frame: [f] = unit forward, [head] = head position, both hall-local; [dt] seconds. */
    fun step(dt: Float, f: Vec3, head: Vec3, page: Int): GazeClassifier.Look {
        val look = GazeClassifier.classify(f, head, eyeH, edges)
        lastLook = look
        classTotals[look.cls.ordinal] += dt
        if (look.cls == LookClass.AUDIENCE) {
            zone[look.zone] += dt
            for (s in HallLayout.seats) if (GazeClassifier.angleDeg(f, s.head - head) < GazeClassifier.SEAT_CONE_DEG) seat[s.index] += dt
        }
        // 10 Hz record; a slow frame (emulator) writes one sample per 0.1 s it covered
        sampleAcc += dt
        while (sampleAcc >= SAMPLE_S && sampleCount < MAX_SAMPLES) {
            sampleAcc -= SAMPLE_S
            writeSample(look, page)
        }
        return look
    }

    private fun writeSample(look: GazeClassifier.Look, page: Int) {
        val y = (look.yawDeg * 100).roundToInt().coerceIn(-32768, 32767)
        val p = (look.pitchDeg * 100).roundToInt().coerceIn(-32768, 32767)
        samples.write(y shr 8); samples.write(y)
        samples.write(p shr 8); samples.write(p)
        samples.write(look.cls.ordinal)
        samples.write(page.coerceIn(0, 255))
        sampleCount++
    }

    fun samplesBase64(): String = Base64.getEncoder().encodeToString(samples.toByteArray())

    val audienceSec: Float
        get() = classTotals[LookClass.AUDIENCE.ordinal]

    companion object {
        const val SAMPLE_S = 0.1f
        const val SAMPLE_BYTES = 6
        const val MAX_SAMPLES = 36_000
    }
}

/** One decoded 10 Hz sample. */
data class Sample(val yawDeg: Float, val pitchDeg: Float, val cls: LookClass, val page: Int)

object Samples {
    fun decode(b64: String): List<Sample> {
        if (b64.isEmpty()) return emptyList()
        val b = Base64.getDecoder().decode(b64)
        val out = ArrayList<Sample>(b.size / GazeRecorder.SAMPLE_BYTES)
        var i = 0
        while (i + GazeRecorder.SAMPLE_BYTES <= b.size) {
            val y = ((b[i].toInt() shl 8) or (b[i + 1].toInt() and 0xFF)).toShort()
            val p = ((b[i + 2].toInt() shl 8) or (b[i + 3].toInt() and 0xFF)).toShort()
            val c = LookClass.entries.getOrElse(b[i + 4].toInt() and 0xFF) { LookClass.OTHER }
            out += Sample(y / 100f, p / 100f, c, b[i + 5].toInt() and 0xFF)
            i += GazeRecorder.SAMPLE_BYTES
        }
        return out
    }

    fun encode(list: List<Sample>): String {
        val o = java.io.ByteArrayOutputStream()
        for (s in list) {
            val y = (s.yawDeg * 100).roundToInt()
            val p = (s.pitchDeg * 100).roundToInt()
            o.write(y shr 8); o.write(y); o.write(p shr 8); o.write(p); o.write(s.cls.ordinal); o.write(s.page)
        }
        return Base64.getEncoder().encodeToString(o.toByteArray())
    }
}

/** Voice numbers kept after a talk (design `voiceModel`); audio itself is never stored. */
sealed interface Voice {
    data class Metrics(val speakRatio: Float, val longestPauseSec: Float, val longestPausePage: Int, val pauseCount: Int, val quiet: Boolean) : Voice

    data object NoSignal : Voice
}

/**
 * Voice metrics from the 100 ms RMS frames (dBFS). Floor = 10th percentile of the countdown frames; speaking =
 * RMS > floor + 12 dB; a pause = 3 s or more without speaking; level = median of speaking frames, < -38 dBFS = quiet.
 */
object VoiceMath {
    const val FRAME_S = 0.1f
    const val SPEAK_DB = 12f
    const val PAUSE_S = 3f
    const val QUIET_DBFS = -38f

    fun floor(countdown: List<Float>): Float {
        if (countdown.isEmpty()) return -60f
        val s = countdown.sorted()
        return s[((s.size - 1) * 0.1f).roundToInt()]
    }

    fun metrics(frames: List<Float>, pages: List<Int>, floorDb: Float): Voice {
        if (frames.isEmpty()) return Voice.NoSignal
        val speaking = frames.map { it > floorDb + SPEAK_DB }
        if (speaking.none { it }) return Voice.NoSignal
        var longest = 0
        var longestAt = 0
        var count = 0
        var run = 0
        val minRun = (PAUSE_S / FRAME_S).roundToInt()
        for (i in speaking.indices) {
            if (!speaking[i]) {
                run++
            } else {
                if (run >= minRun) count++
                if (run > longest) { longest = run; longestAt = i - run }
                run = 0
            }
        }
        if (run >= minRun) count++
        if (run > longest) { longest = run; longestAt = speaking.size - run }
        val level = frames.filterIndexed { i, _ -> speaking[i] }.sorted().let { it[it.size / 2] }
        val longestSec = if (longest >= minRun) longest * FRAME_S else 0f
        return Voice.Metrics(
            speakRatio = speaking.count { it }.toFloat() / speaking.size,
            longestPauseSec = longestSec,
            longestPausePage = pages.getOrElse(longestAt) { 0 },
            pauseCount = count,
            quiet = level < QUIET_DBFS,
        )
    }
}

/** A finished rehearsal (design `dataModel.session`). */
data class Session(
    val id: String,
    val deckId: String,
    val startedAt: Long,
    val durationSec: Float,
    val targetSec: Int,
    val pageTimes: List<Float>,
    val zoneDwell: List<Float>,
    val seatDwell: List<Float>,
    val classTotals: List<Float>,
    val samples: String,
    /** null = microphone off for this talk. */
    val voice: Voice?,
) {
    val audienceSec: Float
        get() = classTotals.getOrElse(LookClass.AUDIENCE.ordinal) { 0f }

    val report: Report
        get() = Report.of(this)
}

enum class ZoneLevel { COLD, MID, HOT }

/** What the report says (design `gazeModel.covered` / `verdict`). */
data class Report(
    val levels: List<ZoneLevel>,
    val covered: Int,
    val audiencePct: Int,
    val lecternPct: Int,
    val backPct: Int,
    val verdict: Verdict,
    val lecternHeavy: Boolean,
    val backHeavy: Boolean,
) {
    sealed interface Verdict {
        data class NoAudience(val lecternPct: Int, val backPct: Int) : Verdict

        data class Cold(val zones: List<Int>) : Verdict

        data class Weakest(val zone: Int) : Verdict

        data object All : Verdict
    }

    companion object {
        const val HOT = 0.08f
        const val MID = 0.03f

        fun levelsOf(zone: List<Float>, audienceSec: Float): List<ZoneLevel> =
            zone.map { s ->
                val q = if (audienceSec <= 1e-6f) 0f else s / audienceSec
                when {
                    q >= HOT -> ZoneLevel.HOT
                    q >= MID -> ZoneLevel.MID
                    else -> ZoneLevel.COLD
                }
            }

        fun of(s: Session): Report {
            val total = max(1e-6f, s.classTotals.sum())
            fun pct(c: LookClass) = (s.classTotals.getOrElse(c.ordinal) { 0f } / total * 100).roundToInt()
            val aud = pct(LookClass.AUDIENCE)
            val lec = pct(LookClass.LECTERN)
            val back = pct(LookClass.BACK)
            val levels = levelsOf(s.zoneDwell, s.audienceSec)
            val covered = levels.count { it != ZoneLevel.COLD }
            val verdict =
                when {
                    aud < 10 -> Verdict.NoAudience(lec, back)
                    levels.all { it == ZoneLevel.HOT } -> Verdict.All
                    levels.any { it == ZoneLevel.COLD } ->
                        Verdict.Cold(levels.indices.filter { levels[it] == ZoneLevel.COLD }.sortedWith(compareBy<Int>({ s.zoneDwell[it] }, { -Zones.tier(it) })).take(2))
                    else -> Verdict.Weakest(levels.indices.filter { levels[it] == ZoneLevel.MID }.minByOrNull { s.zoneDwell[it] } ?: 0)
                }
            return Report(levels, covered, aud, lec, back, verdict, lecternHeavy = lec > 40 && verdict !is Verdict.NoAudience, backHeavy = back > 15 && verdict !is Verdict.NoAudience)
        }
    }
}

/** Seat tint level 0 ... 4 with the neighbour smoothing of the design (0.25 / 0.5 / 0.25, same row). */
object SeatTint {
    fun smoothed(seatDwell: List<Float>): FloatArray {
        val out = FloatArray(seatDwell.size)
        for (s in HallLayout.seats) {
            val l = HallLayout.seats.getOrNull(s.index - 1)?.takeIf { it.row == s.row }
            val r = HallLayout.seats.getOrNull(s.index + 1)?.takeIf { it.row == s.row }
            val c = seatDwell.getOrElse(s.index) { 0f }
            val lv = l?.let { seatDwell.getOrElse(it.index) { 0f } } ?: c
            val rv = r?.let { seatDwell.getOrElse(it.index) { 0f } } ?: c
            out[s.index] = 0.25f * lv + 0.5f * c + 0.25f * rv
        }
        return out
    }

    fun levels(seatDwell: List<Float>): IntArray {
        val v = smoothed(seatDwell)
        val maxV = v.maxOrNull() ?: 0f
        return IntArray(v.size) { i ->
            if (maxV <= 1e-6f || v[i] <= 0.05f) 0 else (1 + (v[i] / maxV * 3.999f).toInt()).coerceIn(1, 4)
        }
    }
}

/** A deck (design `dataModel.deck`). */
data class Deck(
    val id: String,
    val title: String,
    val source: String,
    val pageCount: Int,
    val notes: List<String>,
    val targetMinutes: Int,
    val createdAt: Long,
) {
    val noSlides: Boolean
        get() = source == SOURCE_NONE

    val hasNotes: Boolean
        get() = notes.any { it.isNotBlank() }

    companion object {
        const val SOURCE_SAMPLE = "sample"
        const val SOURCE_PDF = "pdf"
        const val SOURCE_IMAGES = "images"
        const val SOURCE_NONE = "none"
        const val SAMPLE_ID = "sample"
        const val NONE_ID = "none"
        const val MAX_PAGES = 60

        /** Notes TXT: one line `---` between pages; extra blocks are ignored (design `deckModel.notes`). */
        fun parseNotes(text: String, pages: Int): List<String> {
            val blocks = text.replace("\r\n", "\n").split(Regex("(?m)^---\\s*$")).map { it.trim() }
            return List(pages) { blocks.getOrElse(it) { "" } }
        }
    }
}

/** Formatting shared by panels and tests. */
object Clock {
    fun mmss(sec: Float): String {
        val s = max(0, sec.toInt())
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
    }

    fun mmss2(sec: Float): String {
        val s = max(0, sec.toInt())
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60)
    }
}
