package tests.fulfilment

import terra.*
import org.junit.jupiter.api.Tag
import tests.Tags
import org.assertj.core.api.Assertions.assertThat
import java.net.HttpURLConnection
import java.net.URI

/**
 * Proves the whole loop without any application code: the harness started a
 * topology, wrote a descriptor, this JVM attached to it, and the container log was
 * being collected the entire time.
 */
@Tag(Tags.SMOKE) @Tag(Tags.SHIPPING)
@Environment("fulfilment")
class GatewayST : SystemTest() {

    @SharedEnvTest
    fun `gateway answers on the port the harness discovered`(ctx: TerraContext) {
        val gateway = ctx.endpoint("gateway")

        // Nothing in this test knows a port number. Docker chose it, systest looked
        // it up, and the descriptor carried it here.
        eventually {
            val connection = URI("http://$gateway/").toURL().openConnection() as HttpURLConnection
            assertThat(connection.responseCode).isEqualTo(200)
            connection.disconnect()
        }
    }

    @SharedEnvTest
    fun `every test gets identities nothing else can collide with`(ctx: TerraContext) {
        assertThat(ctx.ids.order()).startsWith("ORD-")
        assertThat(ctx.ids.group()).startsWith("terra-")
        // Derived from (run, test), so two tests can share a database and a topic
        // without either one truncating anything.
        assertThat(ctx.ids.order()).isNotEqualTo(TestIds("other", "test").order())
    }

    @SharedEnvTest
    fun `the environment advertises what it can do`(ctx: TerraContext) {
        ctx.requires("kafka")
        assertThat(ctx.descriptor.capabilities).contains("kafka", "mongo")

        // The returns stack is a different topology, and this one really is not it.
        assertThat(ctx.descriptor.capabilities).doesNotContain("returns")
        assertThat(ctx.descriptor.endpoints).doesNotContainKey("returns-api")
    }
}
