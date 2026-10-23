package io.embodit.rehearsalhall.ui.hall.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.rehearsalhall.domain.GazeClassifier
import io.embodit.rehearsalhall.domain.HallLayout
import io.embodit.rehearsalhall.domain.HeadPose
import io.embodit.rehearsalhall.domain.LookClass
import io.embodit.rehearsalhall.domain.Quat4
import io.embodit.rehearsalhall.domain.SeatTint
import io.embodit.rehearsalhall.domain.Samples
import io.embodit.rehearsalhall.domain.Vec3
import io.embodit.rehearsalhall.domain.Yaw
import io.embodit.rehearsalhall.domain.Zones
import io.embodit.rehearsalhall.platform.HallLog
import io.embodit.rehearsalhall.platform.tracking.ControllerTriggers
import io.embodit.rehearsalhall.platform.tracking.HeadTracker
import io.embodit.rehearsalhall.ui.hall.Base
import io.embodit.rehearsalhall.ui.hall.HallController
import io.embodit.rehearsalhall.ui.hall.HallEvent
import io.embodit.rehearsalhall.ui.hall.HallUiState
import io.embodit.rehearsalhall.ui.hall.components.PanelSizes
import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The scene of the open Stage (debug hooks and the frame system talk to it). Main thread only. */
internal object ActiveScene {
    var current: HallScene? = null
}

/** The one per-frame ECS system of the app (`registerSystem` in HallScreen). */
class HallFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.current?.onFrame(context)
    }
}

object PanelIds {
    const val LECTERN = "lectern"

    fun sign(i: Int) = "sign$i"

    val pool: List<String> = listOf(LECTERN) + (0 until Zones.COUNT).map { sign(it) }
}

/**
 * The 3D side (design `entities3d`, `spatialLayout`, `gazeModel`, `audience`): the hall and its 168 cartoon listeners, the
 * screen, the lights, the report overlay and the replay beam, plus the panel pool. Per-frame work happens in [onFrame]
 * only (one ECS system): placement, the head samples into [HallController.recorder], the drift and nod timers, the
 * listeners' heads (a sixth of them per frame), the lights, the screen and the panels. Main thread only.
 */
class HallScene(private val sink: (HallEvent) -> Unit) {
    val panelShown = mutableStateMapOf<String, Boolean>()
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private val head = HeadTracker()
    private val triggers = ControllerTriggers()
    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var hallRoot: Entity? = null
    private var rig: PanelRig? = null
    private var shared: SharedResources? = null
    private var mats: HallMaterials? = null
    private var buildJob: Job? = null
    private var built = false
    private var attached = false
    private var paused = false

    private var stageMesh: MeshResource? = null
    private var tintMesh: MeshResource? = null
    private var tintEntity: ModelEntity? = null
    private var tintFor: String? = null
    private var screen: ModelEntity? = null
    private var screenKey = ""
    private var beam: ModelEntity? = null
    private var spot: Entity? = null
    private val faceMeshes = HashMap<Face, MeshResource>()
    private val members = ArrayList<Member>(HallLayout.SEAT_COUNT)

    private var pose = HeadPose(Vec3(0f, 1.6f, 0f), Quat4.IDENTITY)
    private var hasHead = false
    private var ui = HallUiState()
    private var frames = 0L
    private var lastLogMs = 0L
    private var lastFrameMs = 0L

    // placement (design spatialLayout.placement): wait 1.0 s, then the median of 0.5 s of head poses
    private var placeEpoch = -1
    private var placing = false
    private var placeStartMs = 0L
    private val placeSamples = ArrayList<Pair<Vec3, Float>>()
    private var placedAtMs = -1L
    private var p0 = Vec3.ZERO
    private var yaw0 = 0f
    private var eyeH = 1.6f
    private var placed = false

    // lights
    private var lightLevel = 1f

    // drift / nod (design audience.drift, audienceStyle.behaviors)
    private val unseen = FloatArray(3)
    private val seenAcc = FloatArray(3)
    private val nodAcc = FloatArray(Zones.COUNT)
    private var talkSec = 0f
    private var clapStartMs = -1L

