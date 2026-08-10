package tests.fulfilment

import terra.*
import org.junit.jupiter.api.Tag
import tests.Tags
import org.assertj.core.api.Assertions.assertThat

/**
 * A service whose behaviour is entirely a function of its environment variables —
 * the shape of every Spring Boot service you will point this at.
 *
 * The values under test are declared once, in `environments/fulfilment.yml`, and the
 * service echoes them back on /config. That makes configuration itself testable,
 * which matters because a service with a hundred environment variables has its
 * largest failure surface exactly there.
 */
@Tag(Tags.SMOKE) @Tag(Tags.SHIPPING)
@Environment("fulfilment")
class OrdersApiST : SystemTest() {

    @SharedEnvTest
    fun `the service is reachable on the port the harness discovered`(ctx: HarnessContext) {
        val response = ctx.http("orders-api").get("/")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.body.trim()).isEqualTo("orders-api")
    }

    @SharedEnvTest
    fun `the configuration the environment declared is the configuration it received`(ctx: HarnessContext) {
        val config = ctx.http("orders-api").get("/config").json()

        // Identity and build
        assertThat(config["serviceName"].asText()).isEqualTo("orders-api")
        assertThat(config["version"].asText()).isEqualTo("1.17.4")
        assertThat(config["profile"].asText()).isEqualTo("systest")
        assertThat(config["region"].asText()).isEqualTo("eu-west-1")

        // Wiring: names services use to find each other *inside* the network, which
        // are not the addresses this test uses from outside it.
        assertThat(config["mongoUri"].asText()).isEqualTo("mongodb://mongodb:27017")
        assertThat(config["kafkaBootstrap"].asText()).isEqualTo("kafka:9092")
        assertThat(config["shipmentsTopic"].asText()).isEqualTo("shipments")
        assertThat(config["stockTopic"].asText()).isEqualTo("stock-moves")
    }

    @SharedEnvTest
    fun `numeric and boolean settings arrive with the right types`(ctx: HarnessContext) {
        val config = ctx.http("orders-api").get("/config").json()

        assertThat(config["carrierTimeoutMs"].asInt()).isEqualTo(2000)
        assertThat(config["retryMaxAttempts"].asInt()).isEqualTo(3)
        assertThat(config["reservationTtlSeconds"].asInt()).isEqualTo(900)
        assertThat(config["maxOrderLines"].asInt()).isEqualTo(50)

        // Feature flags are the settings most likely to be wrong in one environment
        // and right in another, which is the whole reason to assert on them.
        assertThat(config["featureSplitShipments"].asBoolean()).isTrue()
        assertThat(config["featureBackorder"].asBoolean()).isFalse()
        assertThat(config["featureExpressLane"].asBoolean()).isFalse()
        assertThat(config["metricsEnabled"].asBoolean()).isTrue()
    }

    @SharedEnvTest
    fun `an unknown route is a 404, not a 500`(ctx: HarnessContext) {
        val response = ctx.http("orders-api").get("/no-such-thing")

        assertThat(response.status).isEqualTo(404)
        assertThat(response.body).contains("no such route")
    }

    @SharedEnvTest
    fun `the environment declares that this service is present`(ctx: HarnessContext) {
        ctx.requires("orders")
        assertThat(ctx.descriptor.endpoints).containsKey("orders-api")
    }
}
