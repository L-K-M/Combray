package ch.lkmc.combray.engine

import java.util.concurrent.atomic.AtomicLong

/** One InputConnection's composition. Updates replace the preview; finishing never appends it. */
internal class PageComposition(
    private val send: (Long, Phase, String) -> Unit,
    private val commit: (String) -> Unit,
    private val nextId: () -> Long = { ids.incrementAndGet() },
) {
    enum class Phase(val nativeValue: Int) { START(0), UPDATE(1), END(2) }

    private var id: Long? = null
    private var text = ""
    var closed = false
        private set

    fun update(value: String): Boolean {
        if (closed) return false
        val session = id ?: nextId().also {
            id = it
            send(it, Phase.START, "")
        }
        text = value
        send(session, Phase.UPDATE, value)
        return true
    }

    fun commit(value: String): Boolean {
        if (closed) return false
        val session = id
        id = null
        text = ""
        if (session == null) commit.invoke(value) else send(session, Phase.END, value)
        return true
    }

    fun finish(): Boolean {
        if (closed) return false
        val session = id ?: return true
        id = null
        val finalText = text
        text = ""
        send(session, Phase.END, finalText)
        return true
    }

    fun close() {
        finish()
        closed = true
    }

    private companion object {
        val ids = AtomicLong()
    }
}
