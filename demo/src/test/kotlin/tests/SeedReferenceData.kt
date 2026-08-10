package tests

import terra.EnvironmentMigration
import terra.MigrationContext
import org.bson.Document

/**
 * Environment setup, registered by ServiceLoader (see
 * `src/test/resources/META-INF/services/terra.EnvironmentMigration`).
 *
 * Runs once per JVM before the first test attaches, in `order`. It must be idempotent:
 * the environment usually outlives the process, so an IDE re-run runs it again, and so
 * does every CI shard.
 *
 * The alternative — a `@BeforeAll` in whichever test class happens to run first — is an
 * ordering dependency wearing a different hat.
 */
class SeedReferenceData : EnvironmentMigration {

    override val name = "seed carriers and warehouses"
    override val environment = "fulfilment"
    override val order = 10

    override fun apply(ctx: MigrationContext) {
        // Reference data every test may read and no test may own.
        val carriers = ctx.mongo.getCollection("carriers")
        listOf("dhl" to 2, "ups" to 3, "royal-mail" to 5).forEach { (code, days) ->
            carriers.replaceOne(
                Document("_id", code),
                Document("_id", code).append("transitDays", days).append("reference", true),
                com.mongodb.client.model.ReplaceOptions().upsert(true),      // idempotent
            )
        }

        // Topics beyond the ones declared in `topics:`.
        ctx.topic("returns-requested")
    }
}
