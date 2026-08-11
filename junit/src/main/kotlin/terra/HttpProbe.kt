package terra

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * HTTP against a service in the running environment.
 *
 * Every request carries `X-Test-Id`, so a fifty-megabyte cluster log becomes
 * greppable by the one thing you care about. Tests never see a port number: the
 * address came from the descriptor, which came from Docker.
 */
class HttpProbe(private val endpoint: HostPort, private val testId: String) {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        // Java's default is HTTP/2, which over plain HTTP means every request opens
        // with an h2c upgrade. Jetty and nginx shrug and answer 1.1; Node destroys
        // the socket, and the test sees "header parser received no bytes" from a
        // service that is running perfectly. No test traffic here needs HTTP/2.
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    private val mapper = jacksonObjectMapper()

    data class Response(val status: Int, val body: String) {
        fun json(): JsonNode = jacksonObjectMapper().readTree(body)
    }

    fun get(path: String = "/"): Response = send("GET", path, null)

    fun post(path: String, body: String = ""): Response = send("POST", path, body)

    private fun send(method: String, path: String, body: String?): Response {
        val request = HttpRequest.newBuilder(URI("http://$endpoint${path.ensurePrefix()}"))
            .timeout(Duration.ofSeconds(10L * timeoutScale))
            .header("X-Test-Id", testId)
            .method(
                method,
                body?.let { HttpRequest.BodyPublishers.ofString(it) }
                    ?: HttpRequest.BodyPublishers.noBody(),
            )
            .build()

        return Journal.record("http", "$method $path") {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            Response(response.statusCode(), response.body())
        }
    }

    private fun String.ensurePrefix() = if (startsWith("/")) this else "/$this"
}
