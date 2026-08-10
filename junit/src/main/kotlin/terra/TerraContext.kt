package terra

import java.time.Instant

/**
 * Everything a test is allowed to touch, injected as a parameter.
 *
 * Deliberately not a singleton: global mutable harness state is what forecloses
 * parallel execution three hundred tests from now, and by then it is expensive
 * to undo.
 */
class TerraContext(
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
        KafkaProbe(endpoint(Terra.kafkaService), ids.group(), descriptor.topics).also { opened += it }
    }

    val mongo: MongoProbe by lazy {
        MongoProbe(endpoint(Terra.mongoService), ids, Terra.database).also { opened += it }
    }

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
        SimulatorProbe(endpoint(Terra.simulatorService), ids.header)
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

    /** Travels as `X-Test-Id`, and stamps every document this test writes. */
    val header = "$runId-$testId"

    /** Terra's own: a consumer group nothing else in the run will use. */
    fun group() = "terra-$runId-$testId"

    /**
     * An identifier of your own kind that nothing else in the run can collide with.
     *
     * Terra does not know what your domain calls things, so it generates the unique
     * part and you name the kind. Declare the vocabulary once, next to your tags:
     *
     * ```kotlin
     * // system-tests/src/test/kotlin/tests/Ids.kt
     * fun TestIds.order(n: Int = 1) = id("ORD", n)
     * fun TestIds.sku(n: Int = 1) = id("SKU", n)
     * fun TestIds.customer(n: Int = 1) = id("CUS", n)
     * ```
     *
     * and then every test reads `ctx.ids.order()`, with the kind checked by the
     * compiler rather than spelled out as a string at each call site.
     *
     * [n] distinguishes several of the same kind within one test, and is explicit
     * rather than a counter so a rerun produces the same ids — which matters when you
     * are reading them back out of a log.
     */
    fun id(prefix: String, n: Int = 1): String {
        require(prefix.isNotBlank()) { "an id kind needs a prefix" }
        return "%s-%s-%s-%03d".format(prefix, runId, testId, n)
    }

    override fun toString() = header
}
