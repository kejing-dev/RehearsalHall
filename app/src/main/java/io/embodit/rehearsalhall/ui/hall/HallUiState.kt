package io.embodit.rehearsalhall.ui.hall

import io.embodit.rehearsalhall.domain.Deck
import io.embodit.rehearsalhall.domain.Session

/** Base states of the design (`states[]`, kind base): exactly one decides which lectern page shows. */
enum class Base(val page: String) {
    S00("loading"), S01("setup"), S02("decks"), S03("importing"), S04("importError"), S05("countdown"), S06("speaking"),
    S07("paused"), S08("endConfirm"), S09("report"), S10("replay"), S11("settings"), S12("history"), S13("micAsk"),
    S14("confirm"), S15("tooShort"),
}

enum class LanguagePref { SYSTEM, ZH, EN }

enum class ReportTab { GAZE, TIME, VOICE }

enum class MicPermission { UNKNOWN, GRANTED, DENIED }

/** How the house lights stand (design `audience.lightsLevels`): entry 100 %, talk 15 %, report 70 %. */
enum class Lights(val level: Float) { ENTRY(1f), TALK(0.15f), REPORT(0.7f) }

enum class ImportError { PASSWORD, NOT_SUPPORTED, FAILED }

enum class ConfirmKind { DELETE_DECK, CLEAR_ALL }

/** A hint line at the top of the lectern panel (design M5 / M8 / mic denied). */
enum class Tip { FIRST_RUN, FIRST_TALK, INTERRUPTED, MIC_DENIED, IMPORT_CAPPED, PICKER_MISSING }

data class Confirm(val kind: ConfirmKind, val returnTo: Base, val deckId: String? = null, val sessions: Int = 0)

/** A report on the lectern: the session, the one before it (for "比上次"), read-only when opened from history (M6). */
data class ReportView(val session: Session, val previous: Session?, val readOnly: Boolean, val deck: Deck?)

data class HallUiState(
    val base: Base = Base.S00,
    val loaded: Boolean = false,
    val language: LanguagePref = LanguagePref.SYSTEM,
    val driftOn: Boolean = true,
    val micOn: Boolean = false,
    val micPermission: MicPermission = MicPermission.UNKNOWN,
    val decks: List<Deck> = emptyList(),
    val deckId: String = Deck.SAMPLE_ID,
    val targetMin: Int = 3,
    val tip: Tip? = null,
    // talk
    val page: Int = 0,
    val elapsedSec: Float = 0f,
    val pageSec: Float = 0f,
    val countdown: Int = 3,
    val endFrom: Base = Base.S06,
    val micDot: Boolean = false,
    // import
    val importDone: Int = 0,
    val importTotal: Int = 0,
    val importError: ImportError? = null,
    // report / replay / history
    val report: ReportView? = null,
    val tab: ReportTab = ReportTab.GAZE,
    val replaySec: Float = 0f,
    val replayPlaying: Boolean = false,
    val history: List<Session> = emptyList(),
    val lastSummary: Session? = null,
    val confirm: Confirm? = null,
    // scene
    val lights: Lights = Lights.ENTRY,
    val placeEpoch: Int = 0,
    val tick: Long = 0L,
) {
    val deck: Deck?
        get() = decks.firstOrNull { it.id == deckId }

    val targetSec: Int
        get() = targetMin * 60

    /** M2: at 90 % of the target. */
    val nearTime: Boolean
        get() = base in TALKING && elapsedSec >= targetSec * 0.9f && elapsedSec <= targetSec

    /** M3: over the target. */
    val overTime: Boolean
        get() = base in TALKING && elapsedSec > targetSec

    val noSlides: Boolean
        get() = deck?.noSlides == true

    companion object {
        val TALKING = setOf(Base.S06, Base.S07, Base.S08)
    }
}

/** Every user intent; panels and debug hooks send these to [HallController.onEvent]. */
sealed interface HallEvent {
    data object Start : HallEvent
    data object ChangeDeck : HallEvent
    data object OpenSettings : HallEvent
    data object OpenHistory : HallEvent
    data object Back : HallEvent
    data class TargetStep(val delta: Int) : HallEvent
    data class MicToggle(val on: Boolean) : HallEvent
    data object MicAllow : HallEvent
    data object MicDecline : HallEvent
    data class PickDeck(val id: String) : HallEvent
    data object ImportFiles : HallEvent
    data class ImportNotes(val deckId: String) : HallEvent
    data class DeleteDeck(val id: String) : HallEvent
    data object CancelImport : HallEvent
    data object RetryImport : HallEvent
    data object CancelCountdown : HallEvent
    data object NextPage : HallEvent
    data object PrevPage : HallEvent
    data object Pause : HallEvent
    data object Resume : HallEvent
    data object End : HallEvent
    data object EndConfirmed : HallEvent
    data object KeepTalking : HallEvent
    data object Replace : HallEvent
    data class Tab(val tab: ReportTab) : HallEvent
    data object Replay : HallEvent
    data object ReplayToggle : HallEvent
    data object ReplayBack : HallEvent
    data object Again : HallEvent
    data object Done : HallEvent
    data class OpenSession(val id: String) : HallEvent
    data class Language(val pref: LanguagePref) : HallEvent
    data class Drift(val on: Boolean) : HallEvent
    data object ClearAll : HallEvent
    data object ConfirmYes : HallEvent
    data object ConfirmNo : HallEvent
    data object DismissTip : HallEvent
    data object MicSettings : HallEvent
    /** Scene: the hall has been built and placed. */
    data object SceneReady : HallEvent
    /** Lifecycle: the Stage went to / came back from the background. */
    data class Background(val on: Boolean) : HallEvent
}