    /** Debug `setHead yaw pitch`: overrides the head direction (hall-local, degrees, + right / + up). */
    var debugHead: Pair<Float, Float>? = null

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        // An entity added to the SpatialView is not at the Stage origin (Spatial Ruler): move the root once so that its
        // local space equals Stage space.
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        HallLog.i("STAGE root offset=${HallLog.pos(offset.domain())}")
        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        rig.world(PanelIds.LECTERN, PanelSizes.LECTERN_W, PanelSizes.LECTERN_H, LECTERN_K).maxPitch = 1.4f
        for (i in 0 until Zones.COUNT) rig.world(PanelIds.sign(i), PanelSizes.SIGN_W, PanelSizes.SIGN_H, SIGN_K).maxPitch = 0.6f
        head.start()
        triggers.start()
        ActiveScene.current = this
        attached = true
        bindPanels(attachments)
        buildJob =
            scope?.launch {
                try {
                    build(root, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) HallLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
        HallLog.i("SCENE attached")
    }

    /** Materials and meshes; the build yields between steps (a mesh costs about a frame; Spatial Ruler ANR). */
    private suspend fun build(root: Entity, t0: Long) {
        delay(300)
        val shared = SharedResources()
        this.shared = shared
        val m = HallMaterials(shared)
        mats = m
        val hall = Entity().named("hall")
        root.addChild(hall)
        hallRoot = hall
        hall.setOpacity(0f)
        frame()
        model(shared.keep(Figures.house().build("house")), m.house, "house", hall)
        frame()
        model(shared.keep(Figures.glow().build("glow")), m.glow, "glow", hall)
        stageMesh = shared.keep(Figures.stage(eyeH).build("stage"))
        model(stageMesh!!, m.stage, "stage", hall)
        frame()
        val seats = Toon(HallLayout.SEAT_COUNT * 120)
        for (s in HallLayout.seats) seats.at(Quat4.axisAngle(Vec3.UP, s.facing), s.floor) { Figures.seat(this) }
        model(shared.keep(seats.build("seats")), m.house, "seats", hall)
        frame()
        val looks = HallLayout.seats.map { lookOf(it) }
        val bodies = Toon(HallLayout.SEAT_COUNT * 120)
        for (s in HallLayout.seats) bodies.at(Quat4.axisAngle(Vec3.UP, s.facing), s.floor) { Figures.torso(this, looks[s.index].clothes, looks[s.index].skin) }
        model(shared.keep(bodies.build("bodies")), m.house, "bodies", hall)
        frame()
        // screen
        val sc = model(shared.keep(screenQuad()), m.screen, "screen", hall)
        sc.setLocal(position = Vec3(0f, HallLayout.SCREEN_BOTTOM + HallLayout.SCREEN_H / 2, HallLayout.SCREEN_Z), rotation = Quat4.axisAngle(Vec3.UP, PI.toFloat()))
        screen = sc
        // spot: a soft cone from the ceiling and a warm pool on the stage around P
        val sp = Entity().named("spot")
        hall.addChild(sp)
        val cone = model(shared.keep(Figures.cone(6.6f, 9f, Palette.LAMP).build("spotCone")), m.spot, "spotCone", sp)
        cone.setLocal(position = Vec3(0f, 7.2f, 0.6f), rotation = Quat4.fromTo(Vec3(0f, 0f, 1f), Vec3(0f, -7.2f, -0.6f).normalized()))
        model(shared.keep(Shapes.disc("spotFloor")), m.spotFloor, "spotFloor", sp).setLocal(position = Vec3(0f, 0.004f, 0f), rotation = Quat4.axisAngle(Vec3.RIGHT, -(PI / 2).toFloat()), scale = Vec3(2.4f, 2.4f, 1f))
        sp.enabled = false
        spot = sp
        // replay beam (design replayBeam: 10° cone, 13 m)
        beam = model(shared.keep(Figures.cone(13f, 5f, Palette.LAMP).build("beam")), m.beam, "beam", hall).also { it.enabled = false }
        tintMesh = shared.keep(MeshBuilder().build("tints"))
        tintEntity = model(tintMesh!!, m.glow, "tints", hall).also { it.enabled = false }
        frame()
        // faces (shared), then the listeners in batches
        for (f in Face.entries) faceMeshes[f] = shared.keep(Figures.face(f).build("face_$f"))
        val armsCache = HashMap<Long, MeshResource>()
        val phoneMesh = shared.keep(Figures.phone().build("phone"))
        for (s in HallLayout.seats) {
            val look = looks[s.index]
            val headMesh = shared.keep(Figures.head(look.skin, look.hair, look.style, look.glasses, look.beanie).build("head${s.index}"))
            val h = model(headMesh, m.house, "head${s.index}", hall)
            val face = model(faceMeshes[Face.NEUTRAL]!!, m.house, "face${s.index}", h)
            val key = look.clothes * 1000L + look.skin
            val armsMesh = armsCache.getOrPut(key) { shared.keep(Figures.arms(look.clothes, look.skin).build("arms$key")) }
            val arms = model(armsMesh, m.house, "arms${s.index}", hall)
            val phone = model(phoneMesh, m.glow, "phone${s.index}", arms).also { it.enabled = false }
            members += Member(s, look, h, face, arms, phone)
            if (s.index % 8 == 7) frame()
        }
        built = true
        HallLog.i("SCENE built ms=${SystemClock.elapsedRealtime() - t0} members=${members.size} styles=${looks.map { it.style }.toSet()} armsMeshes=${armsCache.size}")
        render(ui)
    }

    private suspend fun frame() = delay(16)

    /** A unit quad facing +Z sized to the screen. */
    private fun screenQuad(): MeshResource {
        val b = MeshBuilder(4)
        val n = Vec3(0f, 0f, 1f)
        val w = HallLayout.SCREEN_W / 2
        val h = HallLayout.SCREEN_H / 2
        b.quad(
            b.vertex(Vec3(-w, -h, 0f), n, com.pico.spatial.core.math.Vector2(0f, 1f)),
            b.vertex(Vec3(w, -h, 0f), n, com.pico.spatial.core.math.Vector2(1f, 1f)),
            b.vertex(Vec3(w, h, 0f), n, com.pico.spatial.core.math.Vector2(1f, 0f)),
            b.vertex(Vec3(-w, h, 0f), n, com.pico.spatial.core.math.Vector2(0f, 0f)),
        )
        return b.build("screen")
    }

    fun pause() {
        if (paused || !attached) return
        paused = true
        head.stop()
        triggers.stop()
        HallLog.i("SCENE paused")
    }

    fun resume() {
        if (!paused || !attached) return
        paused = false
        head.start()
        triggers.start()
        lastFrameMs = 0L
        HallLog.i("SCENE resumed")
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        if (!rig.bind(attachments, PanelIds.pool, SystemClock.elapsedRealtime())) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, 250L)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        if (ActiveScene.current === this) ActiveScene.current = null
        main.removeCallbacksAndMessages(null)
        buildJob?.cancel()
        scope?.cancel()
        scope = null
        head.stop()
        triggers.stop()
        panelShown.clear()
        members.clear()
        faceMeshes.clear()
        runCatching { rig?.release() }
        runCatching { stageRoot?.destroy(true) }
        mats?.close()
        shared?.close()
        shared = null
        mats = null
        stageRoot = null
        hallRoot = null
        rig = null
        content = null
        built = false
        placed = false
        placeEpoch = -1
        hasHead = false
        HallLog.i("SCENE detached")
    }

