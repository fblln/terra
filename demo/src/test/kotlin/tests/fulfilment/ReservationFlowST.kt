package tests.fulfilment

import terra.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import tests.Tags
import org.assertj.core.api.Assertions.assertThat

/**
 * A flow across all three surfaces at once — HTTP, MongoDB, Kafka — which is what a
 * real system test usually is. Every step uses ids derived from (execution, test),
 * so this runs concurrently with everything else without a reset anywhere.
 */
@Tag(Tags.REGRESSION) @Tag(Tags.INVENTORY) @Tag(Tags.SHIPPING)
@Environment("fulfilment")
class ReservationFlowST : SystemTest() {

    @BeforeEach
    fun seed(ctx: TerraContext) {
        ctx.mongo.inventory.insert(ctx.ids.sku(), "onHand" to 3, "reserved" to 0)
        ctx.mongo.orders.insert(ctx.ids.order(), "sku" to ctx.ids.sku(), "state" to "NEW", "quantity" to 2)
    }

    @SharedEnvTest
    fun `a reservation moves stock, emits an event and lands in the read model`(ctx: TerraContext) {
        val mark = ctx.kafka.checkpoint("stock-moves")

        // The service is reachable and configured before we lean on it. In a real
        // suite this is the POST that starts the work.
        assertThat(ctx.http("orders-api").get("/health").status).isEqualTo(200)

        // Stands in for the inventory service reacting. In a real suite these two
        // writes are the system's job and the test only waits for them.
        ctx.kafka.publish("stock-moves", StockMoved(sku = ctx.ids.sku(), delta = -2, seq = 1))
        ctx.mongo.inventory.set(ctx.ids.sku(), "onHand" to 1, "reserved" to 2)
        ctx.mongo.orders.set(ctx.ids.order(), "state" to "RESERVED")

        val moved = ctx.kafka.awaitAfter<StockMoved>(mark, "stock-moves") { it.sku == ctx.ids.sku() }
        assertThat(moved.delta).isEqualTo(-2)

        val stock = ctx.mongo.inventory.await(ctx.ids.sku()) { it.getInteger("reserved") == 2 }
        assertThat(stock.getInteger("onHand")).isEqualTo(1)

        val order = ctx.mongo.orders.await(ctx.ids.order()) { it.getString("state") == "RESERVED" }
        assertThat(order.getString("sku")).isEqualTo(ctx.ids.sku())
    }

    @SharedEnvTest
    fun `an over-reservation is rejected and emits nothing`(ctx: TerraContext) {
        val mark = ctx.kafka.checkpoint("stock-moves")

        ctx.mongo.orders.set(ctx.ids.order(), "state" to "REJECTED", "reason" to "INSUFFICIENT_STOCK")

        val order = ctx.mongo.orders.await(ctx.ids.order()) { it.getString("state") == "REJECTED" }
        assertThat(order.getString("reason")).isEqualTo("INSUFFICIENT_STOCK")

        // Asserting an absence needs a short explicit timeout — the default thirty
        // seconds would be thirty seconds of doing nothing, every run.
        val emitted = runCatching {
            ctx.kafka.awaitAfter<StockMoved>(mark, "stock-moves", java.time.Duration.ofSeconds(2)) {
                it.sku == ctx.ids.sku()
            }
        }
        assertThat(emitted.isFailure).isTrue()

        assertThat(ctx.mongo.inventory.require(ctx.ids.sku()).getInteger("onHand")).isEqualTo(3)
    }
}
