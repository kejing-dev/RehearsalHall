package io.embodit.rehearsalhall.ui.hall

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.SystemClock
import io.embodit.rehearsalhall.domain.Deck
import io.embodit.rehearsalhall.domain.GazeRecorder
import io.embodit.rehearsalhall.domain.LookClass
import io.embodit.rehearsalhall.domain.Session
import io.embodit.rehearsalhall.domain.Voice
import io.embodit.rehearsalhall.domain.VoiceMath
import io.embodit.rehearsalhall.domain.Zones
import io.embodit.rehearsalhall.platform.HallLog
import io.embodit.rehearsalhall.platform.Importer
import io.embodit.rehearsalhall.platform.Mic
import io.embodit.rehearsalhall.platform.MicPermissionActivity
import io.embodit.rehearsalhall.platform.PickerActivity
import io.embodit.rehearsalhall.platform.SampleDeck
import io.embodit.rehearsalhall.platform.Store
import io.embodit.rehearsalhall.ui.hall.components.localizedResources
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * All behaviour of the app, one per process (main thread). State machine of the design (`states[]`, `stateModel`,
 * `exitTable`): panel events and debug hooks come in through [onEvent], the scene reads [state] and writes the head
 * samples straight into [recorder] from its ECS system (never through Compose state, design lockedByCritic).
 */
object HallController {
    private val _state = MutableStateFlow(HallUiState())
    val state: StateFlow<HallUiState> = _state

    private var app: Context? = null
    private var prefs: SharedPreferences? = null
    private var store: Store? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var importJob: Job? = null
    private var lastPickFiles: List<File> = emptyList()

    /** The talk's statistics, fed by the scene's ECS system while [recording] is true. */
    var recorder: GazeRecorder? = null
        private set

    /** Eye height measured at placement (scene); the zone edges follow it. */
    var eyeH: Float = 1.6f

    /** True while the talk runs (S06): the scene adds head samples and the drift timers run. */
    val recording: Boolean
        get() = _state.value.base == Base.S06 && recorder != null

    // talk clock (wall time, so a slow emulator frame rate does not slow it down)
    private var runSinceMs = -1L
    private var runAccumMs = 0L
    private var pageSinceMs = -1L
    private var pageTimes = FloatArray(0)
    private var talkStartedAt = 0L
    private var countdownStartMs = 0L

    // voice
    private var mic: Mic? = null
    private val voiceLock = Any()
    private val countdownFrames = ArrayList<Float>()
    private val talkFrames = ArrayList<Float>()
    private val talkFramePages = ArrayList<Int>()
    @Volatile private var lastFrameDb = -90f

    // replay
    private var replayLastMs = -1L

    // debug
    var driftAfterSec: Float = 60f
    /** Debug `countdown hold`: S05 stays at "2" for screenshots. */
    var holdCountdown = false
    private var fakeVoice: Voice? = null
    private var micNoSignal = false
    private var nameOverride: String? = null

    private val pageCache = LinkedHashMap<String, Bitmap>(8, 0.75f, true)

    fun start(context: Context) {
        if (app != null) return
        val c = context.applicationContext
        app = c
        store = Store(c)
        val p = c.getSharedPreferences("hall", Context.MODE_PRIVATE)
        prefs = p
        val lang = runCatching { LanguagePref.valueOf(p.getString("language", "SYSTEM")!!) }.getOrDefault(LanguagePref.SYSTEM)
        val interrupted = p.getBoolean("interrupted", false)
        p.edit().putBoolean("interrupted", false).apply()
        val firstRun = !p.getBoolean("firstRunDone", false)
        val micPerm = if (MicPermissionActivity.granted(c)) MicPermission.GRANTED else if (p.getBoolean("micAsked", false)) MicPermission.DENIED else MicPermission.UNKNOWN
        _state.value =
            HallUiState(
                language = lang,
                driftOn = p.getBoolean("drift", true),
                micOn = p.getBoolean("micOn", false) && micPerm == MicPermission.GRANTED,
                micPermission = micPerm,
                deckId = p.getString("deck", Deck.SAMPLE_ID) ?: Deck.SAMPLE_ID,
                tip = if (interrupted) Tip.INTERRUPTED else if (firstRun) Tip.FIRST_RUN else null,
            )
        reloadDecks()
        PickerActivity.onResult = { r -> onPicked(r) }
        MicPermissionActivity.onResult = { ok -> onMicPermission(ok) }
        HallLog.i("CONTROLLER started lang=$lang interrupted=$interrupted firstRun=$firstRun")
        logState("start")
    }

