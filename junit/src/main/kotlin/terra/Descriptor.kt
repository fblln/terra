package terra

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Path
import kotlin.io.path.*

/**
 * The only thing the test JVM knows about infrastructure.
 *
 * Written by `terra up`, deleted by `terra down`. Everything in it is a fact
 * the harness observed after the environment was healthy — notably the endpoints,
 * which are ephemeral host ports Docker chose, and which therefore cannot be
 * guessed, hard-coded, or shared between two environments.
 *
 * `startedAt` is a string rather than an Instant so the descriptor needs no
 * serializer configuration on either side.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class EnvironmentDescriptor(
    val name: String,
    val fingerprint: String,
    val project: String,
    val runId: String,
    val startedAt: String,
    val resultsDir: String,
    val clusterLog: String,
    val health: String,
    val endpoints: Map<String, String> = emptyMap(),
    val capabilities: Set<String> = emptySet(),
    /** Checkpointed at the start of every test, so `shouldBePublished` needs no ceremony. */
    val topics: List<String> = emptyList(),
    val collectorPids: List<Long> = emptyList(),
) {
    fun endpoint(service: String): HostPort = endpoints[service]
        ?.let { HostPort(it.substringBeforeLast(':'), it.substringAfterLast(':').toInt()) }
        ?: error("environment '$name' exposes no service '$service'; it has ${endpoints.keys}")

    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val results: Path get() = Path(resultsDir)
}

data class HostPort(val host: String, val port: Int) {
    override fun toString() = "$host:$port"
}

object Descriptors {

    private val json = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    /**
     * The test JVM's working directory is the Gradle module, not the project root, so
     * a relative descriptor path would only resolve by luck. Walk up looking for the
     * `environments/` directory — the same trick git uses to find `.git` — so this
     * works from a module, from the root, and from an IDE run configuration alike.
     */
    private val root: Path by lazy {
        System.getenv("TERRA_DESCRIPTOR_DIR")?.let { return@lazy Path(it) }

        generateSequence(Path("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { (it / "environments").isDirectory() }
            ?.let { it / "build" / "terra" / "environments" }
            ?: Path("build/terra/environments")
    }

    fun path(name: String): Path = root.resolve("$name.json")

    /**
     * `TERRA_ENV_FILE` wins when set — that is how `terra run` pins the test
     * JVM to the environment it just started, even if another descriptor exists.
     */
    fun read(name: String): EnvironmentDescriptor? {
        val file = System.getenv("TERRA_ENV_FILE")?.let(::Path) ?: path(name)
        if (!file.exists()) return null
        return runCatching { json.readValue<EnvironmentDescriptor>(file.readText()) }.getOrNull()
    }

    fun write(descriptor: EnvironmentDescriptor): Path =
        path(descriptor.name).apply {
            parent.createDirectories()
            writeText(json.writeValueAsString(descriptor))
        }

    fun delete(name: String) {
        path(name).deleteIfExists()
    }
}
