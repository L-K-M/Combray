package ch.lkmc.combray.core.integration

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Loopback fixture roots that stay unreachable for a whole test.
 *
 * The old fixture took a port with `ServerSocket(0).use { it.localPort }`: the port was free
 * again as soon as that socket closed, so another listener could bind it before the test's
 * requests ran. This reserves each port with a bound but never connected or listening client
 * socket instead, which keeps a listening bind on the same loopback address from succeeding
 * until [close] releases it. Some platforms time out instead of refusing connects to these
 * sockets, so fixture transports must bound connectTimeoutMs separately from their read timeout.
 */
class UnreachableRoots : TestRule {
    private val reservations = mutableListOf<Socket>()

    /** The port of a fresh reservation; repeated calls reserve distinct ports. */
    fun port(): Int = reserve().localPort

    /** A `http://127.0.0.1` root on a fresh reservation, with [path] appended. */
    fun root(path: String): String = "http://127.0.0.1:${reserve().localPort}$path"

    private fun reserve(): Socket {
        val socket = Socket()
        try {
            socket.bind(InetSocketAddress(LOOPBACK, 0))
        } catch (failure: IOException) {
            try {
                socket.close()
            } catch (_: IOException) {
                // The bind failure is the useful one.
            }
            throw failure
        }
        reservations += socket
        return socket
    }

    /** Closes every reservation. Safe to call more than once. */
    fun close() {
        for (socket in reservations) {
            try {
                socket.close()
            } catch (_: IOException) {
                // Cleanup must not mask the failure that brought the test here.
            }
        }
        reservations.clear()
    }

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } finally {
                    close()
                }
            }
        }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}
