package terra

import org.junit.platform.engine.FilterResult
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.launcher.PostDiscoveryFilter
import org.junit.platform.launcher.TagFilter

/**
 * Groups are `@Tag`, and a test can carry as many as it likes — that is the whole
 * feature. Jupiter already propagates class-level tags onto method descriptors, so
 * tagging a suite tags its tests.
 *
 * Both filters delegate to JUnit's own [TagFilter], which means the *expression*
 * language comes for free: `inventory`, `inventory | shipping`,
 * `regression & !slow`, `any()`, `none()`. Writing our own matcher would have been
 * a worse matcher.
 */
class IncludeTagFilter : PostDiscoveryFilter {
    private val delegate = System.getenv("TERRA_TAGS")
        ?.takeIf { it.isNotBlank() }
        ?.let { TagFilter.includeTags(it) }

    override fun apply(descriptor: TestDescriptor): FilterResult =
        delegate?.apply(descriptor) ?: FilterResult.included(null)
}

class ExcludeTagFilter : PostDiscoveryFilter {
    private val delegate = System.getenv("TERRA_EXCLUDE_TAGS")
        ?.takeIf { it.isNotBlank() }
        ?.let { TagFilter.excludeTags(it) }

    override fun apply(descriptor: TestDescriptor): FilterResult =
        delegate?.apply(descriptor) ?: FilterResult.included(null)
}
