package terra.cli

import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.math.absoluteValue

/**
 * A logical environment, resolved to files, variables and a fingerprint.
 *
 * The fingerprint is the load-bearing idea: it is a hash of everything that can
 * change what actually runs, and it names the Compose project. Rebuild a service
 * and the fingerprint moves, so `up` starts a *different* project rather than
 * reusing the old one. Testing against stale images stops being something you have
 * to remember to avoid and becomes something that cannot happen.
 */
data class EnvironmentSpec(
    val name: String,
    val rootDir: Path,
    val composeFiles: List<Path>,
    val services: Map<String, Int>,
    val hostPorts: Map<String, Int>,
    val topics: List<String>,
    val healthPath: String,
    val capabilities: Set<String>,
    val vars: Map<String, String>,
) {
    /**
     * Some services cannot live behind an ephemeral port, because they hand their own
     * address to clients — Kafka's `advertised.listeners` is the canonical case. For
     * those we still avoid a hard-coded port: derive one from the fingerprint, so it
     * is stable for a given environment and different for every other environment.
     *
     * Not part of the fingerprint (it is derived *from* it), so there is no cycle.
     *
     * ponytail: a flat 20000-range hash can collide across environments. Two colliding
     * environments fail loudly at `up` with "port already allocated"; move to a real
     * allocator only if that actually happens.
     */
    val derivedVars: Map<String, String> by lazy {
        hostPorts.keys.sorted().mapIndexed { i, service ->
            "${service.uppercase()}_HOST_PORT" to
                (20000 + (fingerprint.hashCode().absoluteValue % 20000) + i).toString()
        }.toMap()
    }

    /** What Compose actually sees. */
    val allVars: Map<String, String> get() = vars + derivedVars

    fun derivedPort(service: String): Int =
        derivedVars["${service.uppercase()}_HOST_PORT"]?.toInt()
            ?: error("no derived host port for '$service'")

    val fingerprint: String by lazy {
        val material = buildString {
            composeFiles.forEach { append(rootDir.resolve(it).readText()) }
            append(vars.toSortedMap().toString())
            append(imageIds())
        }
        sha256(material).take(8)
    }

    val project: String get() = "terra-$fingerprint"

    /**
     * Image *ids*, not tags. `service:local` names a different image after every
     * rebuild, and that difference has to reach the fingerprint or the whole scheme
     * silently degrades into "attach to whatever is lying around".
     */
    private fun imageIds(): String = Compose.images(this).sorted().joinToString("\n") { image ->
        // ponytail: an image we have not pulled yet hashes as its own name. `up` pulls
        // it, and the next `up` computes the real id — so a first run is one project
        // ahead of steady state, which is harmless.
        runCatching {
            ProcessBuilder("docker", "image", "inspect", "--format", "{{.Id}}", image)
                .redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText().trim()
                .ifBlank { image }
        }.getOrDefault(image)
    }

    companion object {
        fun load(name: String, rootDir: Path = Path.of(".")): EnvironmentSpec {
            val file = rootDir.resolve("environments/$name.yml")
            if (!file.exists()) {
                val known = rootDir.resolve("environments").toFile()
                    .listFiles { f -> f.name.endsWith(".yml") }
                    ?.joinToString(", ") { it.nameWithoutExtension } ?: "none"
                error("no environment '$name' — environments/ has: $known")
            }

            @Suppress("UNCHECKED_CAST")
            val yaml = Yaml().load<Map<String, Any?>>(file.readText())

            val compose = (yaml["compose"] as? List<String>)
                ?: error("environments/$name.yml has no 'compose' list")

            return EnvironmentSpec(
                name = name,
                rootDir = rootDir,
                composeFiles = compose.map(Path::of),
                services = (yaml["services"] as? Map<String, Int>).orEmpty(),
                hostPorts = (yaml["hostPorts"] as? Map<String, Int>).orEmpty(),
                topics = (yaml["topics"] as? List<String>).orEmpty(),
                healthPath = (yaml["health"] as? String) ?: error("environments/$name.yml has no 'health'"),
                capabilities = (yaml["capabilities"] as? List<String>).orEmpty().toSet(),
                // Precedence: environment variable beats file beats nothing.
                vars = (yaml["vars"] as? Map<String, String>).orEmpty() + envOverrides(),
            )
        }

        /** TERRA_VAR_SERVICE_VERSION=1.2.3 → SERVICE_VERSION=1.2.3 */
        private fun envOverrides(): Map<String, String> = System.getenv()
            .filterKeys { it.startsWith("TERRA_VAR_") }
            .mapKeys { it.key.removePrefix("TERRA_VAR_") }

        private fun sha256(s: String) = MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
