package terra

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import org.bson.Document
import java.time.Duration

/**
 * Fixture setup and assertions against MongoDB, with the collection names, the
 * connection and the retry behaviour in one place instead of in three hundred tests.
 *
 * Nothing here truncates anything. Tests write documents under ids derived from
 * (run, test), so a shared database behaves like a private one and two tests can
 * seed the same collection at the same moment.
 */
class MongoProbe(endpoint: HostPort, private val ids: TestIds) : AutoCloseable {

    private val client: MongoClient = MongoClients.create("mongodb://$endpoint")
    private val database = client.getDatabase("systest")

    val orders: Collection by lazy { collection("orders") }
    val inventory: Collection by lazy { collection("inventory") }

    fun collection(name: String) = Collection(database.getCollection(name), ids)

    override fun close() = client.close()

    class Collection(
        private val collection: com.mongodb.client.MongoCollection<Document>,
        private val ids: TestIds,
    ) {
        /** Stamped with the test id so a document can always be traced to its author. */
        fun insert(id: String, vararg fields: Pair<String, Any?>): Document = Journal.record(
            "mongo", "insert ${collection.namespace.collectionName} $id",
        ) {
            val document = Document("_id", id).append("testId", ids.header)
            fields.forEach { (key, value) -> document.append(key, value) }
            collection.insertOne(document)
            document
        }

        fun get(id: String): Document? = collection.find(Filters.eq("_id", id)).first()

        fun require(id: String): Document =
            get(id) ?: error("no document '$id' in ${collection.namespace.collectionName}")

        fun set(id: String, vararg fields: Pair<String, Any?>) = Journal.record(
            "mongo", "update ${collection.namespace.collectionName} $id",
        ) {
            collection.updateOne(
                Filters.eq("_id", id),
                Updates.combine(fields.map { (k, v) -> Updates.set(k, v) }),
            )
        }

        /** Only this test's documents. The rest of the collection is somebody else's. */
        fun mine(): List<Document> = collection.find(Filters.eq("testId", ids.header)).toList()

        fun total(): Long = collection.countDocuments()

        /**
         * Global, destructive, and therefore only ever safe from an @ExclusiveEnvTest.
         * A shared test that calls this deletes other tests' fixtures out from under them.
         */
        fun drop() = collection.drop()

        /**
         * Poll until the document satisfies [predicate]. On timeout, print the document
         * as it actually reads — "expected RESERVED but was null" is rarely the useful
         * half of that sentence.
         */
        fun await(
            id: String,
            timeout: Duration = Duration.ofSeconds(30),
            predicate: (Document) -> Boolean,
        ): Document = Journal.record("mongo", "await ${collection.namespace.collectionName} $id") {
            var last: Document? = null
            eventually(timeout) {
                last = get(id)
                val document = last ?: error("document '$id' does not exist yet")
                check(predicate(document)) { "document '$id' is $document" }
            }
            last!!
        }
    }
}
