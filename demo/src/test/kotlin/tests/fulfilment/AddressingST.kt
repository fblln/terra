package tests.fulfilment

import terra.*
import org.junit.jupiter.api.Tag
import tests.*
import org.assertj.core.api.Assertions.assertThat

/**
 * The two addresses of one database, pinned as a test because it is the thing people
 * get wrong first.
 *
 * A service reaching MongoDB **inside** the Docker network uses the compose service
 * name on its container port: `mongodb:27017`. That is stable, known when you write
 * the compose file, and identical in every environment — so it is not dynamic and
 * needs no discovery.
 *
 * A test reaching the same MongoDB **from the host** uses an ephemeral port Docker
 * chose at `up`. That is the dynamic one, and it is dynamic on purpose: fixed host
 * ports would stop two environments coexisting, which is what makes attach-and-run
 * possible at all.
 *
 * Configure services with the first. Discover the second from the descriptor.
 */
@Tag(Tags.SMOKE) @Tag(Tags.INVENTORY)
@Environment("fulfilment")
class AddressingST : SystemTest() {

    @SharedEnvTest
    fun `the service is configured with the internal name, not a discovered port`(ctx: TerraContext) {
        val config = ctx.http("orders-api").get("/config").json()

        assertThat(config["mongoUri"].asText()).isEqualTo("mongodb://mongodb:27017")
        assertThat(config["kafkaBootstrap"].asText()).isEqualTo("kafka:9092")

        // Nothing in the service's configuration mentions localhost or an ephemeral
        // port, and it should not: inside the network those would be meaningless.
        assertThat(config["mongoUri"].asText()).doesNotContain("localhost")
        assertThat(config["kafkaBootstrap"].asText()).doesNotContain("localhost")
    }

    @SharedEnvTest
    fun `the test reaches the same database on a different, discovered address`(ctx: TerraContext) {
        val fromOutside = ctx.endpoint("mongodb")

        assertThat(fromOutside.host).isEqualTo("localhost")
        assertThat(fromOutside.port).isNotEqualTo(27017)     // Docker chose it

        // And it works — the address came from the descriptor, which came from Docker.
        ctx.mongo.orders.insert(ctx.ids.order(), "state" to "PENDING")
        assertThat(ctx.mongo.orders.require(ctx.ids.order()).getString("state")).isEqualTo("PENDING")
    }

    @SharedEnvTest
    fun `a service that must publish its own address gets a derived, stable port`(ctx: TerraContext) {
        // Kafka cannot live behind an ephemeral port: advertised.listeners hands its
        // address to clients. Such services go under `hostPorts:` and get a port
        // derived from the fingerprint — stable for this environment, distinct across
        // environments. That is the escape hatch when an address really is dynamic
        // and the service itself has to know it.
        val kafka = ctx.endpoint("kafka")

        assertThat(kafka.host).isEqualTo("localhost")
        assertThat(kafka.port).isBetween(20000, 40000)
        assertThat(ctx.kafka.checkpoint("shipments")).isNotEmpty()
    }
}
