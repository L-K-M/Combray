package ch.lkmc.combray.core.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A minimal report service under `/r`: `GET /r/v1/whoami` and `POST /r/v1/reports` with the
 * service's repeat rules (same id and content 200, different content 409). It checks the token
 * and the role, not the full validation: the fixture server runs the service's own
 * validation.py on device checks, and SiteReportsClientTest compares the body with the
 * service's golden request.
 */
class FakeReports(
    @Volatile var acceptedToken: String = TOKEN,
    @Volatile var agentToken: String = AGENT_TOKEN,
) : AutoCloseable {
    enum class Fault { NONE, STORE_THEN_HANG, TOO_MANY_REQUESTS, SERVER_ERROR, UNAUTHORIZED, CONFLICT, TOO_LARGE }

    /** What arrived, without the body: enough to prove where the token travelled. */
    data class Request(val method: String, val target: String, val headerNames: Set<String>, val authorization: String?, val bodyBytes: Int)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** Stored reports by id: the request without `screenshot.data`. */
    val reports = ConcurrentHashMap<String, JsonObject>()

    /** Decoded screenshots by report id. */
    val screenshots = ConcurrentHashMap<String, ByteArray>()
    val requests = CopyOnWriteArrayList<Request>()
    @Volatile var fault = Fault.NONE
    @Volatile var faultCount = 0

    /** Another service's whoami answer, for the wrong-address check. */
    @Volatile var whoamiBody: String? = null

    val root get() = "http://127.0.0.1:${server.address.port}/r"

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/r/v1/whoami") { exchange -> whoami(exchange) }
        server.createContext("/r/v1/reports") { exchange -> create(exchange) }
        server.start()
    }

    private fun whoami(exchange: HttpExchange) {
        log(exchange, 0)
        val role = role(exchange) ?: return reply(exchange, 401, """{"error":"unauthorized"}""")
        reply(exchange, 200, whoamiBody ?: """{"service":"combray-site-reports","api":1,"role":"$role"}""")
    }

    private fun create(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes()
        log(exchange, body.size)
        val role = role(exchange) ?: return reply(exchange, 401, """{"error":"unauthorized"}""")
        if (role != "reporter") return reply(exchange, 403, """{"error":"forbidden"}""")

        val current = if (faultCount > 0) fault.also { faultCount-- } else Fault.NONE
        when (current) {
            Fault.TOO_MANY_REQUESTS -> return reply(exchange, 429, """{"error":"rate_limited"}""", "Retry-After" to "120")
            Fault.SERVER_ERROR -> return reply(exchange, 507, """{"error":"storage_full"}""")
            Fault.UNAUTHORIZED -> return reply(exchange, 401, """{"error":"unauthorized"}""")
            Fault.CONFLICT -> return reply(exchange, 409, """{"error":"id_conflict"}""")
            Fault.TOO_LARGE -> return reply(exchange, 413, """{"error":"too_large"}""")
            Fault.STORE_THEN_HANG, Fault.NONE -> Unit
        }

        val request = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
        val id = request.getValue("id").jsonPrimitive.content
        val screenshot = request["screenshot"] as? JsonObject
        val metadata = JsonObject(
            request + listOfNotNull(screenshot?.let { "screenshot" to JsonObject(it - "data") }),
        )
        val existing = reports.putIfAbsent(id, metadata)
        if (existing == null) {
            screenshot?.let { screenshots[id] = Base64.getDecoder().decode((it.getValue("data") as JsonPrimitive).content) }
        }
        if (current == Fault.STORE_THEN_HANG) {
            Thread.sleep(HANG_MS)
            return reply(exchange, 201, answer(id))
        }
        when {
            existing == null -> reply(exchange, 201, answer(id))
            existing == metadata -> reply(exchange, 200, answer(id))
            else -> reply(exchange, 409, """{"error":"id_conflict"}""")
        }
    }

    private fun answer(id: String) = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("status", JsonPrimitive("open"))
        put("received_at", JsonPrimitive("2026-11-04T09:12:45.120Z"))
    }.toString()

    private fun role(exchange: HttpExchange): String? = when (exchange.requestHeaders.getFirst("Authorization")) {
        "Bearer $acceptedToken" -> "reporter"
        "Bearer $agentToken" -> "agent"
        else -> null
    }

    private fun log(exchange: HttpExchange, bodyBytes: Int) {
        requests += Request(
            method = exchange.requestMethod,
            target = exchange.requestURI.toString(),
            headerNames = exchange.requestHeaders.keys.map { it.lowercase() }.toSet(),
            authorization = exchange.requestHeaders.getFirst("Authorization"),
            bodyBytes = bodyBytes,
        )
    }

    private fun reply(exchange: HttpExchange, status: Int, body: String, vararg headers: Pair<String, String>) {
        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    override fun close() = server.stop(0)

    companion object {
        const val TOKEN = "reporter-token"
        const val AGENT_TOKEN = "agent-token"
        const val HANG_MS = 1_500L

        /** The golden request's versions (services/site-reports/tests/data/report-v1.json). */
        val ENVIRONMENT = ReportEnvironment("0.1.0", 1, "debug", "0.7.0", "6fbfc3d70ad83df5443445d3ff4c43871aae41dd", "15", 35)

        /** The golden request's 3x2 PNG. */
        val PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAYAAACddGYaAAAAG0lEQVR4nGNgYDjxXwOIA4CYgSEKyAHiACAGAJHnDKnaH5CwAAAAAElFTkSuQmCC",
        )

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
