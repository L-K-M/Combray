package ch.lkmc.combray.ui

import android.content.SharedPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real Robolectric [SharedPreferences] whose commits follow a scripted queue of outcomes.
 * [latch] holds every commit until it is released, so a test can delay an older write across a
 * newer click.
 */
internal class ScriptedPreferences(
    val delegate: SharedPreferences,
) : SharedPreferences by delegate {
    val modes = ArrayDeque<Mode>()
    val commits = AtomicInteger()
    var latch: CountDownLatch? = null

    override fun edit(): SharedPreferences.Editor {
        val editor = delegate.edit()
        return object : SharedPreferences.Editor by editor {
            override fun commit(): Boolean {
                commits.incrementAndGet()
                latch?.await()
                editor.commit()
                return when (val mode = modes.removeFirstOrNull() ?: Mode.Acknowledge) {
                    Mode.Acknowledge -> true
                    Mode.Fail -> false
                    Mode.Throw -> throw IllegalStateException("scripted commit failure")
                }
            }

            override fun apply() {
                editor.apply()
            }
        }
    }

    enum class Mode { Acknowledge, Fail, Throw }
}
