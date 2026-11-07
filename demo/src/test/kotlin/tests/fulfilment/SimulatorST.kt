package tests.fulfilment

import terra.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import tests.*
import org.assertj.core.api.Assertions.assertThat
import java.time.Duration

/**
 * Configuring a shared simulator per test, with no header propagation anywhere: the
 * scoping lives in the payload, which is the one thing that survives the hop through
 * the service under test. No propagation, no reset, still concurrent.
 */
@Tag(Tags.REGRESSION) @Tag(Tags.SHIPPING)
@Environment("fulfilment")
class SimulatorST : SystemTest() {

    private lateinit var sim: SimulatorProbe

    @BeforeEach
    fun defaultBehaviour(ctx: TerraContext) {
        ctx.requires("simulator")
        sim = ctx.simulator
        sim.acceptOrders()          // last-resort rule; this test's users only
    }

    @SharedEnvTest
    fun `a rule scoped by a test-unique user needs no propagation`(ctx: TerraContext) {
        val blocked = ctx.ids.user()          // usr-8f30-a1b2-001; nobody else has it

        sim.rejectOrdersFrom(blocked, status = 409, reason = "USER_BLOCKED")

        assertThat(sim.placeOrder(user = blocked).status).isEqualTo(409)

        // A different user of ours is unaffected, and so is every other test's.
        assertThat(sim.placeOrder(user = ctx.ids.user(2)).status).isEqualTo(201)
    }

    @SharedEnvTest
    fun `a concurrent test with its own user is untouched by that rule`(ctx: TerraContext) {
        // Runs beside the test above, against the same simulator, with no header to
        // tell them apart. They do not collide because their users cannot collide.
        assertThat(sim.placeOrder(user = ctx.ids.user()).status).isEqualTo(201)
    }

    @SharedEnvTest
    fun `requests can still be inspected, scoped by the same identity`(ctx: TerraContext) {
        val user = ctx.ids.user()

        sim.placeOrder(user = user, sku = ctx.ids.sku())
        sim.placeOrder(user = user, sku = ctx.ids.sku(2))

        val received = sim.ordersReceived(matchingUser = user)

        assertThat(received).hasSize(2)
        assertThat(received.map { it["sku"].asText() })
            .containsExactlyInAnyOrder(ctx.ids.sku(), ctx.ids.sku(2))
    }

    /**
     * A dependency that answers now and reports the outcome later — the thing a canned
     * response cannot do. The caller is unblocked immediately, and the event that says
     * what actually happened arrives four seconds after the call, from the simulator
     * rather than the test. A test cannot fake that by publishing the event itself,
     * because it does not know when the service under test made the call.
     *
     * The absence assertion is the part that earns its keep: without it this test
     * passes whether the delay is four seconds or zero.
     */
    @SharedEnvTest
    fun `the carrier accepts immediately and ships four seconds later`(ctx: TerraContext) {
        sim.shipOrder(ctx.ids.order(), after = Duration.ofSeconds(4))

        val response = sim.submitOrder(ctx.ids.order())

        assertThat(response.status).isEqualTo(201)
        // Derived from the request, so the test knew it before the simulator said it —
        // and so the event four seconds from now can carry the same one.
        assertThat(response.json()["shipment"].asText()).isEqualTo("SHP-${ctx.ids.order()}")

        ctx.kafka.shouldNotBePublished<ShipmentReady>("shipments", Duration.ofSeconds(2)) {
            it.order == ctx.ids.order()
        }

        val shipped = ctx.kafka.shouldBePublished<ShipmentReady>("shipments") {
            it.order == ctx.ids.order()
        }
        assertThat(shipped.state).isEqualTo("SHIPPED")
    }

    @SharedEnvTest
    fun `the delay is the simulator's, so two tests do not wait for each other`(ctx: TerraContext) {
        sim.shipOrder(ctx.ids.order(), after = Duration.ofSeconds(1))

        assertThat(sim.submitOrder(ctx.ids.order()).status).isEqualTo(201)

        val shipped = ctx.kafka.shouldBePublished<ShipmentReady>("shipments") {
            it.order == ctx.ids.order()
        }
        assertThat(shipped.order).isEqualTo(ctx.ids.order())
    }
}
