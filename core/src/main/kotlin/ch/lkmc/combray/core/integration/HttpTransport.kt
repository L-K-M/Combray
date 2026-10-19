package ch.lkmc.combray.core.integration

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
)

sealed interface HttpOutcome {
    data class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray) : HttpOutcome

    /** Sent, but no answer: the server may or may not have acted on it. */
    data object Timeout : HttpOutcome

    /** Not sent, or the connection broke: [category] is safe to show and log. */
    data class Failure(val category: String) : HttpOutcome
}

fun interface HttpTransport {
    fun execute(request: HttpRequest): HttpOutcome
}

/**
 * Plain HttpURLConnection: no redirects are followed (they could carry the credential or the
 * page content to another origin), bounded timeouts, and response bodies capped in memory.
 */
class UrlConnectionTransport(
    private val timeoutMs: Int = REQUEST_TIMEOUT_MS,
    private val maxResponseBytes: Int = MAX_RESPONSE_BYTES,
) : HttpTransport {
    override fun execute(request: HttpRequest): HttpOutcome {
        val connection = try {
            URL(request.url).openConnection() as HttpURLConnection
        } catch (malformed: IOException) {
            return HttpOutcome.Failure("bad_url")
        }
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            connection.requestMethod = request.method
            try {
                request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            } catch (invalid: IllegalArgumentException) {
                // The message would quote the value, which can be a credential: never keep it.
                return HttpOutcome.Failure("bad_header")
            }
            request.body?.let { body ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
            val headers = connection.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }
            HttpOutcome.Response(status, headers, stream?.use { readBounded(it) } ?: ByteArray(0))
        } catch (timeout: SocketTimeoutException) {
            HttpOutcome.Timeout
        } catch (failure: IOException) {
            HttpOutcome.Failure("network")
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(stream: InputStream): ByteArray {
        val buffer = ByteArray(maxResponseBytes + 1)
        var read = 0
        while (read < buffer.size) {
            val count = stream.read(buffer, read, buffer.size - read)
            if (count < 0) break
            read += count
        }
        // Over the cap: keep the prefix; parsers then see a truncated, invalid body.
        return buffer.copyOf(minOf(read, maxResponseBytes))
    }

    companion object {
        /** Combray policy (docs/IMPLEMENTATION_PLAN.md section 6), not an API limit. */
        const val REQUEST_TIMEOUT_MS = 15_000
        const val MAX_RESPONSE_BYTES = 256 * 1024
    }
}
