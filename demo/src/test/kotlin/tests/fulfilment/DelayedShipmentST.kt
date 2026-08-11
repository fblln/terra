package tests.fulfilment

import terra.*
import org.junit.jupiter.api.Tag
import tests.*
import org.assertj.core.api.Assertions.assertThat
import java.time.Duration

/**
 * A dependency that answers now and reports the outcome later.
 *
 * Every other simulator test here asserts on a canned response. This one asserts on
 * something a canned response cannot do: the caller is unblocked immediately, and the
 * event that says what actually happened arrives four seconds after the call — from
 * the simulator, not from the test. A test cannot fake that by publishing the event
 * itself, because it does not know when the service under test made the call.
 *
 * The absence assertion is the part that earns its keep. Without it this test passes
 * whether the delay is four seconds or zero, and "it eventually happened" is a much
 * weaker statement than "it had not happened yet, and then it did".
 */
@Tag(Tags.REGRESSION) @Tag(Tags.SHIPPING)
@Environment("fulfilment")
class DelayedShipmentST : SystemTest() {

    @SharedEnvTest
    fun `the carrier accepts immediately and ships four seconds later`(ctx: TerraContext) {
        ctx.requires("simulator")
        ctx.simulator.shipOrder(ctx.ids.order(), after = Duration.ofSeconds(4))

        val response = ctx.simulator.submitOrder(ctx.ids.order())

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
        ctx.requires("simulator")
        ctx.simulator.shipOrder(ctx.ids.order(), after = Duration.ofSeconds(1))

        assertThat(ctx.simulator.submitOrder(ctx.ids.order()).status).isEqualTo(201)

        val shipped = ctx.kafka.shouldBePublished<ShipmentReady>("shipments") {
            it.order == ctx.ids.order()
        }
        assertThat(shipped.order).isEqualTo(ctx.ids.order())
    }
}
