package terra

import org.awaitility.Awaitility
import java.lang.management.ManagementFactory
import java.time.Duration

/**
 * A breakpoint must not fail the suite.
 *
 * The moment someone attaches a debugger, every readiness wait and every polling
 * assertion starts firing for reasons unrelated to the bug being investigated.
 * Temporal ships an environment variable for this; the JVM will simply tell us.
 */
val timeoutScale: Int = when {
    ManagementFactory.getRuntimeMXBean().inputArguments.any { it.startsWith("-agentlib:jdwp") } -> 20
    else -> System.getenv("TERRA_TIMEOUT_SCALE")?.toIntOrNull() ?: 1
}

/**
 * `Thread.sleep(5000)` is a bet that the system is slower than nothing and faster
 * than five seconds, placed by someone who knew neither number. Poll instead, with
 * the timeout in one place rather than guessed per assertion.
 */
fun eventually(timeout: Duration = Duration.ofSeconds(30), assertion: () -> Unit) {
    var last: Throwable? = null
    try {
        Awaitility.await()
            .atMost(timeout.multipliedBy(timeoutScale.toLong()))
            .pollInterval(Duration.ofMillis(200))
            .pollDelay(Duration.ZERO)
            .ignoreExceptions()
            .untilAsserted {
                try {
                    assertion()
                } catch (failure: Throwable) {
                    last = failure          // Awaitility reports its own timeout, not this
                    throw failure
                }
            }
    } catch (timedOut: org.awaitility.core.ConditionTimeoutException) {
        // The whole value of a polling wrapper is on this path. Awaitility's own
        // message is "condition ... not fulfilled within 2 seconds", which is the
        // half of the story you already knew. Carry the last real failure instead.
        throw AssertionError(
            "timed out after $timeout: ${last?.message ?: "the assertion never ran"}",
            last ?: timedOut,
        )
    }
}
