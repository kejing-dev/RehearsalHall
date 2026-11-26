package io.embodit.rehearsalhall.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.rehearsalhall.mainApp
import io.embodit.rehearsalhall.ui.hall.HallController
import io.embodit.rehearsalhall.ui.hall.HallEvent

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        guardBack()
        launch(::mainApp)
    }

    /**
     * D60 (gate back-key, v0.1.3): controller B = system BACK. Without a handler a press after tapping the lectern sent
     * the whole Stage to the background. BACK / B is caught at the earliest layer of every window ([BackKeys]) and goes
     * to [HallEvent.Back] (talking -> pause, sub page -> previous page, first page -> nothing). It never quits. The
     * OnBackPressedCallback stays as the last fallback.
     */
    private fun guardBack() {
        BackKeys.handler = { where ->
            HallLog.i("BACK $where")
            HallController.onEvent(HallEvent.Back)
        }
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(a: Activity, b: Bundle?) {
                    if (helper(a)) return
                    BackKeys.wrap(a)
                    val ca = a as? ComponentActivity ?: return
                    ca.onBackPressedDispatcher.addCallback(
                        ca,
                        object : OnBackPressedCallback(true) {
                            override fun handleOnBackPressed() = BackKeys.handler("dispatcher:${a.javaClass.simpleName}")
                        },
                    )
                }

                override fun onActivityPostCreated(a: Activity, b: Bundle?) {
                    if (helper(a)) return
                    BackKeys.wrap(a)
                    BackKeys.sweep("created")
                }

                override fun onActivityStarted(a: Activity) = BackKeys.sweep("started")

                override fun onActivityResumed(a: Activity) {
                    if (helper(a)) return
                    BackKeys.wrap(a)
                    BackKeys.sweep("resumed")
                }

                override fun onActivityPostResumed(a: Activity) = BackKeys.sweep("post-resumed")

                override fun onActivityPaused(a: Activity) = Unit

                override fun onActivityStopped(a: Activity) = Unit

                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit

                override fun onActivityDestroyed(a: Activity) = Unit
            }
        )
    }

    /** The transparent helpers (file picker, permission dialogs) keep the system BACK: it cancels them. */
    private fun helper(a: Activity) = a is PickerActivity || a is MicPermissionActivity || a is EyePermissionActivity || a is OpenWithActivity
}
