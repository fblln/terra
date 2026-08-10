package terra

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock

/**
 * Names a logical environment, never compose files — infrastructure layout must not
 * leak here. Inheritance is handled by walking the superclass chain at resolution
 * time rather than by `@Inherited`, which Kotlin has no equivalent of.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Environment(val value: String)

@ExtendWith(TerraExtension::class)
abstract class SystemTest

const val ENVIRONMENT = "terra.environment"

/**
 * Reads the environment; runs concurrently with other shared tests.
 * This is the default and should cover the large majority of tests.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Test
@Execution(ExecutionMode.CONCURRENT)
@ResourceLock(value = ENVIRONMENT, mode = ResourceAccessMode.READ)
annotation class SharedEnvTest

/**
 * Changes the environment — restarts a container, flips a flag, saturates a queue.
 * JUnit guarantees it never overlaps a shared test.
 *
 * This is the whole of the shared-versus-exclusive mechanism. Temporal builds a
 * pool of shared and dedicated clusters for the same problem; at one environment
 * per machine, a read/write lock is the entire solution.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Test
@ResourceLock(value = ENVIRONMENT, mode = ResourceAccessMode.READ_WRITE)
annotation class ExclusiveEnvTest
