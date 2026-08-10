package terra.cli

import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Log and event collection starts when the environment does, and runs until it
 * stops. Not on failure — by the time an assertion gives up, the seconds that
 * explain it have already scrolled past, and the only copy was in a stream nobody
 * was reading.
 *
 * The container event stream is the piece most suites omit and the one that turns
 * "expected a shipment event, none arrived" into "inventory-service died at :32".
 */
object Collectors {

    fun start(spec: EnvironmentSpec, into: Path): Started {
        into.createDirectories()
        val logs = Compose.follow(
            spec, into.resolve("cluster.log"),
            "logs", "--follow", "--timestamps", "--no-color",
        )
        val events = Compose.follow(spec, into.resolve("events.jsonl"), "events", "--json")
        return Started(
            clusterLog = into.resolve("cluster.log"),
            pids = listOf(logs.pid(), events.pid()),
        )
    }

    /** Reaped by pid because `up` exited long ago and these are no longer its children. */
    fun stop(pids: List<Long>) = pids.forEach { pid ->
        ProcessHandle.of(pid).ifPresent { handle ->
            handle.destroy()
            if (handle.isAlive) handle.destroyForcibly()
        }
    }

    fun alive(pids: List<Long>): Boolean =
        pids.isNotEmpty() && pids.all { ProcessHandle.of(it).map(ProcessHandle::isAlive).orElse(false) }

    data class Started(val clusterLog: Path, val pids: List<Long>)
}