    fun resources(): Resources {
        val c = app ?: error("not started")
        return localizedResources(c, _state.value.language)
    }

    private fun zh(): Boolean = resources().getString(io.embodit.rehearsalhall.R.string.lang_code) == "zh"

    private fun update(why: String, f: (HallUiState) -> HallUiState) {
        val before = _state.value
        val after = f(before)
        _state.value = after
        if (after.base != before.base || why.startsWith("!")) logState(why)
    }

    private fun logState(why: String) = HallLog.i("STATE ${designState()} why=$why")

    /** Design state name for QA ("S06+M3", "S09·声音+M1"). */
    fun designState(): String {
        nameOverride?.let { return it }
        val s = _state.value
        val sb = StringBuilder(s.base.name)
        if (s.base == Base.S09) sb.append('·').append(when (s.tab) { ReportTab.GAZE -> "目光"; ReportTab.TIME -> "时间"; ReportTab.VOICE -> "声音" })
        val mods = ArrayList<String>()
        val voiceOff = s.base == Base.S06 && !s.micOn || s.base == Base.S09 && s.report?.session?.voice !is Voice.Metrics
        if (voiceOff) mods += "M1"
        if (s.nearTime) mods += "M2"
        if (s.overTime) mods += "M3"
        if (drifting != null && s.base == Base.S06) mods += "M4"
        if (s.tip == Tip.FIRST_RUN && s.base == Base.S01 || s.tip == Tip.FIRST_TALK && s.base == Base.S06) mods += "M5"
        if (s.report?.readOnly == true && s.base in setOf(Base.S09, Base.S10)) mods += "M6"
        if (s.noSlides && s.base in setOf(Base.S06, Base.S07, Base.S09, Base.S10)) mods += "M7"
        if (s.tip == Tip.INTERRUPTED && s.base == Base.S01) mods += "M8"
        for (m in mods) sb.append('+').append(m)
        return sb.toString()
    }

    /** Column (0 left, 1 centre, 2 right) that is drifting now, written by the scene. */
    @Volatile var drifting: Int? = null

    // =========================================================================================
    // decks
    // =========================================================================================

    private fun sampleDeck(): Deck {
        val r = resources()
        return Deck(Deck.SAMPLE_ID, SampleDeck.title(r), Deck.SOURCE_SAMPLE, SampleDeck.PAGES, SampleDeck.notes(r), 3, 0L)
    }

    private fun noneDeck(): Deck = Deck(Deck.NONE_ID, resources().getString(io.embodit.rehearsalhall.R.string.deck_none), Deck.SOURCE_NONE, 0, emptyList(), 3, 0L)

    private fun reloadDecks(select: String? = null) {
        val st = store ?: return
        val imported = st.loadDecks()
        val decks = listOf(sampleDeck()) + imported + noneDeck()
        val sessions = st.loadSessions()
        update("decks") { s ->
            val id = (select ?: s.deckId).takeIf { want -> decks.any { it.id == want } } ?: Deck.SAMPLE_ID
            val deck = decks.first { it.id == id }
            val target = prefs?.getInt("target_$id", -1)?.takeIf { it > 0 } ?: deck.targetMinutes
            s.copy(decks = decks, deckId = id, targetMin = target, history = sessions.filter { it.deckId == id }.take(10), lastSummary = sessions.firstOrNull { it.deckId == id })
        }
        prefs?.edit()?.putString("deck", _state.value.deckId)?.apply()
    }

    fun pageCount(): Int = _state.value.deck?.pageCount ?: 0

    /** Page image of a deck (sample pages are drawn in the current language), cached. Main thread. */
    fun pageBitmap(deckId: String, page: Int): Bitmap? {
        val key = "$deckId|${_state.value.language}|$page|${zh()}"
        pageCache[key]?.let { return it }
        val bmp =
            when (deckId) {
                Deck.SAMPLE_ID -> SampleDeck.page(resources(), page.coerceIn(0, SampleDeck.PAGES - 1))
                Deck.NONE_ID -> SampleDeck.nameBoard(resources())
                else -> store?.pageBitmap(deckId, page)
            } ?: return null
        pageCache[key] = bmp
        while (pageCache.size > 12) pageCache.remove(pageCache.keys.first())
        return bmp
    }

    // =========================================================================================
    // events
    // =========================================================================================

