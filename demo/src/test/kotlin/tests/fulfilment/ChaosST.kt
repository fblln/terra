package tests.fulfilment

import terra.*
import org.junit.jupiter.api.Tag
import tests.*
import org.assertj.core.api.Assertions.assertThat
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Severing a connection for the length of a block, and asserting recovery in the same
 * test as the failure.
 *
 * The caller reaches the simulator through a Toxiproxy; disabling the proxy makes the
 * dependency unreachable *to the caller* while leaving it healthy and directly
 * observable *to the test*. That asymmetry is the whole point — it is what lets a test
 * check what the system does during an outage rather than only afterwards.
 *
 * This is the one probe that cannot be scoped by identity: a severed connection is
 * severed for everybody. So it is exclusive, and the harness refuses if you forget.
 */
@Tag(Tags.REGRESSION) @Tag(Tags.SHIPPING)
@Environment("fulfilment")
class ChaosST : SystemTest() {

    @ExclusiveEnvTest
    fun `a partition makes the dependency unreachable, and it recovers afterwards`(
        ctx: TerraContext,
    ) {
        ctx.requires("chaos")
        ctx.simulator.acceptOrders()

        val throughProxy = ctx.endpoint("simulator-proxied")
        assertThat(placeOrderVia(throughProxy, ctx.ids.user()).first).isEqualTo(201)

        ctx.chaos.withNetworkPartition("store-simulator") {
            val failure = runCatching { placeOrderVia(throughProxy, ctx.ids.user()) }
            assertThat(failure.isFailure).isTrue()

            // Still healthy, and still reachable directly — only the proxied path is cut.
            assertThat(ctx.http("store-simulator").get("/__admin/mappings").status).isEqualTo(200)
        }

        // Healed by the block, not by a teardown someone has to remember.
        eventually { assertThat(placeOrderVia(throughProxy, ctx.ids.user()).first).isEqualTo(201) }
        assertThat(ctx.chaos.isUp("store-simulator")).isTrue()
    }

    @ExclusiveEnvTest
    fun `the partition heals even when the block throws`(ctx: TerraContext) {
        ctx.requires("chaos")

        val thrown = runCatching {
            ctx.chaos.withNetworkPartition("store-simulator") { error("boom") }
        }

        assertThat(thrown.isFailure).isTrue()
        assertThat(ctx.chaos.isUp("store-simulator")).isTrue()
    }

    @SharedEnvTest
    fun `a shared test is refused rather than allowed to cut everyone else off`(ctx: TerraContext) {
        val refused = runCatching { ctx.chaos.withNetworkPartition("store-simulator") { } }

        assertThat(refused.isFailure).isTrue()
        assertThat(refused.exceptionOrNull().toString()).contains("@ExclusiveEnvTest")
    }

    private fun placeOrderVia(endpoint: HostPort, user: String): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI("http://$endpoint/orders"))
            .timeout(java.time.Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"user":"$user","sku":"SKU-1"}"""))
            .build()
        // HTTP/1.1 for the same reason the probes pin it: the default tries an h2c
        // upgrade, which a Node-based dependency answers by dropping the connection.
        val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }
}
