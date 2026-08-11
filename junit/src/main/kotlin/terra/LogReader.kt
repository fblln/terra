package terra

import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.*

/**
 * Test-side view of the cluster log. It only ever reads: the followers belong to
 * `terra up` and run for the environment's whole life, which is why an IDE run
 * gets the same evidence a harness run does.
 *
 * `docker compose logs --timestamps --no-color` emits:
 *     gateway-1  | 2026-08-08T13:42:31.351423Z the message
 */
class LogReader(private val file: Path) {

    private val line = Regex("""^(\S+)\s+\|\s+(\S+)\s(.*)$""")

    private fun entries(from: Instant, to: Instant): List<Entry> {
        if (!file.exists()) return emptyList()
        return file.useLines { lines ->
            lines.mapNotNull { line.matchEntire(it) }
                .mapNotNull { m ->
                    val at = runCatching { Instant.parse(m.groupValues[2]) }.getOrNull()
                    at?.let { Entry(m.groupValues[1], it, m.groupValues[3]) }
                }
                .filter { it.at >= from && it.at <= to }
                .toList()
        }
    }

    /**
     * The window for a failed test, split by service. Two seconds of margin either
     * side, because the interesting cause usually precedes the visible symptom.
     */
    fun window(from: Instant, to: Instant, into: Path) {
        Thread.sleep(500)                       // let the follower flush the tail
        val dir = into.resolve("logs").createDirectories()
        entries(from.minusSeconds(2), to.plusSeconds(2))
            .groupBy { it.service }
            .forEach { (service, rows) ->
                dir.resolve("$service.log").writeLines(rows.map { "${it.at} ${it.message}" })
            }
    }

    /**
     * The log is not only evidence, it is an assertion every test gets without
     * writing one. A test that satisfies its own expectations while a service threw
     * fifty exceptions is not a passing test; it is a test that was not looking.
     *
     * Every entry in [ALLOWED] is a decision someone had to defend in review. That
     * is the point of keeping the list short and in one place.
     */
    fun assertNoUnexpectedErrors(since: Instant) {
        val bad = entries(since, Instant.now())
            .filter { ERROR.containsMatchIn(it.message) }
            .filterNot { entry -> ALLOWED.any { it.containsMatchIn(entry.message) } }
        check(bad.isEmpty()) {
            buildString {
                appendLine("${bad.size} unexpected error(s) in container logs during this test:")
                bad.take(20).forEach { appendLine("  ${it.service}: ${it.message}") }
                if (bad.size > 20) appendLine("  … ${bad.size - 20} more")
            }
        }
    }

    private data class Entry(val service: String, val at: Instant, val message: String)

    private companion object {
        /**
         * Deliberately level-based, not name-based.
         *
         * An earlier version also matched any line mentioning `\w+Exception`, which
         * looks thorough and is a false-positive machine: services log exception class
         * names at INFO and WARN constantly. MongoDB 8 informing us it retried a
         * `WriteConflictException` — severity "I", handled internally, entirely normal
         * when two tests create a collection at the same moment — failed two unrelated
         * tests before this was fixed.
         *
         * So: an explicit error level, a JSON error severity, or a line that *is* a
         * stack trace. Anything merely talking about an exception is prose.
         */
        val ERROR = Regex(
            """\bERROR\b|\bFATAL\b|\bSEVERE\b""" +          // text loggers
                """|"s":"[EF]"""" +                              // JSON loggers (mongo)
                """|^\s*(?:[\w${'$'}.]+\.)?\w*(?:Exception|Error)(?::|\s|${'$'})""" +  // stack trace head
                """|^\s*Caused by:"""
        )

        /** Whatever the project declared in [Terra.allowedLogPatterns]. Empty by default. */
        val ALLOWED: List<Regex> get() = Terra.allowedLogPatterns
    }
}
