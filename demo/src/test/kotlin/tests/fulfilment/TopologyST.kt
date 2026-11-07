package tests.fulfilment

import terra.*
import org.junit.jupiter.api.Tag
import tests.*
import org.assertj.core.api.Assertions.assertThat

/**
 * The whole loop without any application code: the harness started a topology, wrote a
 * descriptor, this JVM attached to it, and discovered every address from it.
 *
 * Also the two addresses of one database — `mongodb:27017` from inside the network,
 * an ephemeral port from the host. Configure services with the first, discover the
 * second.
 */
@Tag(Tags.SMOKE) @Tag(Tags.INVENTORY) @Tag(Tags.SHIPPING)
@Environment("fulfilment")
class TopologyST : SystemTest() {

    @SharedEnvTest
    fun `services answer on the ports the harness discovered`(ctx: TerraContext) {
        // Nothing in this test knows a port number. Docker chose it, terra looked it
        // up, and the descriptor carried it here.
        eventually { assertThat(ctx.http("gateway").get("/").status).isEqualTo(200) }

        val orders = ctx.http("orders-api").get("/")
        assertThat(orders.status).isEqualTo(200)
        assertThat(orders.body.trim()).isEqualTo("orders-api")
    }

    @SharedEnvTest
    fun `the configuration the environment declared is the configuration it received`(ctx: TerraContext) {
        val config = ctx.http("orders-api").get("/config").json()

        assertThat(config["serviceName"].asText()).isEqualTo("orders-api")
        assertThat(config["version"].asText()).isEqualTo("1.17.4")
        assertThat(config["profile"].asText()).isEqualTo("systest")
        assertThat(config["region"].asText()).isEqualTo("eu-west-1")

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
    fun `the service is configured with internal names, not discovered ports`(ctx: TerraContext) {
        val config = ctx.http("orders-api").get("/config").json()

        assertThat(config["mongoUri"].asText()).isEqualTo("mongodb://mongodb:27017")
        assertThat(config["kafkaBootstrap"].asText()).isEqualTo("kafka:9092")
        assertThat(config["shipmentsTopic"].asText()).isEqualTo("shipments")
        assertThat(config["stockTopic"].asText()).isEqualTo("stock-moves")

        // Inside the network, localhost and an ephemeral port would be meaningless.
        assertThat(config["mongoUri"].asText()).doesNotContain("localhost")
        assertThat(config["kafkaBootstrap"].asText()).doesNotContain("localhost")
    }

    @SharedEnvTest
    fun `the test reaches the same database on a different, discovered address`(ctx: TerraContext) {
        val fromOutside = ctx.endpoint("mongodb")

        assertThat(fromOutside.host).isEqualTo("localhost")
        assertThat(fromOutside.port).isNotEqualTo(27017)     // Docker chose it

        ctx.mongo.orders.insert(ctx.ids.order(), "state" to "PENDING")
        assertThat(ctx.mongo.orders.require(ctx.ids.order()).getString("state")).isEqualTo("PENDING")
    }

    @SharedEnvTest
    fun `a service that must publish its own address gets a derived, stable port`(ctx: TerraContext) {
        // Kafka cannot live behind an ephemeral port: advertised.listeners hands its
        // address to clients. Such services go under `hostPorts:` and get a port
        // derived from the fingerprint — stable here, distinct across environments.
        val kafka = ctx.endpoint("kafka")

        assertThat(kafka.host).isEqualTo("localhost")
        assertThat(kafka.port).isBetween(20000, 40000)
        assertThat(ctx.kafka.checkpoint("shipments")).isNotEmpty()
    }

    @SharedEnvTest
    fun `an unknown route is a 404, not a 500`(ctx: TerraContext) {
        val response = ctx.http("orders-api").get("/no-such-thing")

        assertThat(response.status).isEqualTo(404)
        assertThat(response.body).contains("no such route")
    }

    @SharedEnvTest
    fun `the environment advertises what it can do`(ctx: TerraContext) {
        ctx.requires("kafka")
        ctx.requires("orders")
        assertThat(ctx.descriptor.capabilities).contains("kafka", "mongo")
        assertThat(ctx.descriptor.endpoints).containsKey("orders-api")

        // The returns stack is a different topology, and this one really is not it.
        assertThat(ctx.descriptor.capabilities).doesNotContain("returns")
        assertThat(ctx.descriptor.endpoints).doesNotContainKey("returns-api")
    }

    @SharedEnvTest
    fun `every test gets identities nothing else can collide with`(ctx: TerraContext) {
        assertThat(ctx.ids.order()).startsWith("ORD-")
        assertThat(ctx.ids.group()).startsWith("terra-")
        // Derived from (run, test), so two tests can share a database and a topic
        // without either one truncating anything.
        assertThat(ctx.ids.order()).isNotEqualTo(TestIds("other", "test").order())
    }

    @SharedEnvTest
    fun `migrations ran before the first test`(ctx: TerraContext) {
        // Seeded by SeedReferenceData, once per JVM, idempotently.
        val carriers = ctx.mongo.collection("carriers")

        assertThat(carriers.get("dhl")?.getInteger("transitDays")).isEqualTo(2)
        assertThat(carriers.get("royal-mail")?.getInteger("transitDays")).isEqualTo(5)
    }
}
