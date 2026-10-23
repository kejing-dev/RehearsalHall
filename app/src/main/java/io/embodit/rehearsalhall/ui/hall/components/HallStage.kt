package io.embodit.rehearsalhall.ui.hall.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.pico.spatial.ui.foundation.content.SpatialView
import io.embodit.rehearsalhall.domain.Zones
import io.embodit.rehearsalhall.platform.HallLog
import io.embodit.rehearsalhall.ui.hall.HallEvent
import io.embodit.rehearsalhall.ui.hall.HallUiState
import io.embodit.rehearsalhall.ui.hall.debug.LocalProbePanel
import io.embodit.rehearsalhall.ui.hall.debug.Probe
import io.embodit.rehearsalhall.ui.hall.scene.HallScene
import io.embodit.rehearsalhall.ui.hall.scene.PanelIds

/**
 * The Stage content: one full-size SpatialView holding the hall and the panel pool (window model
 * `stage_with_lectern_panel`): the lectern panel, and nine read-only zone signs shown with the report. Every attachment
 * panel is declared once with a fixed size; [HallScene] parents and places them.
 */
@Composable
internal fun HallStage(state: HallUiState, scene: HallScene, onEvent: (HallEvent) -> Unit) {
    val bindTick = scene.bindTick.intValue
    SpatialView(
        modifier = Modifier.fillMaxSize(),
        attachments = {
            AttachmentPanel(PanelIds.LECTERN) { PoolPanel(PanelIds.LECTERN, scene) { inView -> LecternPanel(if (inView) state else null, onEvent) } }
            for (z in 0 until Zones.COUNT) {
                AttachmentPanel(PanelIds.sign(z)) { PoolPanel(PanelIds.sign(z), scene) { inView -> ZoneSign(if (inView) state else null, z) } }
            }
        },
        update = { _, attachments ->
            // reading the tick makes this block run again while panel entities are still missing
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * Root of a pooled panel: [content] gets `inView` (a pooled panel draws only once it is in view, Spatial Ruler
 * finding 3). Every press is noted for the trigger page turn; debug builds also log it as `PTR panel=<id> ev=`.
 */
@Composable
private fun PoolPanel(id: String, scene: HallScene, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    val watcher =
        Modifier.pointerInput(id) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Move) continue
                    HallScene.lastPanelPointerMs = android.os.SystemClock.elapsedRealtime()
                    if (Probe.enabled) {
                        val p = event.changes.firstOrNull()?.position
                        HallLog.i(String.format(java.util.Locale.US, "PTR panel=%s ev=%s x=%.1f y=%.1f", id, event.type, p?.x ?: 0f, p?.y ?: 0f))
                    }
                }
            }
        }
    CompositionLocalProvider(LocalProbePanel provides id) {
        Box(modifier = watcher) { content(inView) }
    }
}
