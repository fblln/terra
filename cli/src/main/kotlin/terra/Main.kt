package terra.cli

import terra.Descriptors
import terra.EnvironmentDescriptor
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.random.Random
import kotlin.system.exitProcess

private const val USAGE = """
terra — system test harness

  terra up      <environment>       start it and write the descriptor
  terra down    <environment>       stop it, remove volumes, delete the descriptor
  terra status  <environment>       docker compose ps
  terra logs    <environment>       follow the cluster log
  terra run     [environment...]    up -> gradle -> diagnostics -> down
  terra list                        what would run, by environment and group
  terra prune                       reap every terra project no descriptor points at

Options
  --tag <expr>           run only these groups; repeatable, OR-ed
  --exclude-tag <expr>   skip these groups; repeatable, OR-ed
  --project-dir <path>   where ./gradlew runs           (default: .)
  --task <name>          Gradle task to invoke          (default: systemTest)
  --plan-task <name>     Gradle discovery task          (default: systemTestPlan)
  --keep                 retain the environment if tests fail

Tags are JUnit tag expressions, so these all work:
  --tag smoke
  --tag "checkout | search"
  --tag regression --exclude-tag flaky

Exit codes
  0 success   1 tests failed   2 usage or environment error
"""

fun main(argv: Array<String>) {
    val opts = Options.parse(argv)
    val command = opts.positional.firstOrNull() ?: fail(USAGE)
    val names = opts.positional.drop(1)

    try {
        when (command) {
            "up" -> up(spec(opts, single(names, "up"))).also { println("ready: ${it.project}") }
            "down" -> down(spec(opts, single(names, "down")))
            "status" -> Compose.run(spec(opts, single(names, "status")), "ps")
            "logs" -> Compose.run(spec(opts, single(names, "logs")), "logs", "--follow")
            "run" -> exitProcess(run(opts, names))
            "list" -> list(opts)
            "prune" -> prune()
            "-h", "--help", "help" -> println(USAGE)
            else -> fail("unknown command '$command'\n$USAGE")
        }
    } catch (e: IllegalStateException) {
        System.err.println("terra: ${e.message}")
        exitProcess(2)
    }
}

// ---------------------------------------------------------------------- up/down

/**
 * Idempotent. `up -d --wait` reconciles a healthy project in about a second, so
 * there is no attach-versus-start branch — the only thing worth knowing beforehand
 * is whether anything was already running, and that only decides who may tear it
 * down later.
 */
private fun up(spec: EnvironmentSpec): EnvironmentDescriptor {
    val existing = Descriptors.read(spec.name)
    if (existing?.project == spec.project && Collectors.alive(existing.collectorPids)) {
        Compose.run(spec, "up", "-d", "--wait")     // reconcile; reuse the followers
        return existing
    }
    // The fingerprint moved — compose files or images changed. The old project is now
    // orphaned: nothing will ever attach to it again, so reap it rather than leaving
    // a full topology running for the rest of the day.
    existing?.let {
        Collectors.stop(it.collectorPids)
        if (it.project != spec.project) {
            println("superseding ${it.project} (fingerprint ${it.fingerprint} -> ${spec.fingerprint})")
            Compose.downProject(it.project)
        }
    }

    check(Compose.run(spec, "up", "-d", "--wait") == 0) {
        "environment '${spec.name}' (${spec.project}) did not become healthy.\n" +
            "Try: docker compose -p ${spec.project} ps"
    }

    val runId = System.getenv("TERRA_RUN_ID") ?: Integer.toHexString(Random.nextInt()).takeLast(4)
    // Absolute and normalised: the descriptor is read by a JVM whose working
    // directory is some Gradle module, not this one.
    val results = spec.rootDir.resolve("build/results/$runId")
        .toAbsolutePath().normalize().createDirectories()
    val collectors = Collectors.start(spec, results.resolve("cluster/${spec.name}"))

    // Services with a derived host port already know their address; the rest are looked
    // up, because Docker chose their port and only Docker knows it.
    // A key may be `alias@service`, so one container can publish two useful ports — a
    // proxy needs both its control API and the port it forwards. Tests look the
    // endpoint up by the alias; Docker is asked about the service.
    val endpoints = spec.services.entries.associate { (key, port) ->
        val alias = key.substringBefore('@')
        val service = key.substringAfter('@', key)
        alias to
            if (service in spec.hostPorts) "localhost:${spec.derivedPort(service)}"
            else Compose.endpoint(spec, service, port).toString()
    }

    val descriptor = EnvironmentDescriptor(
        name = spec.name,
        fingerprint = spec.fingerprint,
        project = spec.project,
        runId = runId,
        startedAt = Instant.now().toString(),
        resultsDir = results.toString(),
        clusterLog = collectors.clusterLog.toString(),
        health = health(spec, endpoints),
        endpoints = endpoints,
        capabilities = spec.capabilities,
        topics = spec.topics,
        collectorPids = collectors.pids,
    )
    Descriptors.write(descriptor)
    return descriptor
}

