package io.embodit.rehearsalhall

import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import io.embodit.rehearsalhall.ui.hall.HallScreen
import io.embodit.rehearsalhall.ui.theme.HallTheme

/**
 * App entry (design `container`: STAGE_FULL, skeleton C). The default Stage is Full (AndroidManifest
 * `pico.spatial.stage.style=3`): opening the app puts you on the stage of the hall; there is no Shared Space window.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage { HallTheme { HallScreen() } }
    }
