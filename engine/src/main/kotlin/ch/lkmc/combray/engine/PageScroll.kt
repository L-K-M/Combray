package ch.lkmc.combray.engine

import kotlin.math.abs

/** A particular document in a particular native view, including same-address reloads. */
data class PageRef(val view: ViewRef, val document: Long)

/** Codes shared with native/src/jni_api.rs. */
enum class ScrollEdge(val nativeCode: Int) { TOP(0), BOTTOM(1) }

/** Vertical intent only: taps, horizontal drags and multi-touch must keep their own meaning. */
internal class ScrollGestureTracker(private val slopPx: Float) {
    private var tracking = false
    private var x = 0f
    private var y = 0f

    fun press(x: Float, y: Float) {
        this.x = x
        this.y = y
        tracking = true
    }

    fun move(x: Float, y: Float): ScrollEdge? {
        if (!tracking) return null
        val dx = x - this.x
        val dy = y - this.y
        if (maxOf(abs(dx), abs(dy)) <= slopPx) return null
        if (abs(dx) >= abs(dy)) {
            release()
            return null
        }
        this.x = x
        this.y = y
        // A finger moving up reveals content farther down the page.
        return if (dy < 0) ScrollEdge.BOTTOM else ScrollEdge.TOP
    }

    fun release() {
        tracking = false
    }
}