    // =========================================================================================
    // state -> entities
    // =========================================================================================

    fun render(state: HallUiState) {
        val before = ui
        ui = state
        if (!built) return
        if (state.base == Base.S09 && before.base != Base.S09 && before.base != Base.S10 && state.report?.readOnly != true) clapStartMs = SystemClock.elapsedRealtime()
        if (state.base == Base.S05 && before.base != Base.S05) {
            talkSec = 0f
            unseen.fill(0f)
            seenAcc.fill(0f)
            nodAcc.fill(0f)
            HallController.drifting = null
        }
        if (state.base !in HallUiState.TALKING) HallController.drifting = null
        // report overlay
        val report = state.report
        val showTints = report != null && (state.base == Base.S09 || state.base == Base.S10)
        if (showTints && tintFor != report!!.session.id) {
            val levels = SeatTint.levels(report.session.seatDwell)
            Figures.tints(levels).b.replace(tintMesh!!)
            tintFor = report.session.id
        }
        tintEntity?.enabled = showTints
        beam?.enabled = state.base == Base.S10
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, MAX_DT)
        lastFrameMs = now
        frames++
        head.pose()?.let {
            pose = it
            hasHead = it.position.length > 1e-3f
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(pose)
        if (!attached || paused) return
        val state = ui
        if (built) {
            stepPlacement(state, now)
            if (placed) {
                stepFade(now)
                stepGaze(state, dt, now)
                stepAudience(state, now)
                stepLights(state, dt)
                stepScreen(state)
                stepBeam(state)
                stepTriggers(state, now)
            }
        }
        placePanels(state)
        rig.faceViewer(pose.position)
        if (now - lastLogMs > LOG_MS) {
            lastLogMs = now
            val l = local()
            HallLog.i(String.format(Locale.US, "FRAME n=%d head=%s local=%s fwdLocal=%s state=%s light=%.2f controller(%s)", frames, HallLog.pos(pose.position), HallLog.pos(l.first), HallLog.pos(l.second), HallController.designState(), lightLevel, triggers.status()))
        }
    }

