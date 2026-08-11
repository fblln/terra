package terra

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A programmable mock every test can reconfigure at runtime, without restarting
 * anything and without disturbing the tests running beside it.
 *
 * The isolation trick is the same one used for MongoDB and Kafka: **scope by an
 * identity nothing else uses**. By default that identity lives in the request
 * *payload*, because the payload is the one thing that survives the hop through the
 * service under test. A rule matching a value only this test ever generated can only
 * fire for this test.
 *
 * Terra knows nothing about your endpoints or your payloads, so it exposes stubbing,
 * sending and inspection, and you name the operations — the same way you name your
 * identifiers and your tags:
 *
 * ```kotlin
 * // system-tests/src/test/kotlin/tests/Simulator.kt
 * fun SimulatorProbe.acceptOrders() =
 *     stub(path = "/orders", priority = 5, status = 201, body = """{"status":"ACCEPTED"}""")
 *
 * fun SimulatorProbe.rejectOrdersFrom(user: String) =
 *     stub(path = "/orders", priority = 1, status = 409, body = """{"status":"REJECTED"}""",
 *          matchingBody = "${'$'}[?(@.user == '${'$'}user')]")
 * ```
 *
 * The trap all of this replaces is the shared fixture name: a rule about `"frank"` on
 * a shared mock is a rule that fires for somebody else's test. Whenever you put a
 * literal in a matcher, ask what stops another test using the same literal.
 *
 * If your services ever do propagate `X-Test-Id`, [scopedByTestIdHeader] adds header
 * matching on top. Opt-in, because a rule matching a header that never arrives is a
 * rule that silently never fires.
 *
 * Backed by WireMock's admin API; no test needs to know that.
 */
class SimulatorProbe(
    private val endpoint: HostPort,
    private val testId: String,
    private val scopeByHeader: Boolean = false,
) {

    fun scopedByTestIdHeader() = SimulatorProbe(endpoint, testId, scopeByHeader = true)

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        // Java's default is HTTP/2, which over plain HTTP means every request opens
        // with an h2c upgrade. Jetty and nginx shrug and answer 1.1; Node destroys
        // the socket, and the test sees "header parser received no bytes" from a
        // service that is running perfectly. No test traffic here needs HTTP/2.
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    private val mapper = jacksonObjectMapper()

    /**
     * An event the simulator publishes on its own, [after] the call it answered.
     *
     * This is the half a canned response cannot express: a dependency that accepts a
     * request now and reports the outcome later. [value] is templated the same way
     * [stub]'s body is, so derive it from the request — `originalRequest` here,
     * `request` in the response — and both carry the same id.
     */
    data class Publish(
        val topic: String,
        val value: String,
        val after: Duration = Duration.ofSeconds(4),
    )

    /**
     * Answer [status] with [body] for requests to [path].
     *
     * [matchingBody] is a JSONPath expression; give it one built from an id only this
     * test generated, or the rule belongs to everybody. Lower [priority] wins, so a
     * specific rule at 1 overrides a catch-all at 5 and nothing else changes.
     *
     * [thenPublish] makes the dependency asynchronous: the caller gets its response
     * immediately and the event lands later, which is the behaviour a test cannot
     * fake by publishing the event itself — it does not know when the call happened.
     */
    fun stub(
        path: String,
        status: Int,
        body: String,
        method: String = "POST",
        priority: Int = 5,
        matchingBody: String? = null,
        contentType: String = "application/json",
        thenPublish: Publish? = null,
    ): SimulatorProbe {
        val request = mapper.createObjectNode().apply {
            put("method", method)
            put("urlPath", path)
            matchingBody?.let { putArray("bodyPatterns").addObject().put("matchesJsonPath", it) }
            if (scopeByHeader) putObject("headers").putObject("X-Test-Id").put("equalTo", testId)
        }
        val mapping = mapper.createObjectNode().apply {
            put("priority", priority)
            set<ObjectNode>("request", request)
            putObject("response").apply {
                put("status", status)
                put("body", body)
                // The simulator runs with --local-response-templating, which is opt-in
                // per stub. Without this a body containing a template is returned with
                // the braces still in it — no error, just a wrong answer.
                putArray("transformers").add("response-template")
                putObject("headers").put("Content-Type", contentType)
            }
            thenPublish?.let {
                putArray("postServeActions").addObject().apply {
                    put("name", "webhook")
                    putObject("parameters").apply {
                        put("method", "POST")
                        // The webhook fires from inside the container network, so this
                        // is a service name and a container port, never an endpoint from
                        // the descriptor — those are addresses for the test, not for a
                        // service talking to another service.
                        put("url", "${Terra.kafkaRestUrl}/topics/${it.topic}")
                        putObject("headers")
                            .put("Content-Type", "application/vnd.kafka.json.v2+json")
                        put("body", """{"records":[{"value":${it.value}}]}""")
                        putObject("delay")
                            .put("type", "fixed")
                            .put("milliseconds", it.after.toMillis())
                    }
                }
            }
            // Tagged so every rule this test made can be removed in one call.
            putObject("metadata").put("testId", testId)
        }
        Journal.record("simulator", "stub $method $path p$priority -> $status") {
            post("/__admin/mappings", mapper.writeValueAsString(mapping)).expect(201)
        }
        return this
    }

    /** Send traffic yourself, standing in for the service that would normally call. */
    fun send(path: String, body: String, method: String = "POST"): HttpProbe.Response =
        Journal.record("simulator", "$method $path") { post(path, body, simulatedTraffic = true) }

    /**
     * Requests this mock received, filtered by [matching].
     *
     * The predicate is required rather than optional: without the header there is
     * nothing else that makes a request yours, and an unfiltered read quietly returns
     * every concurrent test's traffic as if it were your own.
     */
    fun requestsReceived(
        path: String,
        method: String = "POST",
        matching: (JsonNode) -> Boolean,
    ): List<JsonNode> {
        val query = mapper.createObjectNode().apply {
            put("method", method)
            put("urlPath", path)
            if (scopeByHeader) putObject("headers").putObject("X-Test-Id").put("equalTo", testId)
        }
        val response = post("/__admin/requests/find", mapper.writeValueAsString(query)).expect(200)
        return mapper.readTree(response.body)["requests"]
            .map { mapper.readTree(it["body"].asText()) }
            .filter(matching)
    }

    /**
     * Remove this test's rules — by the metadata tag, so it is scoped whether or not
     * the header is. Called for you when the test ends; forgetting would be untidy
     * rather than harmful, because ids are unique per execution.
     */
    fun reset() {
        runCatching {
            post(
                "/__admin/mappings/remove-by-metadata",
                """{"matchesJsonPath":{"expression":"${'$'}.testId","equalTo":"$testId"}}""",
            )
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun post(path: String, body: String, simulatedTraffic: Boolean = false): HttpProbe.Response {
        // Admin calls always carry the id; only simulated traffic drops it, so a test
        // without propagation exercises the same path its services do.
        val request = HttpRequest.newBuilder(URI("http://$endpoint$path"))
            .timeout(Duration.ofSeconds(10L * timeoutScale))
            .header("X-Test-Id", if (simulatedTraffic && !scopeByHeader) "not-propagated" else testId)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return HttpProbe.Response(response.statusCode(), response.body())
    }

    private fun HttpProbe.Response.expect(vararg statuses: Int): HttpProbe.Response {
        check(status in statuses) { "simulator returned $status: $body" }
        return this
    }
}
