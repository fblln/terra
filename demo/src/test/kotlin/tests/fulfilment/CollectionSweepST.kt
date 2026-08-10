package tests.fulfilment

import terra.*
import org.junit.jupiter.api.Tag
import tests.*
import org.assertj.core.api.Assertions.assertThat

/**
 * What an @ExclusiveEnvTest is actually for.
 *
 * This test asserts a *global* property of a collection, and it can only do that if
 * nothing else is writing to it. Under `@SharedEnvTest` it would be flaky forever
 * and everyone would eventually learn to re-run it; under `@ExclusiveEnvTest` JUnit
 * guarantees no shared test overlaps it.
 *
 * The rule of thumb: if a test drops, truncates, counts globally, restarts something
 * or flips shared configuration, it is exclusive. Everything else is shared.
 */
@Tag(Tags.REGRESSION) @Tag(Tags.INVENTORY)
@Environment("fulfilment")
class CollectionSweepST : SystemTest() {

    @ExclusiveEnvTest
    fun `a sweep sees exactly what it put there`(ctx: TerraContext) {
        val scratch = ctx.mongo.collection("sweep")
        scratch.drop()                                   // safe only because we run alone

        repeat(5) { n -> scratch.insert(ctx.ids.order(n + 1), "state" to "PENDING") }

        assertThat(scratch.total()).isEqualTo(5)
        assertThat(scratch.mine()).hasSize(5)
        assertThat(scratch.mine()).allMatch { it.getString("state") == "PENDING" }
    }

    @ExclusiveEnvTest
    fun `every document written by the harness carries the id of its author`(ctx: TerraContext) {
        val scratch = ctx.mongo.collection("sweep")
        scratch.drop()
        scratch.insert(ctx.ids.order(), "state" to "PENDING")

        // A global invariant, and the reason the probe stamps testId on every insert:
        // without it a stray document in a shared collection is untraceable.
        assertThat(scratch.mine()).hasSize(scratch.total().toInt())
    }
}
