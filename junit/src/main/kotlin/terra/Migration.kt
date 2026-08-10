package terra

import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import java.util.Properties
import java.util.ServiceLoader

/**
 * Setup that belongs to the *environment*, not to a test: topics, indexes, static
 * reference data, anything every test in this topology assumes.
 *
 * Registered by ServiceLoader in the consuming project and run once per JVM, in
 * [order], before the first test attaches. They must be **idempotent**, because the
 * environment usually outlives the JVM — an IDE re-run against a retained environment
 * runs them again, and so does every shard.
 *
 * The alternative — a `@BeforeAll` somewhere — hides this work inside whichever test
 * class happened to run first, which is exactly the kind of ordering dependency that
 * makes a suite impossible to shard.
 */
interface EnvironmentMigration {

    /** Shown in failures. Make it say what it does. */
    val name: String

    /** Null applies to every environment; otherwise the `@Environment` name. */
    val environment: String? get() = null

    val order: Int get() = 100

    fun apply(ctx: MigrationContext)
}

class MigrationContext(
    val descriptor: EnvironmentDescriptor,
    private val databaseName: String,
) : AutoCloseable {

    private val closeables = mutableListOf<AutoCloseable>()

    val kafka: Admin by lazy {
        Admin.create(Properties().apply {
            put("bootstrap.servers", descriptor.endpoint("kafka").toString())
        }).also { closeables += AutoCloseable { it.close() } }
    }

    val mongo: MongoDatabase by lazy {
        val client = MongoClients.create("mongodb://${descriptor.endpoint("mongodb")}")
        closeables += AutoCloseable { client.close() }
        client.getDatabase(databaseName)
    }

    /** Create if absent. The common case, and the one that has to be idempotent. */
    fun topic(name: String, partitions: Int = 1, replication: Short = 1) {
        val existing = kafka.listTopics().names().get()
        if (name in existing) return
        runCatching { kafka.createTopics(listOf(NewTopic(name, partitions, replication))).all().get() }
            .onFailure { failure ->
                // Another shard or JVM won the race; that is success, not failure.
                if (failure.cause?.javaClass?.simpleName != "TopicExistsException") throw failure
            }
    }

    override fun close() = closeables.forEach { runCatching { it.close() } }
}

internal object Migrations {

    fun runFor(descriptor: EnvironmentDescriptor) {
        val applicable = ServiceLoader.load(EnvironmentMigration::class.java)
            .sortedBy { it.order }
            .filter { it.environment == null || it.environment == descriptor.name }

        // Topics declared in the environment file are created before anything else,
        // so `topics:` is a declaration rather than a thing to also remember to migrate.
        if (descriptor.topics.isNotEmpty() || applicable.isNotEmpty()) {
            MigrationContext(descriptor, Terra.database).use { ctx ->
                descriptor.topics.forEach { ctx.topic(it) }
                applicable.forEach { migration ->
                    runCatching { migration.apply(ctx) }.getOrElse { failure ->
                        error("migration '${migration.name}' failed for '${descriptor.name}': ${failure.message}")
                    }
                }
            }
        }
    }
}