    /** Head position and forward in hall-local metres (debug `setHead` overrides the direction). */
    private fun local(): Pair<Vec3, Vec3> {
        val inv = Quat4.axisAngle(Vec3.UP, -yaw0)
        val dh = debugHead
        if (dh != null) return Vec3(0f, eyeH, 0f) to GazeClassifier.direction(dh.first, dh.second)
        val h = if (hasHead) inv.rotate(pose.position - p0) else Vec3(0f, eyeH, 0f)
        val f = inv.rotate(pose.forward).normalized()
        return h to f
    }

    private fun toStage(local: Vec3): Vec3 = p0 + Quat4.axisAngle(Vec3.UP, yaw0).rotate(local)

    private fun stepPlacement(state: HallUiState, now: Long) {
        if (state.placeEpoch != placeEpoch && !placing) {
            placing = true
            placeStartMs = now
            placeSamples.clear()
            HallLog.i("PLACE start epoch=${state.placeEpoch}")
        }
        if (!placing) return
        val t = now - placeStartMs
        if (t >= PLACE_WAIT_MS && hasHead) placeSamples += pose.position to pose.yaw(0f)
        val enough = t >= PLACE_WAIT_MS + PLACE_WINDOW_MS && placeSamples.isNotEmpty()
        val timedOut = t >= PLACE_TIMEOUT_MS
        if (!enough && !timedOut) return
        val (pos, yaw) =
            if (placeSamples.isEmpty()) Vec3(0f, 1.6f, 0f) to 0f
            else {
                val xs = placeSamples.map { it.first.x }.sorted()
                val ys = placeSamples.map { it.first.y }.sorted()
                val zs = placeSamples.map { it.first.z }.sorted()
                val ref = placeSamples.last().second
                val yaws = placeSamples.map { ref + Yaw.wrap(it.second - ref) }.sorted()
                Vec3(xs[xs.size / 2], ys[ys.size / 2], zs[zs.size / 2]) to Yaw.wrap(yaws[yaws.size / 2])
            }
        placing = false
        placeEpoch = state.placeEpoch
        p0 = Vec3(pos.x, 0f, pos.z)
        yaw0 = yaw
        val eye = pos.y.coerceIn(1.1f, 2.0f)
        if (abs(eye - eyeH) > 0.01f || stageMesh == null) {
            eyeH = eye
            stageMesh?.let { Figures.stage(eyeH).b.replace(it) }
        }
        eyeH = eye
        HallController.eyeH = eyeH
        hallRoot?.setLocal(position = p0, rotation = Quat4.axisAngle(Vec3.UP, yaw0))
        if (!placed) placedAtMs = now
        placed = true
        HallLog.i(String.format(Locale.US, "PLACE done P=%s yaw=%.1f eyeH=%.2f samples=%d edges=%s", HallLog.pos(p0), Math.toDegrees(yaw0.toDouble()), eyeH, placeSamples.size, Zones.edges(eyeH)))
        sink(HallEvent.SceneReady)
    }

    private fun stepFade(now: Long) {
        if (placedAtMs < 0) return
        val q = ((now - placedAtMs) / FADE_MS).coerceIn(0f, 1f)
        hallRoot?.setOpacity(q)
        if (q >= 1f) placedAtMs = -1L
    }

    // ---------------------------------------------------------------- gaze, drift, nod

