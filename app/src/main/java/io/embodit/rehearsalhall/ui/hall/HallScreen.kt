package io.embodit.rehearsalhall.ui.hall

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.rehearsalhall.platform.HallLog
import io.embodit.rehearsalhall.ui.hall.components.HallStage
import io.embodit.rehearsalhall.ui.hall.components.LocalHallResources
import io.embodit.rehearsalhall.ui.hall.components.localizedResources
import io.embodit.rehearsalhall.ui.hall.debug.Probe
import io.embodit.rehearsalhall.ui.hall.scene.HallFrameSystem
import io.embodit.rehearsalhall.ui.hall.scene.HallScene
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The one screen. It wires the process-wide [HallController] (state), the [HallScene] (entities, per-frame work) and
 * [HallStage] (the panels), and owns nothing else.
 */
@Composable
fun HallScreen() {
    val context = LocalContext.current
    remember(context) {
        Probe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        HallController.start(context)
        true
    }
    val state by HallController.state.collectAsStateWithLifecycle()
    val scene = remember { HallScene(HallController::onEvent) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()

    DisposableEffect(scene, lifecycle) {
        // the one per-frame system; every start has its stop in onDispose
        registerSystem<HallFrameSystem>()
        Probe.host = object : Probe.Host { override fun scene(): HallScene = scene }
        // background: head tracking stops, the talk pauses (design container.notes, stateModel.background)
        val observer =
            LifecycleEventObserver { _, event ->
                HallLog.i("LIFECYCLE event=$event")
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        scene.pause()
                        HallController.onEvent(HallEvent.Background(true))
                    }
                    Lifecycle.Event.ON_START -> {
                        scene.resume()
                        HallController.onEvent(HallEvent.Background(false))
                    }
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        // D26 / D44 exit: stop the scene, close the Stage, remove every task of the app, then end the process
        HallController.quitHook = {
            HallLog.i("EXIT close stage + finishAndRemoveTask + exitProcess")
            runCatching { unregisterSystem<HallFrameSystem>() }
            scene.detach()
            scope.launch {
                runCatching { navigator.closeStage() }.onFailure { HallLog.w("EXIT closeStage: $it") }
                runCatching {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    am.appTasks.forEach { t -> runCatching { t.finishAndRemoveTask() } }
                }.onFailure { HallLog.w("EXIT appTasks: $it") }
                runCatching { context.findActivity()?.finishAndRemoveTask() }
                delay(400)
                exitProcess(0)
            }
        }
        HallLog.i("STAGE enter")
        onDispose {
            lifecycle.removeObserver(observer)
            HallController.quitHook = null
            Probe.host = null
            unregisterSystem<HallFrameSystem>()
            scene.detach()
            HallLog.i("STAGE disposed")
        }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    val resources = remember(state.language, context) { localizedResources(context, state.language) }
    CompositionLocalProvider(LocalHallResources provides resources) {
        HallStage(state = state, scene = scene, onEvent = HallController::onEvent)
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