    fun onEvent(e: HallEvent) {
        val s = _state.value
        HallLog.i("EVENT ${e::class.simpleName} in ${s.base}")
        when (e) {
            HallEvent.SceneReady -> if (s.base == Base.S00) {
                update("scene ready") { it.copy(loaded = true, base = Base.S01, lights = Lights.ENTRY) }
                if (_state.value.tip == Tip.FIRST_RUN) prefs?.edit()?.putBoolean("firstRunDone", true)?.apply()
                scheduleTipTimeout()
            } else if (s.base == Base.S07) update("!replaced in S07") { it }
            HallEvent.Start, HallEvent.Again -> startCountdown()
            HallEvent.ChangeDeck -> go(Base.S02)
            HallEvent.OpenSettings -> go(Base.S11)
            HallEvent.OpenHistory -> if (s.history.isNotEmpty()) go(Base.S12)
            HallEvent.Back -> back()
            is HallEvent.TargetStep -> {
                val v = (s.targetMin + e.delta).coerceIn(1, 60)
                prefs?.edit()?.putInt("target_${s.deckId}", v)?.apply()
                update("target") { it.copy(targetMin = v) }
            }
            is HallEvent.MicToggle -> micToggle(e.on)
            HallEvent.MicAllow -> {
                prefs?.edit()?.putBoolean("micAsked", true)?.apply()
                val c = app
                if (c == null || !MicPermissionActivity.open(c)) onMicPermission(false)
            }
            HallEvent.MicDecline -> {
                prefs?.edit()?.putBoolean("micAsked", true)?.putBoolean("micOn", false)?.apply()
                update("mic declined") { it.copy(micOn = false, base = Base.S01) }
            }
            HallEvent.MicSettings -> openAppSettings()
            is HallEvent.PickDeck -> {
                reloadDecks(e.id)
                go(Base.S01)
            }
            HallEvent.ImportFiles, HallEvent.RetryImport -> {
                importNotesFor = null
                val c = app
                if (c == null || !PickerActivity.open(c, notes = false)) onPicked(PickerActivity.PickResult.NoPicker)
            }
            is HallEvent.ImportNotes -> {
                importNotesFor = e.deckId
                val c = app
                if (c == null || !PickerActivity.open(c, notes = true)) onPicked(PickerActivity.PickResult.NoPicker)
            }
            is HallEvent.DeleteDeck -> {
                val n = store?.loadSessions()?.count { it.deckId == e.id } ?: 0
                update("confirm delete") { it.copy(base = Base.S14, confirm = Confirm(ConfirmKind.DELETE_DECK, Base.S02, e.id, n)) }
            }
            HallEvent.CancelImport -> {
                importJob?.cancel()
                importJob = null
                go(Base.S02)
            }
            HallEvent.CancelCountdown -> cancelCountdown()
            HallEvent.NextPage -> turnPage(+1)
            HallEvent.PrevPage -> turnPage(-1)
            HallEvent.Pause -> if (s.base == Base.S06) pauseTalk(Base.S07)
            HallEvent.Resume -> if (s.base == Base.S07) resumeTalk()
            HallEvent.End -> if (s.base == Base.S06 || s.base == Base.S07) {
                val from = s.base
                if (from == Base.S06) pauseTalk(Base.S08) else update("end confirm") { it.copy(base = Base.S08) }
                update("end from") { it.copy(endFrom = from) }
            }
            HallEvent.KeepTalking -> if (s.base == Base.S08) {
                if (s.endFrom == Base.S06) resumeTalk() else go(Base.S07)
            }
            HallEvent.EndConfirmed -> if (s.base == Base.S08) finishTalk()
            HallEvent.Replace -> update("!replace") { it.copy(placeEpoch = it.placeEpoch + 1, base = if (it.base == Base.S11) Base.S00 else it.base, loaded = it.base != Base.S11) }
            is HallEvent.Tab -> update("!tab") { it.copy(tab = e.tab) }
            HallEvent.Replay -> if (s.report != null) {
                replayLastMs = SystemClock.elapsedRealtime()
                update("replay") { it.copy(base = Base.S10, replaySec = 0f, replayPlaying = true) }
                startTicker()
            }
            HallEvent.ReplayToggle -> {
                val dur = s.report?.session?.durationSec ?: 0f
                replayLastMs = SystemClock.elapsedRealtime()
                update("!replay toggle") { if (!it.replayPlaying && it.replaySec >= dur) it.copy(replaySec = 0f, replayPlaying = true) else it.copy(replayPlaying = !it.replayPlaying) }
                startTicker()
            }
            HallEvent.ReplayBack -> update("replay back") { it.copy(base = Base.S09, replayPlaying = false) }
            HallEvent.Done -> {
                update("done") { it.copy(base = Base.S01, report = null, lights = Lights.ENTRY) }
                reloadDecks()
            }
            is HallEvent.OpenSession -> openSession(e.id)
            is HallEvent.Language -> {
                prefs?.edit()?.putString("language", e.pref.name)?.apply()
                pageCache.clear()
                update("!lang") { it.copy(language = e.pref) }
                reloadDecks()
            }
            is HallEvent.Drift -> {
                prefs?.edit()?.putBoolean("drift", e.on)?.apply()
                update("!drift") { it.copy(driftOn = e.on) }
            }
            HallEvent.ClearAll -> update("confirm clear") { it.copy(base = Base.S14, confirm = Confirm(ConfirmKind.CLEAR_ALL, Base.S11)) }
            HallEvent.ConfirmYes -> {
                val cf = s.confirm ?: return go(Base.S01)
                when (cf.kind) {
                    ConfirmKind.DELETE_DECK -> cf.deckId?.let { store?.deleteDeck(it) }
                    ConfirmKind.CLEAR_ALL -> store?.clearSessions()
                }
                update("confirmed") { it.copy(base = cf.returnTo, confirm = null, deckId = if (cf.deckId == it.deckId) Deck.SAMPLE_ID else it.deckId) }
                reloadDecks()
            }
            HallEvent.ConfirmNo -> update("confirm no") { it.copy(base = it.confirm?.returnTo ?: Base.S01, confirm = null) }
            HallEvent.DismissTip -> update("!tip") { it.copy(tip = null) }
            is HallEvent.Background -> onBackground(e.on)
        }
    }

