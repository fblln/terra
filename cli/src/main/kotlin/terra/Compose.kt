package terra.cli

import terra.HostPort
import java.nio.file.Path

/**
 * The only place in the system that runs `docker`. Not Gradle, not the test JVM.
 *
 * Deliberately a thin process wrapper rather than a client library: the harness
 * must orchestrate the same topology a developer can start by hand, and the way
 * to guarantee that is to run the same commands.
 */
object Compose {

    /**
     * `withProject = false` exists for exactly one caller: resolving the image list,
     * which happens *while* the fingerprint is being computed and therefore cannot ask
     * for the project name — that would recurse. `config --images` does not need one.
     */
    private fun base(spec: EnvironmentSpec, withProject: Boolean = true): List<String> =
        listOf("docker", "compose") +
            // Without this, Compose resolves relative bind-mount paths against the
            // directory of the *first* -f file rather than the working directory, so
            // `./compose/x` silently becomes `compose/compose/x` — and Docker creates
            // the missing path as an empty directory instead of failing.
            listOf("--project-directory", spec.rootDir.toAbsolutePath().normalize().toString()) +
            (if (withProject) listOf("-p", spec.project) else emptyList()) +
            spec.composeFiles.flatMap { listOf("-f", it.toString()) }

    private fun builder(
        spec: EnvironmentSpec,
        cmd: List<String>,
        vars: Map<String, String> = spec.allVars,
        withProject: Boolean = true,
    ) = ProcessBuilder(base(spec, withProject) + cmd).apply {
        environment().putAll(vars)
        directory(spec.rootDir.toFile())
    }

    fun run(spec: EnvironmentSpec, vararg cmd: String): Int =
        builder(spec, cmd.toList()).inheritIO().start().waitFor()

    fun capture(spec: EnvironmentSpec, vararg cmd: String): String =
        builder(spec, cmd.toList())
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().readText()

    /**
     * Detached follower. It outlives this JVM on purpose — `up` returns but the
     * stream must keep flowing, or an IDE run half an hour later has no evidence.
     * The pid goes in the descriptor so `down` can reap it.
     */
    fun follow(spec: EnvironmentSpec, into: Path, vararg cmd: String): ProcessHandle =
        builder(spec, cmd.toList())
            .redirectOutput(ProcessBuilder.Redirect.appendTo(into.toFile()))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .toHandle()

    /**
     * `docker compose port` rather than parsing `ps --format json`, whose shape has
     * changed between Compose versions. Output looks like `0.0.0.0:32768`.
     */
    fun endpoint(spec: EnvironmentSpec, service: String, containerPort: Int): HostPort {
        val raw = capture(spec, "port", service, containerPort.toString()).trim().lines().firstOrNull()
        val port = raw?.substringAfterLast(':')?.trim()?.toIntOrNull()
            ?: error(
                "no published port for $service:$containerPort in ${spec.project}.\n" +
                    "docker compose port said: ${raw ?: "<nothing>"}\n" +
                    "Does the service publish the port? Do not pin host ports — let Docker assign."
            )
        return HostPort("localhost", port)
    }

    /**
     * Tear down by project name alone — no compose files needed, and there may not be
     * any that still describe it. Used to reap the project a fingerprint change left
     * behind, which otherwise sits there eating a laptop's memory until someone
     * notices.
     */
    fun downProject(project: String): Int =
        ProcessBuilder("docker", "compose", "-p", project, "down", "--volumes")
            .inheritIO().start().waitFor()

    /** Every systest project Docker currently knows about. */
    fun projects(): List<String> =
        ProcessBuilder("docker", "compose", "ls", "--all", "--format", "json")
            .redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText()
            .let { Regex("\"Name\"\\s*:\\s*\"(terra-[^\"]+)\"").findAll(it) }
            .map { it.groupValues[1] }
            .distinct().toList()

    fun anythingRunning(spec: EnvironmentSpec): Boolean =
        capture(spec, "ps", "--quiet", "--status", "running").isNotBlank()

    /**
     * Uses the declared vars only, never the derived ones — derived ports are computed
     * *from* the fingerprint, and the fingerprint is computed from these images. Passing
     * `allVars` here would be a cycle. Image references never mention a host port, so
     * nothing is lost.
     */
    fun images(spec: EnvironmentSpec): List<String> =
        builder(spec, listOf("config", "--images"), spec.vars, withProject = false)
            .redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText()
            .lines().map(String::trim).filter(String::isNotEmpty)
}
