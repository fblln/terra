package tests.fulfilment

import terra.*
import org.junit.jupiter.api.Tag
import tests.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import java.time.Duration

data class StockMoved(val sku: String = "", val delta: Int = 0, val seq: Int = 0)
data class ShipmentReady(val order: String = "", val state: String = "")

/**
 * Both Kafka paths. `shouldBePublished` needs no checkpoint — the harness marks every
 * topic under `topics:` before the test can act; `checkpoint`/`awaitAfter` remain for
 * undeclared topics and for reading a batch in order. Either way the *window* is
 * handled and the *authorship* is yours.
 */
@Tag(Tags.REGRESSION) @Tag(Tags.INVENTORY) @Tag(Tags.SHIPPING)
@Environment("fulfilment")
class KafkaST : SystemTest() {

    @BeforeEach
    fun seed(ctx: TerraContext) {
        ctx.mongo.inventory.insert(ctx.ids.sku(), "onHand" to 10)
    }

    @SharedEnvTest
    fun `an event published during the test is found, with no checkpoint by hand`(ctx: TerraContext) {
        ctx.kafka.publish(STOCK, StockMoved(sku = ctx.ids.sku(), delta = -1, seq = 1))

        val moved = ctx.kafka.shouldBePublished<StockMoved>(STOCK) { it.sku == ctx.ids.sku() }

        assertThat(moved.delta).isEqualTo(-1)
    }

    @SharedEnvTest
    fun `an absence is asserted with a short timeout, not a long one`(ctx: TerraContext) {
        ctx.kafka.publish(STOCK, StockMoved(sku = ctx.ids.sku(), delta = -1, seq = 1))

        // Nothing of ours reached shipments, proven in two seconds rather than thirty.
        ctx.kafka.shouldNotBePublished<ShipmentReady>(SHIPMENTS) { it.order == ctx.ids.order() }
    }

    @SharedEnvTest
    fun `a topic that was never declared says so, instead of failing obscurely`(ctx: TerraContext) {
        val failure = runCatching {
            ctx.kafka.shouldBePublished<StockMoved>("not-declared") { true }
        }.exceptionOrNull()

        assertThat(failure.toString()).contains("no automatic checkpoint")
        assertThat(failure.toString()).contains("topics:")
    }

    @SharedEnvTest
    fun `a batch comes back in the order it was published`(ctx: TerraContext) {
        val mark = ctx.kafka.checkpoint(STOCK)

        (1..5).forEach { seq ->
            ctx.kafka.publish(STOCK, StockMoved(sku = ctx.ids.sku(), delta = -1, seq = seq))
        }

        // The predicate is not optional: other tests publish into the same window,
        // and an unfiltered read interleaves their events with ours.
        val moved = ctx.kafka.readAfter<StockMoved>(mark, STOCK, count = 5) { it.sku == ctx.ids.sku() }

        assertThat(moved.map { it.seq }).containsExactly(1, 2, 3, 4, 5)
        assertThat(moved).allMatch { it.sku == ctx.ids.sku() }
    }

    @SharedEnvTest
    fun `a checkpoint hides everything that happened before it`(ctx: TerraContext) {
        ctx.kafka.publish(SHIPMENTS, ShipmentReady(order = ctx.ids.order(2), state = "BEFORE"))

        val mark = ctx.kafka.checkpoint(SHIPMENTS)      // taken after the event above
        ctx.kafka.publish(SHIPMENTS, ShipmentReady(order = ctx.ids.order(3), state = "AFTER"))

        val event = ctx.kafka.awaitAfter<ShipmentReady>(mark, SHIPMENTS) { it.state == "AFTER" }
        assertThat(event.order).isEqualTo(ctx.ids.order(3))
    }

    @SharedEnvTest
    fun `a test reads only its own events out of a shared topic`(ctx: TerraContext) {
        val mark = ctx.kafka.checkpoint(SHIPMENTS)

        ctx.kafka.publish(SHIPMENTS, ShipmentReady(order = "ORD-someone-else", state = "READY"))
        ctx.kafka.publish(SHIPMENTS, ShipmentReady(order = ctx.ids.order(), state = "READY"))

        // Neither this test nor its neighbours truncate the topic: each reads forward
        // from its own checkpoint and filters by an order id only it knows.
        val event = ctx.kafka.awaitAfter<ShipmentReady>(mark, SHIPMENTS) { it.order == ctx.ids.order() }
        assertThat(event.state).isEqualTo("READY")
    }

    @SharedEnvTest
    fun `an event drives a document update, and both are asserted`(ctx: TerraContext) {
        val mark = ctx.kafka.checkpoint(STOCK)

        ctx.kafka.publish(STOCK, StockMoved(sku = ctx.ids.sku(), delta = -4, seq = 1))
        val moved = ctx.kafka.awaitAfter<StockMoved>(mark, STOCK) { it.sku == ctx.ids.sku() }

        // Stands in for the inventory service reacting to the event. In a real suite
        // this write is the service's job and the test only waits for it.
        ctx.mongo.inventory.set(ctx.ids.sku(), "onHand" to 10 + moved.delta)

        val stock = ctx.mongo.inventory.await(ctx.ids.sku()) { it.getInteger("onHand") == 6 }
        assertThat(stock.getInteger("onHand")).isEqualTo(6)
    }

    @SharedEnvTest
    fun `a failed match reports the records it did see`(ctx: TerraContext) {
        val mark = ctx.kafka.checkpoint(STOCK)
        ctx.kafka.publish(STOCK, StockMoved(sku = ctx.ids.sku(), delta = -1, seq = 99))

        val failure = runCatching {
            ctx.kafka.awaitAfter<StockMoved>(mark, STOCK, Duration.ofSeconds(3)) {
                it.sku == "SKU-does-not-exist"
            }
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(failure.toString()).contains("saw")
        assertThat(failure.toString()).contains(ctx.ids.sku())
    }

    private companion object {
        const val STOCK = "stock-moves"
        const val SHIPMENTS = "shipments"
    }
}
