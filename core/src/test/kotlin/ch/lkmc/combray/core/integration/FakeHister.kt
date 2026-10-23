package ch.lkmc.combray.core.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** A minimal Hister: `POST /h/api/add` (201, needs Origin: hister://) and `GET /h/search`. */
class FakeHister(private val token: String = TOKEN) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val added = CopyOnWriteArrayList<String>()
    val requestSizes = CopyOnWriteArrayList<Int>()
    val searchQueries = CopyOnWriteArrayList<String>()
    // Like the pinned server: page_key is always present and empty when there is no next page.
    /** Shaped like the pinned server's answer: "history" is null when nothing is promoted. */
    @Volatile var searchBody = """{"documents":[],"history":null,"total":0,"page_key":""}"""

    /** An answer to authorized adds instead of storing them, such as 406 (skip rules) or 422. */
    @Volatile var refusal: Int? = null

    val root get() = "http://127.0.0.1:${server.address.port}/h"

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/h/api/add") { exchange ->
            val body = exchange.requestBody.readBytes()
            requestSizes += body.size
            val refused = refusal
            when {
                exchange.requestHeaders.getFirst("X-Access-Token") != token -> reply(exchange, 401, "")
                exchange.requestHeaders.getFirst("Origin") != "hister://" -> reply(exchange, 403, "")
                refused != null -> reply(exchange, refused, "refused")
                else -> {
                    added += body.toString(Charsets.UTF_8)
                    reply(exchange, 201, "")
                }
            }
        }
        server.createContext("/h/search") { exchange ->
            searchQueries += exchange.requestURI.rawQuery
            if (exchange.requestHeaders.getFirst("X-Access-Token") != token) return@createContext reply(exchange, 401, "")
            reply(exchange, 200, searchBody)
        }
        server.start()
    }

    private fun reply(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    override fun close() = server.stop(0)

    companion object {
        const val TOKEN = "hister-token"
    }
}