/** `health: gateway:8080/actuator/health` → `http://localhost:32771/actuator/health` */
private fun health(spec: EnvironmentSpec, endpoints: Map<String, String>): String {
    val service = spec.healthPath.substringBefore(':')
    val path = spec.healthPath.substringAfter('/', "")
    val endpoint = endpoints[service]
        ?: error("health refers to service '$service', which is not in 'services'")
    return "http://$endpoint/$path"
}

private fun down(spec: EnvironmentSpec) {
    // Descriptor first: nothing should be able to attach while teardown is in flight.
    val descriptor = Descriptors.read(spec.name)
    Descriptors.delete(spec.name)
    descriptor?.let { Collectors.stop(it.collectorPids) }
    Compose.run(spec, "down", "--volumes")
}

// -------------------------------------------------------------------------- run

/**
 * One environment per Gradle invocation. That is what removes the need to sort test
 * classes into environment groups inside the JVM: the grouping happened out here,
 * and TERRA_ENV tells the test JVM which subset to run.
 */
private fun run(opts: Options, requested: List<String>): Int {
    val candidates = requested.ifEmpty { discoverEnvironments(opts.projectDir) }
    check(candidates.isNotEmpty()) { "no environments found in ${opts.projectDir}/environments" }

    // Which environments actually contain selected tests. Starting a topology is the
    // most expensive thing here, and once tests are selected by tag, starting one
    // with nothing in it stops being an edge case and becomes the common one.
    val plan = plan(opts)
    val names = candidates.filter { plan.isEmpty() || plan.containsKey(it) }
    val skipped = candidates - names.toSet()
    if (skipped.isNotEmpty()) println("skipping ${skipped.joinToString(", ")} — no selected tests")
    if (names.isEmpty()) {
        println("no tests selected")
        return 0
    }

    var failures = 0
    for (name in names) {
        val spec = spec(opts, name)
        println("\n=== ${spec.name} (${spec.project}) ===")
        val descriptor = up(spec)

        val failed = gradle(opts, name, Descriptors.path(name)) != 0
        if (failed) failures++

        if (failed && opts.keep) retain(descriptor) else down(spec)
    }
    return if (failures > 0) 1 else 0
}

private fun gradle(opts: Options, environment: String, descriptor: Path): Int {
    val wrapper = if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "./gradlew"
    return ProcessBuilder(wrapper, opts.task)
        .directory(opts.projectDir.toFile())
        .apply {
            environment()["TERRA_ENV"] = environment
            environment()["TERRA_ENV_FILE"] = descriptor.toAbsolutePath().toString()
            opts.tags?.let { environment()["TERRA_TAGS"] = it }
            opts.excludeTags?.let { environment()["TERRA_EXCLUDE_TAGS"] = it }
        }
        .inheritIO()
        .start()
        .waitFor()
}

/**
 * Ask the test JVM what it would run, without running it and without an environment
 * — discovery is classpath scanning; the harness extension only wakes at execution
 * time. The same filters apply here as during a real run, so the plan cannot
 * disagree with reality.
 */
