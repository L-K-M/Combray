package ch.lkmc.combray.core.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A minimal Karakeep: `POST /keep/api/v1/bookmarks` (201 new, 200 existing URL) and
 * `GET /keep/api/v1/bookmarks`, behind a configurable fault for the next requests.
 */
class FakeKarakeep(private val apiKey: String = KEY) : AutoCloseable {
    enum class Fault { NONE, STORE_THEN_HANG, TOO_MANY_REQUESTS, SERVER_ERROR, BAD_REQUEST, REDIRECT, MALFORMED }

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val bookmarks = LinkedHashMap<String, String>()
    val requests = CopyOnWriteArrayList<String>()
    @Volatile var fault = Fault.NONE
    @Volatile var faultCount = 0
    @Volatile var acceptedKey = apiKey

    val root get() = "http://127.0.0.1:${server.address.port}/keep"

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/keep/api/v1/bookmarks") { exchange -> handle(exchange) }
        server.start()
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        requests += "${exchange.requestMethod} $body"
        if (exchange.requestHeaders.getFirst("Authorization") != "Bearer $acceptedKey") return reply(exchange, 401, "{}")
        if (exchange.requestMethod == "GET") return reply(exchange, 200, """{"bookmarks":[],"nextCursor":null}""")

        val url = Regex("\"url\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        val current = if (faultCount > 0) fault.also { faultCount-- } else Fault.NONE
        when (current) {
            Fault.TOO_MANY_REQUESTS -> return reply(exchange, 429, "{}", "Retry-After" to "120")
            Fault.SERVER_ERROR -> return reply(exchange, 503, "{}")
            Fault.BAD_REQUEST -> return reply(exchange, 400, """{"error":"bad"}""")
            Fault.REDIRECT -> return reply(exchange, 302, "", "Location" to "https://elsewhere.example/")
            Fault.MALFORMED -> {
                bookmarks.getOrPut(url) { "bm${bookmarks.size + 1}" }
                return reply(exchange, 201, "not json")
            }
            Fault.STORE_THEN_HANG -> {
                bookmarks.getOrPut(url) { "bm${bookmarks.size + 1}" }
                Thread.sleep(HANG_MS)
                return reply(exchange, 201, "{}")
            }
            Fault.NONE -> Unit
        }
        val existing = bookmarks[url]
        val id = existing ?: "bm${bookmarks.size + 1}".also { bookmarks[url] = it }
        reply(exchange, if (existing != null) 200 else 201, """{"id":"$id","url":"$url"}""")
    }

    private fun reply(exchange: HttpExchange, status: Int, body: String, vararg headers: Pair<String, String>) {
        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    override fun close() = server.stop(0)

    companion object {
        const val KEY = "test-key"
        const val HANG_MS = 1_500L
    }
}
