package terra

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.writeText

/**
 * What the test did, in order, so a failure comes with its own context.
 *
 * An assertion failure tells you the last thing that was untrue. It does not tell you
 * that the HTTP call before it returned 503, or that the event you were waiting for
 * went to a different topic. That sequence is the diagnosis, and the harness is the
 * only thing positioned to record it.
 *
 * Borrowed from Stove, which prints exactly this alongside the failure.
 *
 * Kept in a ThreadLocal rather than threaded through every probe constructor. That is
 * global mutable state, which this codebase otherwise avoids — but it is per-thread,
 * opened and closed by the extension around each test, and a probe used from a thread
 * the extension never touched simply records nothing instead of failing.
 */
object Journal {

    private val current = ThreadLocal<TestJournal?>()

    fun begin(name: String): TestJournal = TestJournal(name).also { current.set(it) }

    fun end() = current.remove()

    /** Wrap an operation. Records duration and outcome either way, then rethrows. */
    fun <T> record(system: String, operation: String, block: () -> T): T {
        val journal = current.get() ?: return block()
        val started = Instant.now()
        return try {
            block().also { result ->
                journal.add(system, operation, outcome(result), started, failed = false)
            }
        } catch (failure: Throwable) {
            journal.add(system, operation, failure.message.orEmpty().lines().first(), started, failed = true)
            throw failure
        }
    }

    /** Add a note without wrapping anything. */
    fun note(system: String, operation: String, outcome: String = "") {
        current.get()?.add(system, operation, outcome, Instant.now(), failed = false)
    }

    private fun outcome(result: Any?): String = when (result) {
        null, Unit -> ""
        is HttpProbe.Response -> "${result.status}"
        is Collection<*> -> "${result.size} item(s)"
        else -> result.toString().take(120)
    }
}

class TestJournal(private val name: String) {

    private data class Entry(
        val at: Instant,
        val system: String,
        val operation: String,
        val outcome: String,
        val millis: Long,
        val failed: Boolean,
    )

    private val entries = mutableListOf<Entry>()
    private val startedAt = Instant.now()

    @Synchronized
    internal fun add(system: String, operation: String, outcome: String, started: Instant, failed: Boolean) {
        entries += Entry(
            at = started,
            system = system,
            operation = operation,
            outcome = outcome,
            millis = Duration.between(started, Instant.now()).toMillis(),
            failed = failed,
        )
    }

    @Synchronized
    fun render(): String = buildString {
        appendLine(name)
        entries.forEach { entry ->
            val offset = Duration.between(startedAt, entry.at).toMillis()
            val marker = if (entry.failed) "✘" else "▶"
            append("  $marker %5dms  %-9s %-46s".format(offset, entry.system, entry.operation))
            if (entry.outcome.isNotEmpty()) append("  ${entry.outcome}")
            appendLine()
        }
        if (entries.isEmpty()) appendLine("  (nothing recorded)")
    }

    fun writeTo(directory: Path) = directory.resolve("timeline.txt").writeText(render())

    val isEmpty: Boolean get() = entries.isEmpty()
}
