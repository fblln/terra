package tests.fulfilment

import terra.*
import org.junit.jupiter.api.Tag
import tests.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import java.time.Duration

data class StockMoved(val sku: String = "", val delta: Int = 0, val seq: Int = 0)

/**
 * Publish and subscribe across two topics, with a MongoDB fixture behind it — the
 * combination most system tests actually are.
 */
// Two domains: stock moves are inventory, the shipments topic is shipping. A test
// belongs to as many groups as it genuinely belongs to.
@Tag(Tags.REGRESSION) @Tag(Tags.INVENTORY) @Tag(Tags.SHIPPING)
@Environment("fulfilment")
class KafkaPubSubST : SystemTest() {

    @BeforeEach
    fun seed(ctx: TerraContext) {
        ctx.mongo.inventory.insert(ctx.ids.sku(), "onHand" to 10)
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
    fun `publishing to one topic leaves the other alone`(ctx: TerraContext) {
        val stock = ctx.kafka.checkpoint(STOCK)
        val shipments = ctx.kafka.checkpoint(SHIPMENTS)

        ctx.kafka.publish(STOCK, StockMoved(sku = ctx.ids.sku(), delta = -3, seq = 1))

        val moved = ctx.kafka.awaitAfter<StockMoved>(stock, STOCK) { it.sku == ctx.ids.sku() }
        assertThat(moved.delta).isEqualTo(-3)

        // Nothing of ours went to shipments, and the checkpoint proves it in two
        // seconds rather than by waiting out a thirty-second default.
        val leaked = runCatching {
            ctx.kafka.awaitAfter<ShipmentReady>(shipments, SHIPMENTS, Duration.ofSeconds(2)) {
                it.order == ctx.ids.order()
            }
        }
        assertThat(leaked.isFailure).isTrue()
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
