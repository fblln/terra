package terra

import org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request
import org.junit.platform.launcher.core.LauncherFactory
import java.nio.file.Path

/**
 * Discovery without execution: which tests exist, in which group, needing which
 * topology.
 *
 * This exists so `terra run --tag inventory` does not start five environments to
 * run tests that live in one. Starting a topology is the most expensive thing the
 * harness does, and once tests can be selected by tag, starting one with nothing in
 * it stops being a rare edge and becomes the common case.
 *
 * It needs no environment, because discovery is classpath scanning — the harness
 * extension only runs at execution time.
 *
 * The same ServiceLoader-registered filters apply here as during a real run, so the
 * plan cannot disagree with what will actually execute. TERRA_ENV must be unset
 * for a plan, or you get one environment's worth of it.
 *
 * Emits TSV: environment, class, method, tags.
 */
object Discover {

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "usage: Discover <test-classes-dir>[:<dir>...]" }

        val roots = args.flatMap { it.split(java.io.File.pathSeparator) }
            .filter { it.isNotBlank() }
            .map { Path.of(it) }
            .filter { java.nio.file.Files.exists(it) }

        val plan = LauncherFactory.create()
            .discover(request().selectors(selectClasspathRoots(roots.toSet())).build())

        plan.roots
            .flatMap { plan.getDescendants(it) }
            .filter { it.isTest }
            .sortedBy { it.uniqueId }
            .forEach { test ->
                println(
                    listOf(
                        environmentOf(test) ?: "-",
                        classOf(test) ?: "-",
                        test.displayName,
                        test.tags.map { it.name }.sorted().joinToString(",").ifEmpty { "-" },
                    ).joinToString("\t")
                )
            }
    }

    private fun classOf(test: TestIdentifier): String? =
        (test.source.orElse(null) as? MethodSource)?.className

    private fun environmentOf(test: TestIdentifier): String? {
        val className = classOf(test) ?: return null
        val klass = runCatching { Class.forName(className) }.getOrNull() ?: return null
        return generateSequence(klass) { it.superclass }
            .mapNotNull { k -> k.annotations.filterIsInstance<Environment>().firstOrNull() }
            .firstOrNull()?.value
    }
}
