package tests.returns

import terra.*
import org.junit.jupiter.api.Tag
import tests.Tags
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.net.URI

/**
 * The second environment. These tests cannot run against `fulfilment` — it has no
 * returns-api — and they never say so; they name a topology and the harness makes
 * sure they get it.
 */
@Tag(Tags.REGRESSION) @Tag(Tags.RETURNS)
@Environment("returns")
class ReturnsST : SystemTest() {

    @SharedEnvTest
    fun `returns-api exists only in this topology`(ctx: HarnessContext) {
        ctx.requires("returns")

        val body = eventuallyBody(ctx.endpoint("returns-api"))
        assertThat(body).contains("returns-api")
    }

    @SharedEnvTest
    fun `the shared half of the topology is here too`(ctx: HarnessContext) {
        // base.yml is written once and used by both environments, so Kafka and Mongo
        // are present with identical configuration — as separate containers, because
        // this is a separate project.
        ctx.requires("kafka")
        ctx.requires("mongo")
        assertThat(ctx.descriptor.endpoints.keys)
            .contains("kafka", "mongodb", "gateway", "returns-api")
    }

    @SharedEnvTest
    fun `asking for a capability this topology lacks is refused, not ignored`(ctx: HarnessContext) {
        assertThatThrownBy { ctx.requires("simulator") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("lacks capability 'simulator'")
    }

    private fun eventuallyBody(endpoint: HostPort): String {
        lateinit var body: String
        eventually { body = URI("http://$endpoint/").toURL().readText() }
        return body
    }
}
