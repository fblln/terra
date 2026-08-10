package terra

import java.time.Instant

/**
 * Everything a test is allowed to touch, injected as a parameter.
 *
 * Deliberately not a singleton: global mutable harness state is what forecloses
 * parallel execution three hundred tests from now, and by then it is expensive
 * to undo.
 */
class HarnessContext(
    private val attached: Attached,
    val ids: TestIds,
    val startedAt: Instant,
    /** True for @ExclusiveEnvTest. Gates anything that is inherently global. */
    val exclusive: Boolean = false,
) : AutoCloseable {

    val descriptor: EnvironmentDescriptor get() = attached.descriptor
    val logs: LogReader get() = attached.logs

    fun endpoint(service: String) = descriptor.endpoint(service)

    fun requires(capability: String) {
        check(capability in descriptor.capabilities) {
            "environment '${descriptor.name}' lacks capability '$capability' " +
                "(has ${descriptor.capabilities}); this test cannot run here"
        }
    }

    /**
     * Opened on first use and tracked so [close] can shut down only what the test
     * actually touched — a test that never reads Kafka should not pay for a consumer.
     */
    private val opened = mutableListOf<AutoCloseable>()

    val kafka: KafkaProbe by lazy {
        KafkaProbe(endpoint("kafka"), ids.group(), descriptor.topics).also { opened += it }
    }

    val mongo: MongoProbe by lazy { MongoProbe(endpoint("mongodb"), ids).also { opened += it } }

    /** HTTP against any service the environment publishes. Carries X-Test-Id. */
    fun http(service: String) = HttpProbe(endpoint(service), ids.header)

    /**
     * Network faults. Inherently global — a severed connection is severed for every
     * test — so this refuses outside an @ExclusiveEnvTest rather than quietly breaking
     * whatever else is running.
     */
    val chaos: ChaosProbe by lazy { ChaosProbe(endpoint("toxiproxy"), exclusive) }

    /**
     * Rules registered here belong to this test alone and are removed when it ends.
     * Configuring the simulator therefore costs an HTTP call, not an environment.
     */
    val simulator: SimulatorProbe by lazy {
        SimulatorProbe(endpoint("store-simulator"), ids.header)
            .also { probe -> opened += AutoCloseable { probe.reset() } }
    }

    // Fill this in against your own client. It takes an endpoint and the test's id
    // header; nothing else about the environment should reach a test.
    // val api: ApiClient by lazy { ApiClient(endpoint("gateway"), ids.header).also { opened += it } }

    override fun close() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }
}

/**
 * Every identity a test touches is derived from (run, test), so nothing has to be
 * truncated or reset between tests. Two tests can write the same collection and the
 * same topic simultaneously and never see each other.
 */
class TestIds(private val runId: String, private val testId: String) {
    val header = "$runId-$testId"
    fun order(n: Int = 1) = "ORD-$runId-$testId-%03d".format(n)

    /**
     * A user nobody else is using. The point of this one is scoping a *downstream*
     * mock: if the value a service sends onward is unique to this test, the rule can
     * match on it and no header has to survive the hop.
     */
    fun user(n: Int = 1) = "usr-$runId-$testId-%03d".format(n)
    fun sku(n: Int = 1) = "SKU-$runId-$testId-%03d".format(n)
    fun group() = "terra-$runId-$testId"
    override fun toString() = header
}
