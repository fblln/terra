package tests.fulfilment

import terra.*
import org.junit.jupiter.api.Tag
import tests.Tags
import org.assertj.core.api.Assertions.assertThat

/**
 * The Kafka assertion without the ceremony.
 *
 * No `checkpoint(...)` anywhere: the harness marks every topic listed under `topics:`
 * at the start of the test, before it can act. Taking the mark by hand is the easiest
 * thing here to get subtly wrong — forget it, or take it after the action, and the
 * assertion silently reads the wrong window.
 *
 * The window is handled for you. The *authorship* is still yours: match on an id only
 * this test generated.
 */
@Tag(Tags.REGRESSION) @Tag(Tags.SHIPPING)
@Environment("fulfilment")
class PublishedST : SystemTest() {

    @SharedEnvTest
    fun `an event published during the test is found`(ctx: TerraContext) {
        ctx.kafka.publish("stock-moves", StockMoved(sku = ctx.ids.sku(), delta = -1, seq = 1))

        val moved = ctx.kafka.shouldBePublished<StockMoved>("stock-moves") { it.sku == ctx.ids.sku() }

        assertThat(moved.delta).isEqualTo(-1)
    }

    @SharedEnvTest
    fun `an absence is asserted with a short timeout, not a long one`(ctx: TerraContext) {
        ctx.kafka.publish("stock-moves", StockMoved(sku = ctx.ids.sku(), delta = -1, seq = 1))

        // Nothing of ours reached shipments, proven in two seconds rather than thirty.
        ctx.kafka.shouldNotBePublished<ShipmentReady>("shipments") { it.order == ctx.ids.order() }
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
    fun `migrations ran before the first test`(ctx: TerraContext) {
        // Seeded by SeedReferenceData, once per JVM, idempotently.
        val carriers = ctx.mongo.collection("carriers")

        assertThat(carriers.get("dhl")?.getInteger("transitDays")).isEqualTo(2)
        assertThat(carriers.get("royal-mail")?.getInteger("transitDays")).isEqualTo(5)
    }
}
