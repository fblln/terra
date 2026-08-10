package terra

import org.junit.jupiter.api.extension.*
import org.junit.jupiter.api.extension.ExtensionContext.Namespace
import java.net.URI
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.math.abs
import kotlin.random.Random

/**
 * Discover-or-fail. The test JVM never starts, stops, or inspects containers — it
 * reads the descriptor `terra up` wrote and connects to what is already there.
 *
 * That is what makes the IDE run gutter work: there is no 40-second stall to hide,
 * and stopping a run in the IDE cannot destroy anybody's environment.
 */
class TerraExtension :
    BeforeAllCallback, BeforeEachCallback, AfterEachCallback, ParameterResolver {

    override fun beforeAll(ctx: ExtensionContext) {
        attached(ctx, environmentOf(ctx))
    }

    override fun beforeEach(ctx: ExtensionContext) {
        val attached = attached(ctx, environmentOf(ctx))
        val ids = TestIds(EXEC_ID, shortId(ctx.uniqueId))
        val exclusive = ctx.requiredTestMethod.annotations.any { it is ExclusiveEnvTest }
        val harness = TerraContext(attached, ids, Instant.now(), exclusive)
        ctx.store().put(CONTEXT, harness)
        ctx.store().put(JOURNAL, Journal.begin("${ctx.requiredTestClass.simpleName} > ${ctx.displayName}"))

        // Mark every declared topic now, before the test can act. Taking the checkpoint
        // yourself is the easiest thing in this harness to get subtly wrong.
        if (attached.descriptor.topics.isNotEmpty()) harness.kafka.armCheckpoints()
    }

    override fun afterEach(ctx: ExtensionContext) {
        val harness = ctx.store().get(CONTEXT, TerraContext::class.java) ?: return
        harness.close()

        val journal = ctx.store().get(JOURNAL, TestJournal::class.java)
        Journal.end()

        if (ctx.executionException.isPresent) {
            // Already failing. Collect, never assert — a second failure would bury the first.
            val into = resultDir(ctx, harness)
            harness.logs.window(harness.startedAt, Instant.now(), into)

            // The assertion says what was untrue. This says what happened before it,
            // which is usually the diagnosis.
            journal?.takeUnless { it.isEmpty }?.let {
                it.writeTo(into)
                System.err.println("\n" + it.render())
            }
        } else {
            harness.logs.assertNoUnexpectedErrors(since = harness.startedAt)
        }
    }

    override fun supportsParameter(p: ParameterContext, ctx: ExtensionContext) =
        p.parameter.type == TerraContext::class.java

    override fun resolveParameter(p: ParameterContext, ctx: ExtensionContext): Any =
        ctx.store().get(CONTEXT, TerraContext::class.java)
            ?: error("no TerraContext; is the test annotated with @SharedEnvTest or @ExclusiveEnvTest?")

    // ---------------------------------------------------------------- internals

    private fun environmentOf(ctx: ExtensionContext): String =
        generateSequence(ctx.requiredTestClass) { it.superclass }
            .mapNotNull { klass -> klass.annotations.filterIsInstance<Environment>().firstOrNull() }
            .firstOrNull()?.value
            ?: error("${ctx.requiredTestClass.simpleName} has no @Environment")

    /**
     * Resolved once per JVM per environment, and cached in the root store.
     *
     * JUnit 6 declares `getOrComputeIfAbsent` as returning a nullable value, so the
     * null branch is stated rather than assumed — it should be unreachable, and if the
     * store ever surprises us it says so instead of throwing an NPE from nowhere.
     */
    private fun attached(ctx: ExtensionContext, name: String): Attached =
        ctx.root.getStore(Namespace.GLOBAL)
            .getOrComputeIfAbsent(name, { attach(name) }, Attached::class.java)
            ?: error("environment '$name' vanished from the extension store")

    private fun attach(name: String): Attached {
        val descriptor = Descriptors.read(name) ?: refuse(name, "no environment is running")

        // Liveness, not correctness. Detecting stale images is `terra up`'s job:
        // it recomputes the fingerprint, so rebuilt images produce a new project.
        if (!reachable(descriptor.health)) {
            refuse(name, "a descriptor exists but ${descriptor.health} is not answering")
        }
        // Once per JVM per environment, before the first test. Idempotent by contract,
        // because the environment usually outlives this process.
        Migrations.runFor(descriptor)

        return Attached(descriptor, LogReader(Path.of(descriptor.clusterLog)))
    }

    private fun refuse(name: String, why: String): Nothing = error(
        """

        Cannot run: $why for environment '$name'.

            ./terra up $name

        The test JVM never starts containers — that is the harness's job.
        """.trimIndent()
    )

    private fun reachable(url: String): Boolean = runCatching {
        URI(url).toURL().openConnection().apply {
            connectTimeout = 2000
            readTimeout = 2000
        }.getInputStream().close()
    }.isSuccess

    private fun resultDir(ctx: ExtensionContext, harness: TerraContext): Path =
        harness.descriptor.results
            .resolve("tests")
            .resolve(ctx.requiredTestClass.name.replace('.', '/'))
            .resolve(shortId(ctx.uniqueId) + "-" + ctx.displayName.replace(Regex("[^A-Za-z0-9]+"), "-"))
            .createDirectories()

    private fun ExtensionContext.store() =
        getStore(Namespace.create(TerraExtension::class.java, uniqueId))

    private companion object {
        const val CONTEXT = "terra.context"
        const val JOURNAL = "terra.journal"

        /**
         * Per *execution*, not per environment.
         *
         * The descriptor's runId lives as long as the environment does, so deriving
         * identities from it means the second run of a test against a retained
         * environment reuses every id and collides on insert — which is precisely the
         * inner loop this design exists to make fast. Identities must be unique per
         * JVM run; only the artifact directory belongs to the environment.
         */
        val EXEC_ID: String = System.getenv("TERRA_EXEC_ID")
            ?: Integer.toHexString(Random.nextInt()).takeLast(4)

        /** Short, stable, derived from the JUnit unique id — survives renames of everything else. */
        fun shortId(uniqueId: String) =
            abs(uniqueId.hashCode()).toString(36).padStart(4, '0').takeLast(4)
    }
}

/** One resolved environment, shared by every test class that names it. */
class Attached(val descriptor: EnvironmentDescriptor, val logs: LogReader)