    private fun go(b: Base) = update("go") { it.copy(base = b) }

    private fun back() {
        val s = _state.value
        when (s.base) {
            Base.S02, Base.S11, Base.S13, Base.S15 -> go(Base.S01)
            Base.S12 -> go(Base.S01)
            Base.S04 -> go(Base.S02)
            Base.S09 -> if (s.report?.readOnly == true) update("back to history") { it.copy(base = Base.S12, report = null, lights = Lights.ENTRY) } else onEvent(HallEvent.Done)
            Base.S14 -> onEvent(HallEvent.ConfirmNo)
            else -> Unit
        }
    }

    private fun scheduleTipTimeout() {
        val tip = _state.value.tip ?: return
        scope.launch {
            delay(8_000)
            if (_state.value.tip == tip) update("!tip timeout") { it.copy(tip = null) }
        }
    }

    // =========================================================================================
    // microphone
    // =========================================================================================

    private fun micToggle(on: Boolean) {
        val s = _state.value
        if (!on) {
            prefs?.edit()?.putBoolean("micOn", false)?.apply()
            update("!mic off") { it.copy(micOn = false) }
            return
        }
        when (s.micPermission) {
            MicPermission.GRANTED -> {
                prefs?.edit()?.putBoolean("micOn", true)?.apply()
                update("!mic on") { it.copy(micOn = true) }
            }
            MicPermission.UNKNOWN -> go(Base.S13)
            MicPermission.DENIED -> {
                // asked before: try the system dialog again, it may come up now
                val c = app
                if (c == null || !MicPermissionActivity.open(c)) onMicPermission(false)
            }
        }
    }

    private fun onMicPermission(ok: Boolean) {
        scope.launch {
            prefs?.edit()?.putBoolean("micOn", ok)?.apply()
            update("!mic permission $ok") { it.copy(micPermission = if (ok) MicPermission.GRANTED else MicPermission.DENIED, micOn = ok, base = if (it.base == Base.S13) Base.S01 else it.base, tip = if (ok) it.tip else Tip.MIC_DENIED) }
            if (!ok) scheduleTipTimeout()
        }
    }