    private fun stepGaze(state: HallUiState, dt: Float, now: Long) {
        val rec = HallController.recorder ?: return
        if (!HallController.recording || dt <= 0f) return
        val (h, f) = local()
        val look = rec.step(dt, f, h, state.page)
        talkSec += dt
        val col = if (look.cls == LookClass.AUDIENCE) Zones.column(look.zone) else -1
        // drift: a column nobody looked at (< 0.5 s) for driftAfter seconds bows its middle and back rows to phones
        for (c in 0 until 3) {
            if (c == col) {
                seenAcc[c] += dt
                if (seenAcc[c] >= SEEN_S) {
                    seenAcc[c] = 0f
                    unseen[c] = 0f
                    if (HallController.drifting == c) {
                        HallController.drifting = null
                        HallLog.i("DRIFT end col=$c")
                    }
                    continue
                }
            }
            unseen[c] += dt
        }
        val after = HallController.driftAfterSec
        if (state.driftOn && HallController.drifting == null && talkSec >= after) {
            val worst = (0 until 3).maxByOrNull { unseen[it] }!!
            if (unseen[worst] >= after) {
                HallController.drifting = worst
                driftStartMs = now
                HallLog.i(String.format(Locale.US, "DRIFT start col=%d unseen=%.1f talk=%.1f", worst, unseen[worst], talkSec))
            }
        }
        if (!state.driftOn && HallController.drifting != null) HallController.drifting = null
        // nod: 1.5 s on a zone -> 2 or 3 of its people nod and smile (each at most once per 20 s)
        if (look.cls == LookClass.AUDIENCE) {
            nodAcc[look.zone] += dt
            if (nodAcc[look.zone] >= NOD_AFTER_S) {
                nodAcc[look.zone] = 0f
                val pool = members.filter { it.seat.zone == look.zone && now - it.lastNodMs > NOD_COOLDOWN_MS && !it.phone }
                pool.shuffled(rnd).take(2 + rnd.nextInt(2)).forEach {
                    it.nodStartMs = now
                    it.lastNodMs = now
                }
            }
        }
    }

    private var driftStartMs = 0L
    private val rnd = Random(42)

    // ---------------------------------------------------------------- audience

    private fun stepAudience(state: HallUiState, now: Long) {
        if (members.isEmpty()) return
        val batch = (frames % BATCHES).toInt()
        val (h, _) = local()
        val speaker = h
        val talking = state.base in setOf(Base.S05, Base.S06, Base.S07, Base.S08, Base.S09, Base.S10)
        val drifting = HallController.drifting
        val clapping = clapStartMs > 0 && now - clapStartMs < CLAP_MS + 400
        val frozen = state.base == Base.S07
        for (i in batch until members.size step BATCHES) {
            val m = members[i]
            val dtS = if (m.lastMs == 0L) 0.1f else ((now - m.lastMs) / 1000f).coerceIn(0f, 2f)
            m.lastMs = now
            // phone (M4): middle and back rows of the drifting column, each after its own delay within 3 s
            val wantsPhone = drifting != null && Zones.column(m.seat.zone) == drifting && m.seat.row >= 3 && now - driftStartMs >= m.delayMs
            if (wantsPhone != m.phone) {
                m.phone = wantsPhone
                m.phoneEntity.enabled = wantsPhone
            }
            if (frozen) continue
            // target direction
            if (!talking && now >= m.nextTargetMs) {
                m.nextTargetMs = now + 3_000 + rnd.nextInt(5_000)
                m.idleTarget =
                    when (rnd.nextInt(10)) {
                        in 0..5 -> null
                        6 -> SCREEN
                        else -> HallLayout.seats.getOrNull(m.seat.index + if (rnd.nextBoolean()) 1 else -1)?.head
                    }
            }
            if (talking && now >= m.nextGlanceMs) {
                m.nextGlanceMs = now + 8_000 + rnd.nextInt(12_000)
                m.glanceUntilMs = now + 1_000 + rnd.nextInt(1_000)
            }
            val glancing = talking && now < m.glanceUntilMs && state.base != Base.S09
            val target =
                when {
                    !talking -> m.idleTarget ?: speaker
                    glancing -> SCREEN
                    else -> speaker
                }
            val d = target - m.seat.head
            var wantYaw = Yaw.wrap(atan2(d.x, d.z) - m.seat.facing) + m.offYaw
            var wantPitch = atan2(d.y, hypot(d.x, d.z)) + m.offPitch
            if (m.phone) {
                wantYaw = m.offYaw * 0.5f
                wantPitch = PHONE_PITCH
            }
            wantYaw = wantYaw.coerceIn(-MAX_YAW, MAX_YAW)
            wantPitch = wantPitch.coerceIn(-0.75f, 0.7f)
            val step = TURN_RATE * dtS
            m.yaw += (wantYaw - m.yaw).coerceIn(-step, step)
            m.pitch += (wantPitch - m.pitch).coerceIn(-step, step)
            // nod: two dips over 1 s
            val nodT = (now - m.nodStartMs) / 1000f
            val nodding = nodT in 0f..1f
            val nod = if (nodding) -0.21f * sin(nodT * 2 * PI.toFloat() * 2f).let { max(0f, it) } else 0f
            val bob = 0.006f * sin((now / 4000f + m.phase) * 2 * PI.toFloat())
            m.head.setLocal(position = m.seat.head + Vec3(0f, bob, 0f), rotation = Quat4.axisAngle(Vec3.UP, m.seat.facing + m.yaw) * Quat4.axisAngle(Vec3.RIGHT, -(m.pitch + nod)))
            // arms: on the lap, raised to the phone, or clapping
            val clapOn = clapping && now - clapStartMs >= m.delayMs / 10 && now - clapStartMs < CLAP_MS + m.delayMs / 10
            val raise = when {
                clapOn -> 0.96f
                m.phone -> 0.44f
                else -> 0f
            }
            val squeeze = if (clapOn) (if (((now - clapStartMs) / 140) % 2 == 0L) 0.7f else 1f) else 1f
            if (raise != m.raise || squeeze != m.squeeze) {
                m.raise = raise
                m.squeeze = squeeze
                m.arms.setLocal(
                    position = m.seat.floor + Quat4.axisAngle(Vec3.UP, m.seat.facing).rotate(Vec3(0f, Figures.SHOULDER_Y, 0f)),
                    rotation = Quat4.axisAngle(Vec3.UP, m.seat.facing) * Quat4.axisAngle(Vec3.RIGHT, -raise),
                    scale = Vec3(squeeze, 1f, 1f),
                )
            }
            // face
            if (now >= m.nextBlinkMs) {
                m.nextBlinkMs = now + 3_000 + rnd.nextInt(3_000)
                m.blinkUntilMs = now + BLINK_MS
            }
            val face =
                when {
                    m.phone -> Face.PHONE
                    clapOn -> Face.CLAP
                    nodding -> Face.SMILE
                    state.base == Base.S09 && clapStartMs > 0 && now - clapStartMs < CLAP_MS + 4_000 -> Face.SMILE
                    now < m.blinkUntilMs -> Face.BLINK
                    else -> Face.NEUTRAL
                }
            if (face != m.faceKind) {
                m.faceKind = face
                faceMeshes[face]?.let { mesh -> m.face.components[ModelComponent::class.java]?.mesh = mesh }
            }
        }
    }

