package ch.lkmc.combray.core.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The unreachable-address fixture must stay unreachable for the whole test. A released port
 * allocation can be taken by another listener before the test's requests run, so the fixture
 * reserves the port instead.
 */
class UnreachableRootsTest {
    @get:Rule val roots = UnreachableRoots()

    /** The old fixture's weakness: a closed allocation leaves the port free for another listener. */
    @Test
    fun aClosedPortAllocationCanBeTakenByAnotherListener() {
        val port = ServerSocket(0).use { it.localPort }

        bind(port).use { rebound ->
            assertEquals(port, rebound.localPort)
        }
    }

    /** The protected fixture: while held, the port accepts no other listener and no connection. */
    @Test
    fun aReservedPortRejectsAnotherListeningBindAndAConnection() {
        val port = roots.port()

        ServerSocket().use { listener ->
            assertThrows(BindException::class.java) {
                listener.bind(InetSocketAddress(LOOPBACK, port))
            }
        }

        val probe = Socket()
        try {
            probe.connect(InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MS)
            fail("port $port accepted a connection while the fixture held it")
        } catch (expected: IOException) {
            // Held: nothing listens, and the bounded connect fails instead of hanging.
        } finally {
            probe.close()
        }
    }

    /** Tests that call the fixture repeatedly need distinct ports, not one shared address. */
    @Test
    fun repeatedCallsReserveDistinctPorts() {
        assertNotEquals(roots.port(), roots.port())
    }

    /** The root form appends its path to a fresh reservation. */
    @Test
    fun rootAppendsThePathToADistinctReservedPort() {
        val first = roots.root("/keep")
        val second = roots.root("/r")

        assertTrue(first, first.startsWith("http://$LOOPBACK:"))
        assertTrue(first, first.endsWith("/keep"))
        assertTrue(second, second.endsWith("/r"))
        assertNotEquals(first, second)
    }

    /** Closing the fixture releases the port for a listener again. */
    @Test
    fun closingTheFixtureReleasesItsPorts() {
        val fixture = UnreachableRoots()
        val port = fixture.port()
        fixture.close()

        bind(port).use { released ->
            assertEquals(port, released.localPort)
        }
        fixture.close()
    }

    /** The rule closes every reservation even when the test itself fails. */
    @Test
    fun theRuleClosesEveryReservedPortWhenTheTestFails() {
        val fixture = UnreachableRoots()
        val first = fixture.port()
        var second = -1
        val failing = object : Statement() {
            override fun evaluate() {
                second = fixture.port()
                throw AssertionError("deliberate test failure")
            }
        }

        assertThrows(AssertionError::class.java) {
            fixture.apply(failing, Description.createTestDescription("UnreachableRoots", "fails")).evaluate()
        }

        bind(first).use { assertEquals(first, it.localPort) }
        bind(second).use { assertEquals(second, it.localPort) }
    }

    /** Binds a listener to the same loopback address the fixture reserves. */
    private fun bind(port: Int): ServerSocket {
        val socket = ServerSocket()
        try {
            socket.bind(InetSocketAddress(LOOPBACK, port))
            return socket
        } catch (failure: IOException) {
            runCatching { socket.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CONNECT_TIMEOUT_MS = 500
    }
}
