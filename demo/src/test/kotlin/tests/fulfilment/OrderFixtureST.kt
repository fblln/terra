package tests.fulfilment

import terra.*
import org.junit.jupiter.api.Tag
import tests.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import kotlin.concurrent.thread

/**
 * The normal shape of a system test: seed MongoDB in @BeforeEach, act, assert.
 *
 * Nothing here truncates a collection. Every document is keyed on ids derived from
 * (run, test), which is what lets these run concurrently with each other and with
 * everything else in the suite.
 */
@Tag(Tags.REGRESSION) @Tag(Tags.INVENTORY)
@Environment("fulfilment")
class OrderFixtureST : SystemTest() {

    @BeforeEach
    fun seed(ctx: TerraContext) {
        ctx.mongo.orders.insert(
            ctx.ids.order(),
            "sku" to ctx.ids.sku(),
            "state" to "PENDING",
            "quantity" to 1,
        )
    }

    @SharedEnvTest
    fun `the fixture is there when the test starts`(ctx: TerraContext) {
        val order = ctx.mongo.orders.require(ctx.ids.order())

        assertThat(order.getString("state")).isEqualTo("PENDING")
        assertThat(order.getString("sku")).isEqualTo(ctx.ids.sku())
        assertThat(order.getString("testId")).isEqualTo(ctx.ids.header)
    }

    @SharedEnvTest
    fun `a test sees its own documents in a collection full of other tests`(ctx: TerraContext) {
        ctx.mongo.orders.insert(ctx.ids.order(2), "state" to "PENDING")
        ctx.mongo.orders.insert(ctx.ids.order(3), "state" to "PENDING")

        // Three of mine: the fixture plus these two.
        assertThat(ctx.mongo.orders.mine()).hasSize(3)

        // And the collection is not empty of everybody else — which is the point.
        // A suite that needed an empty database could never run two tests at once.
        assertThat(ctx.mongo.orders.total()).isGreaterThanOrEqualTo(3)
    }

    @SharedEnvTest
    fun `await polls until the document reaches the state`(ctx: TerraContext) {
        // Stands in for the service doing the work. In a real suite this is an HTTP
        // call and the wait is for a consumer somewhere to catch up.
        thread {
            Thread.sleep(600)
            ctx.mongo.orders.set(ctx.ids.order(), "state" to "RESERVED")
        }

        val order = ctx.mongo.orders.await(ctx.ids.order()) { it.getString("state") == "RESERVED" }
        assertThat(order.getString("state")).isEqualTo("RESERVED")
    }

    @SharedEnvTest
    fun `a timeout says what the document actually looks like`(ctx: TerraContext) {
        val failure = runCatching {
            ctx.mongo.orders.await(
                ctx.ids.order(),
                timeout = java.time.Duration.ofSeconds(2),
            ) { it.getString("state") == "NEVER_HAPPENS" }
        }.exceptionOrNull()

        // The value of the wrapper is on this path, not the happy one: an eventual
        // assertion that times out saying only "false" has thrown away everything it knew.
        assertThat(failure).isNotNull()
        assertThat(failure.toString()).contains("state=PENDING")
    }
}