    // ---------------------------------------------------------------- lights, screen, beam

    private fun stepLights(state: HallUiState, dt: Float) {
        val want = if (state.base == Base.S00) 1f else state.lights.level
        if (abs(want - lightLevel) < 1e-3f && lightsWritten) return
        val step = dt / LIGHT_FADE_S * 0.85f
        lightLevel += (want - lightLevel).coerceIn(-step, step)
        val m = mats ?: return
        m.tint(m.house, 0.2f + 0.8f * lightLevel)
        m.tint(m.stage, 0.5f + 0.5f * lightLevel, warm = true)
        spot?.enabled = lightLevel < 0.95f
        lightsWritten = true
    }

    private var lightsWritten = false

    private fun stepScreen(state: HallUiState) {
        val report = state.report
        val deckId = when {
            (state.base == Base.S09 || state.base == Base.S10) && report != null -> report.session.deckId
            else -> state.deckId
        }
        val deck = state.decks.firstOrNull { it.id == deckId }
        val page =
            when {
                deck == null || deck.noSlides -> 0
                state.base == Base.S10 && report != null -> replaySample(state)?.page ?: 0
                state.base in HallUiState.TALKING -> state.page
                else -> 0
            }
        val key = "$deckId|$page|${state.language}"
        if (key == screenKey) return
        val bmp = HallController.pageBitmap(if (deck == null) io.embodit.rehearsalhall.domain.Deck.NONE_ID else deckId, page) ?: return
        mats?.setScreen(bmp)
        screenKey = key
        HallLog.i("SCREEN deck=$deckId page=$page")
    }

    private var replayCache: Pair<String, List<io.embodit.rehearsalhall.domain.Sample>>? = null

    private fun replaySample(state: HallUiState): io.embodit.rehearsalhall.domain.Sample? {
        val s = state.report?.session ?: return null
        val list = replayCache?.takeIf { it.first == s.id }?.second ?: Samples.decode(s.samples).also { replayCache = s.id to it }
        if (list.isEmpty()) return null
        return list[(state.replaySec * 10).toInt().coerceIn(0, list.size - 1)]
    }