    private fun openAppSettings() {
        val c = app ?: return
        runCatching {
            c.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${c.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { HallLog.w("MIC settings failed: $it") }
    }

    private fun startMic(): Boolean {
        if (micNoSignal) return true
        val m = mic ?: Mic { db -> onMicFrame(db) }.also { mic = it }
        val ok = m.start()
        if (!ok) HallLog.w("MIC could not start: the report will say no signal")
        return ok
    }

    private fun stopMic() {
        mic?.stop()
    }

    private fun onMicFrame(db: Float) {
        lastFrameDb = db
        val s = _state.value
        synchronized(voiceLock) {
            when (s.base) {
                Base.S05 -> countdownFrames += db
                Base.S06 -> {
                    talkFrames += db
                    talkFramePages += s.page
                }
                else -> Unit
            }
        }
    }

    // =========================================================================================
    // talk
    // =========================================================================================

    private fun startCountdown() {
        val s = _state.value
        if (!s.loaded) return
        synchronized(voiceLock) {
            countdownFrames.clear()
            talkFrames.clear()
            talkFramePages.clear()
        }
        drifting = null
        countdownStartMs = SystemClock.elapsedRealtime()
        update("countdown") { it.copy(base = Base.S05, countdown = 3, lights = Lights.TALK, report = null, tab = ReportTab.GAZE, page = 0, elapsedSec = 0f, pageSec = 0f) }
        if (s.micOn) startMic()
        startTicker()
    }

    private fun cancelCountdown() {
        stopMic()
        update("countdown cancel") { it.copy(base = Base.S01, lights = Lights.ENTRY) }
    }

    private fun beginTalk() {
        val s = _state.value
        recorder = GazeRecorder(eyeH)
        runAccumMs = 0L
        runSinceMs = SystemClock.elapsedRealtime()
        pageSinceMs = runSinceMs
        pageTimes = FloatArray(maxOf(1, s.deck?.pageCount ?: 1))
        talkStartedAt = System.currentTimeMillis()
        prefs?.edit()?.putBoolean("interrupted", true)?.apply()
        val firstTalk = prefs?.getBoolean("firstTalkDone", false) != true
        if (firstTalk) prefs?.edit()?.putBoolean("firstTalkDone", true)?.apply()
        update("talk") { it.copy(base = Base.S06, page = 0, elapsedSec = 0f, pageSec = 0f, tip = if (firstTalk) Tip.FIRST_TALK else if (it.tip == Tip.FIRST_TALK) null else it.tip) }
        if (firstTalk) scope.launch {
            delay(5_000)
            if (_state.value.tip == Tip.FIRST_TALK) update("!tip timeout") { it.copy(tip = null) }
        }
    }

    private fun elapsedMs(now: Long = SystemClock.elapsedRealtime()): Long = runAccumMs + if (runSinceMs >= 0) now - runSinceMs else 0L

    private fun closePage(now: Long) {
        if (pageSinceMs >= 0) {
            val i = _state.value.page.coerceIn(0, pageTimes.size - 1)
            pageTimes[i] += (now - pageSinceMs) / 1000f
        }
        pageSinceMs = -1L
    }

    private fun pauseTalk(to: Base) {
        val now = SystemClock.elapsedRealtime()
        runAccumMs = elapsedMs(now)
        runSinceMs = -1L
        closePage(now)
        stopMic()
        update("pause") { it.copy(base = to, elapsedSec = runAccumMs / 1000f) }
    }

    private fun resumeTalk() {
        val now = SystemClock.elapsedRealtime()
        runSinceMs = now
        pageSinceMs = now
        if (_state.value.micOn) startMic()
        update("resume") { it.copy(base = Base.S06) }
        startTicker()
    }

    private fun turnPage(d: Int) {
        val s = _state.value
        if (s.base != Base.S06) return
        val n = s.deck?.pageCount ?: 0
        if (n <= 1) return
        val to = (s.page + d).coerceIn(0, n - 1)
        if (to == s.page) return
        val now = SystemClock.elapsedRealtime()
        closePage(now)
        pageSinceMs = now
        update("!page $to") { it.copy(page = to, pageSec = 0f) }
    }

    /** Debug `page <n>`. */
    fun debugPage(n: Int) {
        val s = _state.value
        if (s.base != Base.S06) return
        turnPage(n - s.page)
    }

    private fun finishTalk() {
        val s = _state.value
        val rec = recorder
        prefs?.edit()?.putBoolean("interrupted", false)?.apply()
        recorder = null
        stopMic()
        val dur = runAccumMs / 1000f
        if (rec == null || dur < MIN_TALK_S) {
            update("too short") { it.copy(base = Base.S15, lights = Lights.ENTRY) }
            return
        }
        val voice: Voice? =
            when {
                fakeVoice != null -> fakeVoice
                !s.micOn -> null
                micNoSignal -> Voice.NoSignal
                else -> synchronized(voiceLock) { VoiceMath.metrics(talkFrames.toList(), talkFramePages.toList(), VoiceMath.floor(countdownFrames)) }
            }
        val session =
            Session(
                id = "s" + talkStartedAt,
                deckId = s.deckId,
                startedAt = talkStartedAt,
                durationSec = dur,
                targetSec = s.targetSec,
                pageTimes = if (s.noSlides) emptyList() else pageTimes.toList(),
                zoneDwell = rec.zone.toList(),
                seatDwell = rec.seat.toList(),
                classTotals = rec.classTotals.toList(),
                samples = rec.samplesBase64(),
                voice = voice,
            )
        val previous = store?.loadSessions()?.firstOrNull { it.deckId == s.deckId }
        store?.saveSession(session)
        HallLog.i(String.format(Locale.US, "SESSION saved id=%s dur=%.1f audience=%.1f zones=%s classes=%s voice=%s", session.id, dur, session.audienceSec, session.zoneDwell.map { String.format(Locale.US, "%.1f", it) }, session.classTotals.map { String.format(Locale.US, "%.1f", it) }, voice))
        showReport(session, previous, readOnly = false)
        reloadDecks()
    }

    private fun showReport(session: Session, previous: Session?, readOnly: Boolean) {
        update("report") { it.copy(base = Base.S09, report = ReportView(session, previous, readOnly, it.decks.firstOrNull { d -> d.id == session.deckId }), tab = ReportTab.GAZE, lights = Lights.REPORT, replaySec = 0f, replayPlaying = false) }
    }

    private fun openSession(id: String) {
        val all = store?.loadSessions() ?: return
        val s = all.firstOrNull { it.id == id } ?: return
        showReport(s, null, readOnly = true)
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker =
            scope.launch {
                while (true) {
                    val s = _state.value
                    val now = SystemClock.elapsedRealtime()
                    when (s.base) {
                        Base.S05 -> {
                            if (holdCountdown) countdownStartMs = now - 1_500
                            val left = 3 - ((now - countdownStartMs) / 1000).toInt()
                            if (left <= 0) beginTalk() else if (left != s.countdown) update("!countdown") { it.copy(countdown = left) }
                        }
                        Base.S06 -> {
                            val e = elapsedMs(now) / 1000f
                            val p = if (pageSinceMs >= 0) (now - pageSinceMs) / 1000f else 0f
                            val dot = s.micOn && (micNoSignal.not() && lastFrameDb > VoiceMath.floor(synchronized(voiceLock) { countdownFrames.toList() }) + VoiceMath.SPEAK_DB)
                            val wasNear = s.nearTime
                            val wasOver = s.overTime
                            update("tick") { it.copy(elapsedSec = e, pageSec = p, micDot = dot, tick = now) }
                            val n = _state.value
                            if (n.nearTime != wasNear || n.overTime != wasOver) logState("time")
                        }
                        Base.S10 -> {
                            val dur = s.report?.session?.durationSec ?: 0f
                            if (s.replayPlaying) {
                                val dt = (now - replayLastMs) / 1000f * REPLAY_SPEED
                                val t = (s.replaySec + dt).coerceAtMost(dur)
                                update("tick") { it.copy(replaySec = t, replayPlaying = t < dur, tick = now) }
                            }
                            replayLastMs = now
                        }
                        else -> return@launch
                    }
                    delay(if (s.base == Base.S10) 100 else 200)
                }
            }
    }

    private fun onBackground(on: Boolean) {
        val s = _state.value
        if (on) {
            when (s.base) {
                Base.S05 -> cancelCountdown()
                Base.S06 -> pauseTalk(Base.S07)
                Base.S10 -> update("!replay bg") { it.copy(replayPlaying = false) }
                else -> Unit
            }
        }
    }

    // =========================================================================================
    // import
    // =========================================================================================

    private var importNotesFor: String? = null

    private fun onPicked(r: PickerActivity.PickResult) {
        scope.launch {
            val notesFor = importNotesFor
            importNotesFor = null
            when (r) {
                PickerActivity.PickResult.NoPicker -> update("!no picker") { it.copy(tip = Tip.PICKER_MISSING, base = if (it.base == Base.S04) Base.S02 else it.base) }
                PickerActivity.PickResult.Cancelled -> if (_state.value.base == Base.S04) go(Base.S02)
                is PickerActivity.PickResult.Files -> if (notesFor != null) importNotes(notesFor, r.files.first()) else importFiles(r.files)
            }
        }
    }

    /** Debug `importPath <file on the device>`: the same path as a picked file. */
    fun importFiles(files: List<File>) {
        val st = store ?: return
        lastPickFiles = files
        val id = "d" + System.currentTimeMillis()
        update("importing") { it.copy(base = Base.S03, importDone = 0, importTotal = 0, importError = null) }
        importJob =
            scope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        Importer.import(st, files, id) { done, total -> scope.launch { if (_state.value.base == Base.S03) update("!import $done/$total") { it.copy(importDone = done, importTotal = total) } } }
                    }
                HallLog.i("IMPORT result=$result")
                when (result) {
                    is Importer.Result.Ok -> {
                        reloadDecks(select = result.deck.id)
                        update("imported") { it.copy(base = if (it.base == Base.S03) Base.S02 else it.base, tip = if (result.capped) Tip.IMPORT_CAPPED else it.tip) }
                    }
                    Importer.Result.Password -> update("import error") { it.copy(base = Base.S04, importError = ImportError.PASSWORD) }
                    Importer.Result.NotSupported -> update("import error") { it.copy(base = Base.S04, importError = ImportError.NOT_SUPPORTED) }
                    is Importer.Result.Failed -> update("import error") { it.copy(base = Base.S04, importError = ImportError.FAILED) }
                }
            }
    }

