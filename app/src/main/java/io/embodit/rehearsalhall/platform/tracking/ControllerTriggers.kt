package io.embodit.rehearsalhall.platform.tracking

import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.rehearsalhall.platform.HallLog
import java.util.concurrent.atomic.AtomicInteger

/**
 * The controllers' triggers as a page clicker (design `input.rule`, T8): a press of the right trigger = next page, the
 * left one = previous page. Read from the provider's action listener (SDK doc "Obtain controller input actions"): the
 * callback runs on the data source thread and only counts press edges; the scene takes them once per frame and decides
 * whether the ray was on the lectern panel. Every start has its stop (scene attach / detach, pause / resume).
 */
class ControllerTriggers {
    private var provider: ControllerTrackingProvider? = null
    private val right = AtomicInteger(0)
    private val left = AtomicInteger(0)
    @Volatile private var rightDown = false
    @Volatile private var leftDown = false
    @Volatile var actionCount = 0L
        private set

    private val actions =
        ControllerTrackingProvider.ControllerActionListener { a ->
            val r = a.right.triggerPressed
            val l = a.left.triggerPressed
            if (r && !rightDown) right.incrementAndGet()
            if (l && !leftDown) left.incrementAndGet()
            rightDown = r
            leftDown = l
            actionCount++
        }

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        runCatching { p.addControllerActionListener(actions) }.onFailure { HallLog.w("TRACK controller actions: $it") }
        val result = runCatching { p.start() }
        HallLog.i("TRACK controller start=${result.getOrNull() ?: result.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
    }

    fun stop() {
        provider?.let { p ->
            runCatching { p.removeControllerActionListener(actions) }
            runCatching { p.stop() }
        }
        provider = null
        right.set(0)
        left.set(0)
    }

    fun status(): String = "support=${runCatching { provider?.supportState }.getOrNull()} actions=$actionCount"

    /** Presses since the last call: (right, left). */
    fun take(): Pair<Int, Int> {
        runCatching { provider?.latestData }
        return right.getAndSet(0) to left.getAndSet(0)
    }
}
