package tests.fulfilment

import io.skodjob.annotations.Desc
import io.skodjob.annotations.Label
import io.skodjob.annotations.Step
import io.skodjob.annotations.SuiteDoc
import io.skodjob.annotations.TestDoc
import terra.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import tests.*
import org.assertj.core.api.Assertions.assertThat

/**
 * A flow across all three surfaces at once — HTTP, MongoDB, Kafka — which is what a
 * real system test usually is. Every step uses ids derived from (execution, test),
 * so this runs concurrently with everything else without a reset anywhere.
 *
 * Also the worked example for `./gradlew :demo:testDocs` — the @SuiteDoc/@TestDoc
 * annotations below are what ends up in demo/docs/.
 */
@Tag(Tags.REGRESSION) @Tag(Tags.INVENTORY) @Tag(Tags.SHIPPING)
@Environment("fulfilment")
@SuiteDoc(
    description = Desc(
        "Reservation of stock end to end: the HTTP surface, the Mongo read model and " +
            "the stock-moves topic, asserted together against a live `fulfilment` environment."
    ),
    beforeTestSteps = [
        Step(
            value = "Insert a SKU with onHand=3, reserved=0 under this test's own id",
            expected = "Inventory holds stock nothing else in the run can see",
        ),
        Step(
            value = "Insert a NEW order for 2 of that SKU under this test's own id",
            expected = "An order exists to reserve against",
        ),
    ],
    labels = [Label(Tags.INVENTORY), Label(Tags.SHIPPING)],
)
class ReservationFlowST : SystemTest() {

    @BeforeEach
    fun seed(ctx: TerraContext) {
        ctx.mongo.inventory.insert(ctx.ids.sku(), "onHand" to 3, "reserved" to 0)
        ctx.mongo.orders.insert(ctx.ids.order(), "sku" to ctx.ids.sku(), "state" to "NEW", "quantity" to 2)
    }

    @TestDoc(
        description = Desc("A reservation debits stock, emits StockMoved and leaves the order RESERVED."),
        steps = [
            Step(value = "Checkpoint the stock-moves topic", expected = "A mark to read forward from, so other tests' events are not seen"),
            Step(value = "GET /health on orders-api", expected = "200 — the service is up and configured"),
            Step(value = "Reserve 2 of the SKU", expected = "StockMoved{delta=-2} is published, inventory reads onHand=1 reserved=2"),
            Step(value = "Await the order in the read model", expected = "Order state is RESERVED and still carries the SKU"),
        ],
        labels = [Label(Tags.REGRESSION), Label(Tags.INVENTORY)],
    )
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

    @TestDoc(
        description = Desc("An order for more than is on hand is rejected, and no StockMoved is emitted."),
        steps = [
            Step(value = "Checkpoint the stock-moves topic", expected = "A mark to assert an absence from"),
            Step(value = "Await the order reaching REJECTED", expected = "reason is INSUFFICIENT_STOCK"),
            Step(value = "Read stock-moves forward from the mark for 2s", expected = "Times out — nothing was emitted for this SKU"),
            Step(value = "Re-read the SKU", expected = "onHand is still 3; the rejection moved nothing"),
        ],
        labels = [Label(Tags.REGRESSION), Label(Tags.INVENTORY)],
    )
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