    private fun importNotes(deckId: String, file: File) {
        val st = store ?: return
        val deck = _state.value.decks.firstOrNull { it.id == deckId } ?: return
        if (deck.source == Deck.SOURCE_SAMPLE || deck.noSlides) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        st.saveDeck(deck.copy(notes = Deck.parseNotes(text, deck.pageCount)))
        reloadDecks()
        HallLog.i("IMPORT notes deck=$deckId")
    }

    // =========================================================================================
    // debug hooks (src/debug drives these; nothing here runs by itself)
    // =========================================================================================

    fun debugState(id: String) {
        nameOverride = null
        // leaving an open talk by a jump: close it like a cancelled one (no session, no M8 next start)
        if ((_state.value.base in HallUiState.TALKING || _state.value.base == Base.S05) && id.uppercase(Locale.US) !in setOf("S06", "S07", "S08")) {
            recorder = null
            runSinceMs = -1L
            stopMic()
            prefs?.edit()?.putBoolean("interrupted", false)?.apply()
        }
        when (id.uppercase(Locale.US)) {
            "S00" -> update("debug") { it.copy(base = Base.S00, loaded = false) }
            "S01" -> update("debug") { it.copy(base = Base.S01, loaded = true, lights = Lights.ENTRY, report = null) }
            "S02" -> go(Base.S02)
            "S03" -> update("debug") { it.copy(base = Base.S03, importDone = 7, importTotal = 24) }
            "S04" -> update("debug") { it.copy(base = Base.S04, importError = ImportError.PASSWORD) }
            "S05" -> startCountdown()
            "S06" -> if (_state.value.base !in HallUiState.TALKING) startCountdownNow()
            "S07" -> {
                if (_state.value.base !in HallUiState.TALKING) startCountdownNow()
                if (_state.value.base == Base.S06) pauseTalk(Base.S07)
            }
            "S08" -> {
                if (_state.value.base !in HallUiState.TALKING) startCountdownNow()
                onEvent(HallEvent.End)
            }
            "S09" -> debugFakeSession(7, open = true)
            "S10" -> {
                if (_state.value.report == null) debugFakeSession(7, open = true)
                onEvent(HallEvent.Replay)
            }
            "S11" -> go(Base.S11)
            "S12" -> {
                if (_state.value.history.isEmpty()) repeat(3) { debugFakeSession(it + 1, open = false) }
                go(Base.S12)
            }
            "S13" -> go(Base.S13)
            "S14" -> update("debug") { it.copy(base = Base.S14, confirm = Confirm(ConfirmKind.CLEAR_ALL, Base.S11)) }
            "S15" -> update("debug") { it.copy(base = Base.S15, lights = Lights.ENTRY) }
        }
    }