    private fun stepBeam(state: HallUiState) {
        val b = beam ?: return
        if (state.base != Base.S10) return
        val smp = replaySample(state) ?: return
        val dir = GazeClassifier.direction(smp.yawDeg, smp.pitchDeg)
        b.setLocal(position = Vec3(0f, eyeH, 0f), rotation = Quat4.fromTo(Vec3(0f, 0f, 1f), dir))
    }

    // ---------------------------------------------------------------- controller triggers (T8)

    private var pendingRight = -1L
    private var pendingLeft = -1L

    private fun stepTriggers(state: HallUiState, now: Long) {
        val (r, l) = triggers.take()
        if (state.base != Base.S06) {
            pendingRight = -1L
            pendingLeft = -1L
            return
        }
        if (r > 0) pendingRight = now
        if (l > 0) pendingLeft = now
        // a press that also hit the lectern panel is a panel press, not a page turn
        val panelSince = lastPanelPointerMs
        if (pendingRight > 0 && now - pendingRight >= TRIGGER_SETTLE_MS) {
            if (panelSince < pendingRight - TRIGGER_SETTLE_MS) sink(HallEvent.NextPage)
            HallLog.i("TRIGGER right panel=${panelSince >= pendingRight - TRIGGER_SETTLE_MS}")
            pendingRight = -1L
        }
        if (pendingLeft > 0 && now - pendingLeft >= TRIGGER_SETTLE_MS) {
            if (panelSince < pendingLeft - TRIGGER_SETTLE_MS) sink(HallEvent.PrevPage)
            HallLog.i("TRIGGER left panel=${panelSince >= pendingLeft - TRIGGER_SETTLE_MS}")
            pendingLeft = -1L
        }
    }

    // ---------------------------------------------------------------- panels

    private fun placePanels(state: HallUiState) {
        val rig = rig ?: return
        if (!placed || !built) {
            for (id in PanelIds.pool) rig[id]?.hide()
            return
        }
        // the lectern panel keeps facing the speaker's place (no yaw swing towards the head) and tilts towards the eyes
        // within 20 ... 70°: no corner can reach the lectern below it (QA q1-1)
        val panel = HallLayout.lecternPanel(eyeH)
        val (h, _) = local()
        val tilt = atan2(h.y - panel.y, (h.z - panel.z).coerceAtLeast(0.05f)).coerceIn(PANEL_MIN_TILT, PANEL_MAX_TILT)
        rig[PanelIds.LECTERN]?.facePoint = toStage(panel + Vec3(0f, sin(tilt), kotlin.math.cos(tilt)))
        showAt(PanelIds.LECTERN, toStage(panel))
        val signs = state.report != null && (state.base == Base.S09 || state.base == Base.S10)
        for (z in 0 until Zones.COUNT) {
            if (!signs) {
                rig[PanelIds.sign(z)]?.hide()
                continue
            }
            showAt(PanelIds.sign(z), toStage(signPoint(z)))
        }
    }

    private fun signPoint(zone: Int): Vec3 {
        val yaw = when (Zones.column(zone)) { 0 -> -32f; 1 -> 0f; else -> 32f }
        val row = Zones.tier(zone) * 3 + 1
        val r = HallLayout.rowRadius(row)
        val a = Math.toRadians(yaw.toDouble())
        return Vec3((r * sin(a)).toFloat(), HallLayout.headY(row) + 1.2f + 0.18f, (-r * kotlin.math.cos(a)).toFloat())
    }

    private fun showAt(id: String, at: Vec3) {
        val slot = rig?.get(id) ?: return
        if (slot.shown) slot.moveTo(at) else slot.show(at)
    }

    // ---------------------------------------------------------------- debug

    fun poseLine(): String {
        val (h, f) = local()
        return String.format(Locale.US, "POSE hmd pos=%s fwd=%s yaw=%.1f pitch=%.1f valid=%s local=%s fwdLocal=%s yawLocal=%.1f pitchLocal=%.1f", HallLog.pos(pose.position), HallLog.pos(pose.forward), Math.toDegrees(pose.yaw(0f).toDouble()), Math.toDegrees(pose.pitch.toDouble()), hasHead, HallLog.pos(h), HallLog.pos(f), GazeClassifier.horizontalDeg(f), GazeClassifier.pitchDeg(f))
    }

    fun panelLines(): List<String> = rig?.dump() ?: emptyList()

