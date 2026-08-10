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
 * A store simulator every test can reconfigure at runtime, without restarting
 * anything and without disturbing the tests running beside it.
 *
 * The isolation trick is the same one used for MongoDB and Kafka: **scope by an
 * identity nothing else uses**. By default that identity lives in the request
 * *payload* — `ctx.ids.user()`, `order()`, `sku()` — because the payload is the one
 * thing that survives the hop through the service under test. A rule matching a value
 * only this test ever generated can only fire for this test.
 *
 * That is what makes "configure the simulator" a per-test operation rather than an
 * environment-wide one. Nothing is reset, so nothing has to be serialised.
 *
 * The trap it replaces is the shared fixture name: a rule about `"frank"` on a shared
 * simulator is a rule that fires for somebody else's test. Whenever you put a literal
 * in a matcher, ask what stops another test using the same literal.
 *
 * If your services ever do propagate `X-Test-Id`, [scopedByTestIdHeader] adds header
 * matching on top. It is strictly nicer when available and entirely optional.
 */
class SimulatorProbe(
    private val endpoint: HostPort,
    private val testId: String,
    private val scopeByHeader: Boolean = false,
) {

    /**
     * Also match on `X-Test-Id`, for the day your services propagate it.
     *
     * Opt-in, because a rule that matches a header which never arrives is a rule that
     * silently never fires — a worse failure than no rule at all. Until propagation
     * exists, scope on the payload.
     */
    fun scopedByTestIdHeader() = SimulatorProbe(endpoint, testId, scopeByHeader = true)

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val mapper = jacksonObjectMapper()

    // ------------------------------------------------------------------ rules

    /** Accept any order this test places. Register last-resort behaviour first. */
    fun acceptOrders(): SimulatorProbe = stub(
        priority = 5,
        match = mapper.createObjectNode(),
        status = 201,
        body = """{"status":"ACCEPTED"}""",
    )

    /**
     * Reject orders from one user, for this test only.
     *
     * Higher priority than [acceptOrders], so it wins for that user and nothing else
     * changes — which is how a rule should behave.
     */
    fun rejectOrdersFrom(user: String, status: Int = 409, reason: String = "USER_BLOCKED"): SimulatorProbe =
        stub(
            priority = 1,
            match = mapper.createObjectNode().apply {
                putArray("bodyPatterns").addObject()
                    .put("matchesJsonPath", "$[?(@.user == '$user')]")
            },
            status = status,
            body = """{"status":"REJECTED","reason":"$reason","user":"$user"}""",
        )

    /** Fail every order with a server error — for testing what the system does about it. */
    fun failOrders(status: Int = 503): SimulatorProbe = stub(
        priority = 1,
        match = mapper.createObjectNode(),
        status = status,
        body = """{"status":"UNAVAILABLE"}""",
    )

    private fun stub(priority: Int, match: ObjectNode, status: Int, body: String): SimulatorProbe {
        val request = (match.deepCopy() as ObjectNode).apply {
            put("method", "POST")
            put("urlPath", "/orders")
            // The scoping that makes this safe under concurrency — when the header
            // survives the hop. When it does not, the caller's matcher carries it.
            if (scopeByHeader) {
                putObject("headers").putObject("X-Test-Id").put("equalTo", testId)
            }
        }
        val mapping = mapper.createObjectNode().apply {
            put("priority", priority)
            set<ObjectNode>("request", request)
            putObject("response")
                .put("status", status)
                .put("body", body)
                .putObject("headers").put("Content-Type", "application/json")
            // Tagged so every rule this test made can be removed in one call.
            putObject("metadata").put("testId", testId)
        }
        Journal.record("simulator", "rule p$priority -> $status") {
            post("/__admin/mappings", mapper.writeValueAsString(mapping)).expect(201)
        }
        return this
    }

    // ------------------------------------------------------------- interaction

    /**
     * Place an order. Scoping comes from [user], so pass `ctx.ids.user()` — a literal
     * shared with another test is a collision waiting to happen.
     */
    fun placeOrder(user: String, sku: String = "SKU-1"): HttpProbe.Response =
        Journal.record("simulator", "placeOrder $user") {
            post("/orders", """{"user":"$user","sku":"$sku"}""")
        }

    /** Every order this test sent, as the simulator saw it. */
    fun ordersReceived(matchingUser: String? = null): List<JsonNode> {
        check(scopeByHeader || matchingUser != null) {
            "without the X-Test-Id header there is nothing to scope by — " +
                "pass ordersReceived(matchingUser = ctx.ids.user())"
        }
        val response = post("/__admin/requests/find", mapper.writeValueAsString(scopedToThisTest()))
            .expect(200)
        return mapper.readTree(response.body)["requests"]
            .map { mapper.readTree(it["body"].asText()) }
            .filter { matchingUser == null || it["user"].asText() == matchingUser }
    }

    /**
     * Remove this test's rules. Called automatically when the test ends, so a test
     * never has to remember — and because ids are unique per execution, forgetting
     * would be untidy rather than harmful.
     */
    fun reset() {
        // Best effort on both counts: a test that already failed should not fail
        // twice because cleanup of its own scratch state did not answer.
        runCatching {
            post(
                "/__admin/mappings/remove-by-metadata",
                """{"matchesJsonPath":{"expression":"${'$'}.testId","equalTo":"$testId"}}""",
            )
        }
        // Only when the header scopes it. Without one, "this test's requests" is not
        // expressible as a WireMock query, and removing by method+path would wipe every
        // concurrent test's journal — which is exactly what it did until a test caught
        // it. The journal is bounded by --max-request-journal-entries instead.
        if (scopeByHeader) {
            runCatching {
                post("/__admin/requests/remove", mapper.writeValueAsString(scopedToThisTest()))
            }
        }
    }

    private fun scopedToThisTest() = mapper.createObjectNode().apply {
        put("method", "POST")
        put("urlPath", "/orders")
        if (scopeByHeader) {
            putObject("headers").putObject("X-Test-Id").put("equalTo", testId)
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun post(path: String, body: String): HttpProbe.Response {
        // Admin calls always carry the id; only the simulated *traffic* drops it, so
        // that a test without propagation is exercising the same path its services do.
        val simulatedTraffic = path == "/orders"
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
