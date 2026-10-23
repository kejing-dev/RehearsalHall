package io.embodit.rehearsalhall.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.rehearsalhall.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
