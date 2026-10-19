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
    @Volatile var searchBody = """{"documents":[],"history":[],"total":0,"page_key":null}"""

    val root get() = "http://127.0.0.1:${server.address.port}/h"

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/h/api/add") { exchange ->
            val body = exchange.requestBody.readBytes()
            requestSizes += body.size
            when {
                exchange.requestHeaders.getFirst("X-Access-Token") != token -> reply(exchange, 401, "")
                exchange.requestHeaders.getFirst("Origin") != "hister://" -> reply(exchange, 403, "")
                else -> {
                    added += body.toString(Charsets.UTF_8)
                    reply(exchange, 201, "")
                }
            }
        }
        server.createContext("/h/search") { exchange ->
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
