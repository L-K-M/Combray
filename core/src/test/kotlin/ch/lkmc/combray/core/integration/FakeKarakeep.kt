package ch.lkmc.combray.core.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A minimal Karakeep: `POST /keep/api/v1/bookmarks` (201 new, 200 existing URL),
 * `GET /keep/api/v1/bookmarks` (newest first) and `.../bookmarks/search?q=` (address or title
 * contains q), `GET /keep/api/v1/lists` and `PUT .../lists/{list}/bookmarks/{id}`, behind a
 * configurable fault for the next requests.
 */
class FakeKarakeep(private val apiKey: String = KEY) : AutoCloseable {
    enum class Fault { NONE, STORE_THEN_HANG, TOO_MANY_REQUESTS, SERVER_ERROR, BAD_REQUEST, REDIRECT, MALFORMED, MISSING_ID }

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val bookmarks = LinkedHashMap<String, String>()
    private val titles = HashMap<String, String?>()

    /** List id -> bookmark ids in it. Lists that are not here answer 404. */
    val lists = LinkedHashMap<String, MutableSet<String>>()
    val requests = CopyOnWriteArrayList<String>()
    @Volatile var fault = Fault.NONE
    @Volatile var faultCount = 0
    @Volatile var acceptedKey = apiKey

    val root get() = "http://127.0.0.1:${server.address.port}/keep"

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/keep/api/v1/bookmarks") { exchange -> handle(exchange) }
        server.createContext("/keep/api/v1/lists") { exchange -> handleLists(exchange) }
        server.start()
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        requests += "${exchange.requestMethod} $body"
        if (exchange.requestHeaders.getFirst("Authorization") != "Bearer $acceptedKey") return reply(exchange, 401, "{}")
        if (exchange.requestMethod == "GET") return reply(exchange, 200, listing(exchange))

        val url = Regex("\"url\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        val current = if (faultCount > 0) fault.also { faultCount-- } else Fault.NONE
        when (current) {
            Fault.TOO_MANY_REQUESTS -> return reply(exchange, 429, "{}", "Retry-After" to "120")
            Fault.SERVER_ERROR -> return reply(exchange, 503, "{}")
            Fault.BAD_REQUEST -> return reply(exchange, 400, """{"error":"bad"}""")
            Fault.REDIRECT -> return reply(exchange, 302, "", "Location" to "https://elsewhere.example/")
            Fault.MALFORMED, Fault.MISSING_ID -> {
                val existing = url in bookmarks
                bookmarks.getOrPut(url) { "bm${bookmarks.size + 1}" }
                return reply(
                    exchange,
                    if (existing) HttpURLConnection.HTTP_OK else HttpURLConnection.HTTP_CREATED,
                    if (current == Fault.MALFORMED) "not json" else "{}",
                )
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
        titles.putIfAbsent(url, Regex("\"title\":\"([^\"]+)\"").find(body)?.groupValues?.get(1))
        reply(exchange, if (existing != null) 200 else 201, """{"id":"$id","url":"$url"}""")
    }

    private fun listing(exchange: HttpExchange): String {
        val query = exchange.requestURI.query.orEmpty().split('&').map { it.substringBefore('=') to it.substringAfter('=') }.toMap()
        val search = query["q"]?.takeIf { exchange.requestURI.path.endsWith("/search") }
        val matches = bookmarks.entries.reversed().filter { (url, _) ->
            search == null || url.contains(search, ignoreCase = true) || titles[url]?.contains(search, ignoreCase = true) == true
        }
        val items = matches.joinToString(",") { (url, id) ->
            val title = titles[url]?.let { "\"$it\"" } ?: "null"
            """{"id":"$id","title":null,"content":{"type":"link","url":"$url","title":$title}}"""
        }
        return """{"bookmarks":[$items],"nextCursor":null}"""
    }

    private fun handleLists(exchange: HttpExchange) {
        requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
        if (exchange.requestHeaders.getFirst("Authorization") != "Bearer $acceptedKey") return reply(exchange, 401, "{}")
        if (exchange.requestMethod == "GET") {
            val manual = lists.keys.map { """{"id":"$it","name":"List $it","icon":"📚","parentId":null,"type":"manual"}""" }
            val smart = """{"id":"smart","name":"Smart","icon":"🔎","parentId":null,"type":"smart"}"""
            return reply(exchange, 200, """{"lists":[${(manual + smart).joinToString(",")}]}""")
        }
        // PUT /keep/api/v1/lists/{list}/bookmarks/{id}
        val parts = exchange.requestURI.path.split('/')
        val members = lists[parts[parts.size - 3]] ?: return reply(exchange, 404, """{"error":"not found"}""")
        if (parts.last() !in bookmarks.values) return reply(exchange, 404, """{"error":"not found"}""")
        members += parts.last()
        reply(exchange, 204, "")
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