    /** S06 at once (no 3 s countdown), for screenshots. */
    private fun startCountdownNow() {
        startCountdown()
        countdownStartMs -= 3_000
    }

    fun debugName(name: String?) {
        nameOverride = name
    }

    fun debugTarget(min: Int) = onEvent(HallEvent.TargetStep(min - _state.value.targetMin))

    fun debugMic(mode: String) {
        when (mode) {
            "on" -> {
                micNoSignal = false
                update("!debug mic on") { it.copy(micOn = true, micPermission = MicPermission.GRANTED) }
            }
            "off" -> {
                micNoSignal = false
                update("!debug mic off") { it.copy(micOn = false) }
            }
            "nosignal" -> {
                micNoSignal = true
                update("!debug mic nosignal") { it.copy(micOn = true, micPermission = MicPermission.GRANTED) }
            }
        }
    }

    fun debugFakeVoice(ratio: Float, pause: Float, db: Float) {
        fakeVoice = Voice.Metrics(ratio, pause, 3, if (pause >= 3f) 2 else 0, db < VoiceMath.QUIET_DBFS)
        update("!debug fakeVoice") { it.copy(micOn = true, micPermission = MicPermission.GRANTED) }
    }

    fun debugFirstRunReset() {
        prefs?.edit()?.remove("firstRunDone")?.remove("firstTalkDone")?.remove("micAsked")?.apply()
        update("!firstRun reset") { it.copy(tip = Tip.FIRST_RUN, micPermission = if (it.micPermission == MicPermission.GRANTED) MicPermission.GRANTED else MicPermission.UNKNOWN) }
        scheduleTipTimeout()
    }