private fun plan(opts: Options): Map<String, List<PlannedTest>> {
    val wrapper = if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "./gradlew"
    val process = ProcessBuilder(wrapper, "--quiet", opts.planTask)
        .directory(opts.projectDir.toFile())
        .apply {
            environment().remove("TERRA_ENV")          // a plan covers every environment
            opts.tags?.let { environment()["TERRA_TAGS"] = it }
            opts.excludeTags?.let { environment()["TERRA_EXCLUDE_TAGS"] = it }
            redirectError(ProcessBuilder.Redirect.INHERIT)
        }
        .start()

    val rows = process.inputStream.bufferedReader().readLines()
    if (process.waitFor() != 0) {
        println("(no ${opts.planTask} task; running every requested environment)")
        return emptyMap()
    }
    return rows.mapNotNull { line ->
        line.split('\t').takeIf { it.size == 4 }?.let { (env, klass, method, tags) ->
            PlannedTest(env, klass, method, tags.split(',').filter { it != "-" })
        }
    }.groupBy { it.environment }
}

private data class PlannedTest(
    val environment: String,
    val className: String,
    val method: String,
    val tags: List<String>,
)

private fun list(opts: Options) {
    val plan = plan(opts)
    if (plan.isEmpty()) return println("no tests selected")
    plan.toSortedMap().forEach { (environment, tests) ->
        println("\n$environment  (${tests.size} test${if (tests.size == 1) "" else "s"})")
        tests.groupBy { it.className }.toSortedMap().forEach { (klass, methods) ->
            println("  ${klass.substringAfterLast('.')}  [${methods.first().tags.joinToString(" ")}]")
            methods.forEach { println("    ${it.method}") }
        }
    }
    val tags = plan.values.flatten().flatMap { it.tags }.distinct().sorted()
    println("\ntags: ${tags.joinToString(", ")}")
}

/** Reap every terra project no live descriptor points at. */
private fun prune() {
    val live = runCatching {
        Descriptors.path("x").parent.listDirectoryEntries("*.json")
            .mapNotNull { Descriptors.read(it.nameWithoutExtension)?.project }
    }.getOrDefault(emptyList()).toSet()

    val orphans = Compose.projects().filterNot { it in live }
    if (orphans.isEmpty()) return println("nothing to prune")
    orphans.forEach {
        println("pruning $it")
        Compose.downProject(it)
    }
}

private fun retain(descriptor: EnvironmentDescriptor) = println(
    """

    Environment retained because tests failed.
      project    ${descriptor.project}
      artifacts  ${descriptor.resultsDir}

      ./terra status ${descriptor.name}
      ./terra logs   ${descriptor.name}
      ./terra down   ${descriptor.name}
    """.trimIndent()
)

private fun discoverEnvironments(root: Path): List<String> =
    root.resolve("environments").listDirectoryEntries("*.yml").map { it.nameWithoutExtension }.sorted()

// ---------------------------------------------------------------------- plumbing

private fun spec(opts: Options, name: String) = EnvironmentSpec.load(name, opts.projectDir)

private fun single(names: List<String>, command: String) =
    names.singleOrNull() ?: fail("'$command' takes exactly one environment name")

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(2)
}

private data class Options(
    val positional: List<String>,
    val projectDir: Path,
    val task: String,
    val planTask: String,
    val keep: Boolean,
    val tags: String?,
    val excludeTags: String?,
) {
    companion object {
        fun parse(argv: Array<String>): Options {
            val positional = mutableListOf<String>()
            var projectDir = Path.of(".")
            var task = "systemTest"
            var planTask = "systemTestPlan"
            var keep = System.getenv("TERRA_KEEP") == "true"
            val tags = mutableListOf<String>()
            val excludeTags = mutableListOf<String>()

            var i = 0
            while (i < argv.size) {
                when (argv[i]) {
                    "--project-dir" -> projectDir = Path.of(argv[++i])
                    "--task" -> task = argv[++i]
                    "--plan-task" -> planTask = argv[++i]
                    "--keep" -> keep = true
                    "--tag" -> tags += argv[++i]
                    "--exclude-tag" -> excludeTags += argv[++i]
                    else -> positional += argv[i]
                }
                i++
            }
            // Repeated flags are OR-ed, which is what JUnit does with multiple
            // expressions and what people mean by "run inventory and shipping".
            return Options(
                positional, projectDir, task, planTask, keep,
                tags.takeIf { it.isNotEmpty() }?.joinToString(" | ") { "($it)" },
                excludeTags.takeIf { it.isNotEmpty() }?.joinToString(" | ") { "($it)" },
            )
        }
    }
}
