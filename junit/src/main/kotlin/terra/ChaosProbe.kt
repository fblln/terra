package terra

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Network faults, scoped to a block that always heals.
 *
 * A Toxiproxy sits between a caller and a dependency. Disabling the proxy severs the
 * connection while leaving the dependency itself healthy and directly observable by the
 * test — which is what makes assertions *during* an outage possible.
 *
 * ```kotlin
 * ctx.chaos.withNetworkPartition("store-simulator") {
 *     // the dependency is unreachable in here
 * }
 * // reachable again, whether the block returned, threw, or was cancelled
 * ```
 *
 * **Always exclusive.** A severed connection is severed for everybody, so unlike every
 * other probe here this one cannot be scoped by identity. It refuses to run outside an
 * `@ExclusiveEnvTest` rather than silently breaking whatever else is in flight — the
 * one case where the honest answer really is "run alone".
 *
 * This replaces a `compose/faults/` overlay for anything short-lived: no second
 * topology, no restart, and recovery is asserted in the same test as the failure.
 */
class ChaosProbe(private val control: HostPort, private val exclusive: Boolean) {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun <T> withNetworkPartition(vararg targets: String, block: () -> T): T {
        check(exclusive) {
            "network partition is global and cannot be scoped to one test.\n" +
                "Mark this test @ExclusiveEnvTest so nothing else is running while the " +
                "connection is severed."
        }
        Journal.note("chaos", "partition ${targets.joinToString()}", "severed")
        targets.forEach { enable(it, false) }
        return try {
            block()
        } finally {
            // Heals on success, failure and cancellation alike. A test that leaves a
            // partition standing poisons every test after it.
            targets.forEach { runCatching { enable(it, true) } }
            Journal.note("chaos", "heal ${targets.joinToString()}", "restored")
        }
    }

    fun isUp(target: String): Boolean =
        send("GET", "/proxies/$target", null).let { it.status == 200 && it.body.contains("\"enabled\":true") }

    private fun enable(target: String, enabled: Boolean) {
        val response = send("POST", "/proxies/$target", """{"enabled":$enabled}""")
        check(response.status == 200) {
            "toxiproxy refused to set '$target' enabled=$enabled: ${response.status} ${response.body}"
        }
    }

    private fun send(method: String, path: String, body: String?): HttpProbe.Response {
        val request = HttpRequest.newBuilder(URI("http://$control$path"))
            .timeout(Duration.ofSeconds(10L * timeoutScale))
            .header("Content-Type", "application/json")
            .method(
                method,
                body?.let { HttpRequest.BodyPublishers.ofString(it) }
                    ?: HttpRequest.BodyPublishers.noBody(),
            )
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return HttpProbe.Response(response.statusCode(), response.body())
    }
}
