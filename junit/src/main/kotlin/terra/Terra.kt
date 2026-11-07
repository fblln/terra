package terra

/**
 * The handful of things terra cannot discover: ports come from Docker, endpoints from
 * the descriptor, topics from the environment file, but these appear nowhere it can
 * read. A project states them once — see `TerraSetup` in the demo.
 *
 * Settable rather than injected: they are constants of the project, not of a test, and
 * threading them through every probe constructor would put topology naming into the
 * signature of things that should not care.
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
     * Where the simulator posts the events a stub asked it to publish. An address
     * *inside* the container network, because the simulator is what calls it — the
     * host ports in the descriptor are for the test, and the simulator cannot use them.
     */
    var kafkaRestUrl: String = "http://kafka-rest:8082"

    /**
     * Log lines that are noise in *your* stack. Every entry is a decision somebody has
     * to defend in review, which is why the list is short and in one place rather than
     * `@Suppress` sprinkled over tests.
     */
    val allowedLogPatterns: MutableList<Regex> = mutableListOf()
}
