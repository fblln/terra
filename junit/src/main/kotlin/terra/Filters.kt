package terra

import org.junit.platform.engine.FilterResult
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.PostDiscoveryFilter
import kotlin.math.abs

/**
 * `terra run` starts one environment per invocation and tells the test JVM which
 * one, so the JVM runs only the classes that asked for it. This replaces sorting
 * test classes into environment groups: the grouping already happened, outside.
 *
 * With TERRA_ENV unset — an IDE run — nothing is filtered, so clicking a single
 * test runs exactly that test.
 */
class EnvironmentFilter : PostDiscoveryFilter {

    private val only: String? = System.getenv("TERRA_ENV")?.takeIf { it.isNotBlank() }

    override fun apply(descriptor: TestDescriptor): FilterResult {
        if (only == null) return FilterResult.included(null)
        val declared = descriptor.environmentName() ?: return FilterResult.included(null)
        return if (declared == only) FilterResult.included(null)
        else FilterResult.excluded("declares environment '$declared', running '$only'")
    }

    /**
     * Both source kinds must be handled, and that is not a detail: excluding a class
     * descriptor does **not** prune its methods. A method that filters as included
     * keeps its parent alive and the class runs anyway. Answering only for
     * ClassSource looks correct and silently does nothing.
     */
    private fun TestDescriptor.environmentName(): String? {
        val klass = when (val source = source.orElse(null)) {
            is ClassSource -> runCatching { source.javaClass }.getOrNull()
            is MethodSource -> runCatching { source.javaClass }.getOrNull()
            else -> null
        } ?: return null

        return generateSequence(klass) { it.superclass }
            .mapNotNull { k -> k.annotations.filterIsInstance<Environment>().firstOrNull() }
            .firstOrNull()?.value
    }
}

/**
 * TERRA_SHARD=2/8. Deterministic in the test's own identity, so the same test
 * always lands in the same shard for a given shard count and no list of test names
 * has to be maintained anywhere. Curated shard lists are wrong within a week.
 */
class ShardFilter : PostDiscoveryFilter {

    private val shard: Pair<Int, Int>? = System.getenv("TERRA_SHARD")
        ?.split("/")
        ?.takeIf { it.size == 2 }
        ?.let { (index, total) -> index.trim().toInt() to total.trim().toInt() }

    override fun apply(descriptor: TestDescriptor): FilterResult {
        val (index, total) = shard ?: return FilterResult.included(null)
        if (!descriptor.isTest) return FilterResult.included(null)
        val bucket = abs(descriptor.uniqueId.toString().hashCode()) % total
        return if (bucket == index - 1) FilterResult.included(null)
        else FilterResult.excluded("shard ${bucket + 1}/$total")
    }
}
