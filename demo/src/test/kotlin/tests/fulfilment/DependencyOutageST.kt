package tests.fulfilment

import terra.*
import org.junit.jupiter.api.Tag
import tests.Tags
import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import java.time.Instant

/**
 * How the service behaves when its dependency is unreachable.
 *
 * `orders-api` calls the store simulator through the chaos proxy, so a test can sever
 * that hop and watch what the *service* does — which is the question a system test is
 * uniquely able to answer. A component test can mock the dependency into returning an
 * error; only this tier can take the network away.
 *
 * All exclusive, because a severed connection is severed for everybody.
 */
@Tag(Tags.REGRESSION) @Tag(Tags.SHIPPING)
@Environment("fulfilment")
class DependencyOutageST : SystemTest() {

    @ExclusiveEnvTest
    fun `the dependency is reachable to begin with`(ctx: TerraContext) {
        ctx.requires("chaos")
        ctx.simulator.acceptOrders()

        val response = ctx.http("orders-api").post("/carrier", order(ctx))

        assertThat(response.status).isEqualTo(201)
        assertThat(response.json()["status"].asText()).isEqualTo("ACCEPTED")
    }

    @ExclusiveEnvTest
    fun `an unreachable dependency degrades to a stated contract, not a leak`(ctx: TerraContext) {
        ctx.requires("chaos")
        ctx.simulator.acceptOrders()

        ctx.chaos.withNetworkPartition("store-simulator") {
            val response = ctx.http("orders-api").post("/carrier", order(ctx))

            // Not a 500, not nginx's own 502 — the service's own documented degradation.
            assertThat(response.status).isEqualTo(503)
            assertThat(response.json()["status"].asText()).isEqualTo("DEGRADED")
            assertThat(response.json()["reason"].asText()).isEqualTo("CARRIER_UNAVAILABLE")
        }
    }

    @ExclusiveEnvTest
    fun `the service stays healthy while its dependency is down`(ctx: TerraContext) {
        ctx.requires("chaos")

        ctx.chaos.withNetworkPartition("store-simulator") {
            // The failure mode this guards against is a cascade: a service that reports
            // itself unhealthy because something downstream is down gets restarted by
            // its orchestrator, and the outage gets wider instead of narrower.
            repeat(3) {
                assertThat(ctx.http("orders-api").get("/health").status).isEqualTo(200)
            }
            // Unrelated routes keep working too — the blast radius is one dependency.
            assertThat(ctx.http("orders-api").get("/config").status).isEqualTo(200)
        }
    }

    @ExclusiveEnvTest
    fun `the failure is bounded by the configured timeout, it does not hang`(ctx: TerraContext) {
        ctx.requires("chaos")
        val budget = ctx.http("orders-api").get("/config").json()["carrierTimeoutMs"].asInt()

        ctx.chaos.withNetworkPartition("store-simulator") {
            val started = Instant.now()
            val response = ctx.http("orders-api").post("/carrier", order(ctx))
            val took = Duration.between(started, Instant.now())

            assertThat(response.status).isEqualTo(503)
            // Generous, because we are asserting "bounded", not "fast". A hang shows up
            // here as a test that fails, rather than one that eventually times out and
            // tells you nothing about where.
            assertThat(took).isLessThan(Duration.ofMillis(budget.toLong() * 4 + 3000))
        }
    }

    @ExclusiveEnvTest
    fun `it recovers by itself once the dependency comes back`(ctx: TerraContext) {
        ctx.requires("chaos")
        ctx.simulator.acceptOrders()

        ctx.chaos.withNetworkPartition("store-simulator") {
            assertThat(ctx.http("orders-api").post("/carrier", order(ctx)).status).isEqualTo(503)
        }

        // No restart, no manual intervention — which is the property that actually
        // matters on a Sunday. `eventually` covers the DNS/connection re-establishment.
        eventually {
            assertThat(ctx.http("orders-api").post("/carrier", order(ctx)).status).isEqualTo(201)
        }
    }

    @ExclusiveEnvTest
    fun `an outage leaves no trace in the read model`(ctx: TerraContext) {
        ctx.requires("chaos")
        ctx.simulator.acceptOrders()
        ctx.mongo.orders.insert(ctx.ids.order(), "state" to "NEW")

        ctx.chaos.withNetworkPartition("store-simulator") {
            assertThat(ctx.http("orders-api").post("/carrier", order(ctx)).status).isEqualTo(503)
        }

        // A failed downstream call must not have half-committed anything. Here the
        // service is a proxy so there is nothing to commit; in a real service this is
        // the assertion that catches a write that happened before the call failed.
        assertThat(ctx.mongo.orders.require(ctx.ids.order()).getString("state")).isEqualTo("NEW")
        ctx.kafka.shouldNotBePublished<ShipmentReady>("shipments") { it.order == ctx.ids.order() }
    }

    private fun order(ctx: TerraContext) = """{"user":"${ctx.ids.user()}","sku":"${ctx.ids.sku()}"}"""
}
