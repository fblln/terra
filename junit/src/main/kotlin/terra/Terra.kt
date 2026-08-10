package terra

/**
 * The handful of things terra cannot guess about your topology.
 *
 * Everything else it discovers: ports come from Docker, endpoints from the census,
 * topics from the environment file. These do not appear anywhere it can read, so a
 * project states them once — in a `@BeforeAll` on a base class, an `init` block, or a
 * `LauncherSessionListener` if you prefer it out of the test source entirely.
 *
 * ```kotlin
 * Terra.database = "fulfilment"
 * Terra.simulatorService = "carrier-mock"
 * ```
 *
 * Deliberately settable rather than injected: these are constants of the project, not
 * of a test, and threading them through every probe constructor would put topology
 * naming into the signature of things that should not care.
 */
object Terra {

    /** The Mongo database your services actually write to. */
    var database: String = "terra"

    /** Which service in the topology is the programmable mock. */
    var simulatorService: String = "simulator"

    /** Which service is Mongo, and which is Kafka, if you did not use these names. */
    var mongoService: String = "mongodb"
    var kafkaService: String = "kafka"

    /**
     * Log lines that are noise in *your* stack. Every entry is a decision somebody has
     * to defend in review, which is the point of keeping the list short and in one
     * place rather than sprinkling `@Suppress` over tests.
     */
    val allowedLogPatterns: MutableList<Regex> = mutableListOf()
}