    fun debugKillFlag() {
        prefs?.edit()?.putBoolean("interrupted", true)?.commit()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** A synthetic session (seeded) saved for the current deck; [open] shows its report. */
    fun debugFakeSession(seed: Int, open: Boolean) {
        val s = _state.value
        val rnd = java.util.Random(seed.toLong())
        val dur = 120f + rnd.nextInt(240)
        val pages = maxOf(1, s.deck?.pageCount ?: 1)
        val zone = FloatArray(Zones.COUNT) { z -> if (z == 6 || (seed % 2 == 0 && z == 8)) 0f else 4f + rnd.nextInt(40) }
        val audience = zone.sum()
        val lect = dur * 0.25f
        val back = dur * 0.06f
        val classes = listOf(audience, lect, back, (dur - audience - lect - back).coerceAtLeast(0f))
        val samples = ArrayList<io.embodit.rehearsalhall.domain.Sample>()
        val n = (dur * 10).toInt()
        for (i in 0 until n) {
            val t = i / 10f
            val yaw = 40f * kotlin.math.sin(t / 7f) + 6f * kotlin.math.sin(t * 1.3f)
            val pitch = -4f + 7f * kotlin.math.sin(t / 11f)
            samples += io.embodit.rehearsalhall.domain.Sample(yaw, pitch, LookClass.AUDIENCE, (i * pages / n).coerceIn(0, pages - 1))
        }
        val seat = io.embodit.rehearsalhall.domain.HallLayout.seats.map { st -> if (st.zone == 6) 0f else zone[st.zone] / 15f * (0.5f + rnd.nextFloat()) }
        val session =
            Session(
                id = "s" + (System.currentTimeMillis() - (10 - seed) * 86_400_000L),
                deckId = s.deckId,
                startedAt = System.currentTimeMillis() - (10 - seed) * 86_400_000L,
                durationSec = dur,
                targetSec = s.targetSec,
                pageTimes = if (s.noSlides) emptyList() else List(pages) { dur / pages * (0.6f + rnd.nextFloat() * 0.8f) },
                zoneDwell = zone.toList(),
                seatDwell = seat,
                classTotals = classes,
                samples = io.embodit.rehearsalhall.domain.Samples.encode(samples),
                voice = fakeVoice ?: if (s.micOn) Voice.Metrics(0.78f, 6f, 3, 2, false) else null,
            )
        val previous = store?.loadSessions()?.firstOrNull { it.deckId == s.deckId }
        store?.saveSession(session)
        reloadDecks()
        if (open) showReport(session, previous, readOnly = false)
    }

    fun dump(): String {
        val s = _state.value
        val r = recorder
        return String.format(
            Locale.US,
            "DUMP state=%s deck=%s pages=%d page=%d elapsed=%.1f target=%d mic=%s/%s lights=%s eyeH=%.2f drifting=%s rec=%s",
            designState(), s.deckId, pageCount(), s.page, s.elapsedSec, s.targetSec, s.micOn, s.micPermission, s.lights, eyeH, drifting,
            r?.let { "zones=" + it.zone.map { z -> String.format(Locale.US, "%.1f", z) } + " classes=" + it.classTotals.map { c -> String.format(Locale.US, "%.1f", c) } + " samples=" + it.sampleCount } ?: "none",
        )
    }

    const val MIN_TALK_S = 15f
    const val REPLAY_SPEED = 4f
}