    fun audienceLine(): String =
        "AUDIENCE members=${members.size} phones=${members.count { it.phone }} nodding=${members.count { SystemClock.elapsedRealtime() - it.nodStartMs < 1000 }} faces=${members.groupingBy { it.faceKind }.eachCount()} unseen=${unseen.map { String.format(Locale.US, "%.1f", it) }} drifting=${HallController.drifting}"

    // ---------------------------------------------------------------- the listeners

    /** Looks per seat, fixed by the seat number (design: the same people every time). */
    internal data class Look(val skin: Int, val hair: Int, val clothes: Int, val style: Int, val glasses: Boolean, val beanie: Int)

    private val lookCache = HashMap<Int, Look>()

    private fun lookOf(s: HallLayout.Seat): Look =
        lookCache.getOrPut(s.index) {
            val r = Random(s.index * 7919L + 13)
            val prevClothes = if (s.inRow > 0) lookCache[s.index - 1]?.clothes else null
            var clothes = Palette.CLOTHES[r.nextInt(Palette.CLOTHES.size)]
            while (clothes == prevClothes) clothes = Palette.CLOTHES[r.nextInt(Palette.CLOTHES.size)]
            Look(
                skin = Palette.SKIN[r.nextInt(Palette.SKIN.size)],
                hair = Palette.HAIR[r.nextInt(Palette.HAIR.size)],
                clothes = clothes,
                style = (s.index * 5 + r.nextInt(3)) % 6,
                glasses = r.nextFloat() < 0.2f,
                beanie = Palette.CLOTHES[r.nextInt(Palette.CLOTHES.size)],
            )
        }

    private class Member(val seat: HallLayout.Seat, val look: Look, val head: ModelEntity, val face: ModelEntity, val arms: ModelEntity, val phoneEntity: ModelEntity) {
        private val r = Random(seat.index * 31L + 7)
        val offYaw = Math.toRadians((r.nextFloat() * 8 - 4).toDouble()).toFloat()
        val offPitch = Math.toRadians((r.nextFloat() * 6 - 3).toDouble()).toFloat()
        val phase = r.nextFloat()
        val delayMs = r.nextInt(3_000).toLong()
        var yaw = 0f
        var pitch = 0f
        var lastMs = 0L
        var nextTargetMs = 0L
        var idleTarget: Vec3? = null
        var nextGlanceMs = SystemClock.elapsedRealtime() + 8_000 + r.nextInt(12_000)
        var glanceUntilMs = 0L
        var nextBlinkMs = SystemClock.elapsedRealtime() + r.nextInt(6_000)
        var blinkUntilMs = 0L
        var nodStartMs = -10_000L
        var lastNodMs = -100_000L
        var phone = false
        var raise = -1f
        var squeeze = 1f
        var faceKind = Face.NEUTRAL
    }

    companion object {
        /** Lectern panel 760 dp -> 0.46 m (design panels[0].physicalM). */
        const val LECTERN_K = 0.46f / (PanelSizes.LECTERN_W * DP_M)
        /** Zone sign 320 dp -> 1.20 m. */
        const val SIGN_K = 1.2f / (PanelSizes.SIGN_W * DP_M)
        const val MAX_DT = 2f
        const val PLACE_WAIT_MS = 1_000L
        const val PLACE_WINDOW_MS = 500L
        const val PLACE_TIMEOUT_MS = 4_000L
        const val FADE_MS = 800f
        const val LIGHT_FADE_S = 3f
        const val LOG_MS = 5_000L
        const val BATCHES = 6
        const val TURN_RATE = 1.047f // 60°/s
        const val MAX_YAW = 1.2f
        const val PHONE_PITCH = -0.61f // 35° down
        const val SEEN_S = 0.5f
        const val NOD_AFTER_S = 1.5f
        const val NOD_COOLDOWN_MS = 20_000L
        const val CLAP_MS = 3_000L
        const val BLINK_MS = 160L
        const val TRIGGER_SETTLE_MS = 250L
        const val PANEL_MIN_TILT = 0.349f // 20°
        const val PANEL_MAX_TILT = 1.222f // 70°
        val SCREEN = Vec3(0f, HallLayout.SCREEN_BOTTOM + HallLayout.SCREEN_H / 2, HallLayout.SCREEN_Z)

        /** Last pointer event on any panel (the trigger page turn ignores presses that landed on the lectern). */
        @Volatile var lastPanelPointerMs = 0L
    }
}
