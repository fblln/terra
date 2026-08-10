package tests.fulfilment

import terra.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import tests.Tags
import org.assertj.core.api.Assertions.assertThat

/**
 * Configuring a shared simulator per test, with no header propagation anywhere.
 *
 * The test drives service A, service A calls the mocked service B, and A does not
 * forward `X-Test-Id` — the normal situation. So the scoping lives in the data: every
 * test owns identities nothing else uses (`ctx.ids.user()`, `order()`, `sku()`), and
 * those travel inside the payload, which is the one thing that survives the hop.
 *
 * The rule is then self-scoping: it can only fire for a request carrying a value only
 * this test ever generated. No propagation, no reset, still concurrent.
 *
 * The trap this replaces is the shared fixture name. A rule about "frank" on a shared
 * simulator is a rule that fires for somebody else's test.
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

    @SharedEnvTest
    fun `asking for unscoped inspection is refused rather than quietly wrong`(ctx: TerraContext) {
        val failure = runCatching { sim.ordersReceived() }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(failure.toString()).contains("nothing to scope by")
    }
}
