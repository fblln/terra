package tests.fulfilment

import terra.*
import org.junit.jupiter.api.Tag
import tests.*
import org.assertj.core.api.Assertions.assertThat

data class ShipmentReady(val order: String = "", val state: String = "")

/**
 * The isolation model, demonstrated rather than asserted about.
 *
 * Both tests publish to the same topic at the same time. Neither truncates it and
 * neither can see the other's events, because each reads forward from its own
 * checkpoint and filters by an order id only it knows.
 */
@Tag(Tags.REGRESSION) @Tag(Tags.SHIPPING)
@Environment("fulfilment")
class KafkaCheckpointST : SystemTest() {

    @SharedEnvTest
    fun `reads only its own events, forward from the checkpoint`(ctx: TerraContext) {
        val mark = ctx.kafka.checkpoint(TOPIC)

        ctx.kafka.publish(TOPIC, ShipmentReady(order = "ORD-someone-else", state = "READY"))
        ctx.kafka.publish(TOPIC, ShipmentReady(order = ctx.ids.order(), state = "READY"))

        val event = ctx.kafka.awaitAfter<ShipmentReady>(mark, TOPIC) { it.order == ctx.ids.order() }
        assertThat(event.state).isEqualTo("READY")
    }

    @SharedEnvTest
    fun `a checkpoint hides everything that happened before it`(ctx: TerraContext) {
        ctx.kafka.publish(TOPIC, ShipmentReady(order = ctx.ids.order(2), state = "BEFORE"))

        val mark = ctx.kafka.checkpoint(TOPIC)      // taken after the event above
        ctx.kafka.publish(TOPIC, ShipmentReady(order = ctx.ids.order(3), state = "AFTER"))

        val event = ctx.kafka.awaitAfter<ShipmentReady>(mark, TOPIC) { it.state == "AFTER" }
        assertThat(event.order).isEqualTo(ctx.ids.order(3))
    }

    private companion object {
        const val TOPIC = "shipments"
    }
}
