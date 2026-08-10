package terra

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.kafka.clients.consumer.ConsumerConfig.*
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.time.Instant
import java.util.Properties

typealias Checkpoint = Map<TopicPartition, Long>

/**
 * Isolation without a reset.
 *
 * Nothing is deleted and no topic is truncated. A test records where the log is
 * now, then reads only forward from that mark, filtering by an id only it knows.
 * Two tests can publish to the same topic at the same moment and neither can see
 * the other's events — which is what makes a shared environment safe to share.
 */
class KafkaProbe(
    bootstrap: HostPort,
    group: String,
    private val autoCheckpointTopics: List<String> = emptyList(),
) : AutoCloseable {

    /**
     * Checkpoints taken when the probe is created — which the extension does at the
     * start of every test, before the test can act.
     *
     * Taking the mark yourself is the single easiest thing to get wrong: forget it, or
     * take it after the action, and the assertion silently reads the wrong window. The
     * harness can always take it strictly earlier than any author would, so it does.
     */
    private val marks: Map<String, Checkpoint> by lazy {
        autoCheckpointTopics.associateWith { checkpoint(it) }
    }

    /** Force the marks to be taken now. Called by the extension in beforeEach. */
    fun armCheckpoints() { marks }

    /**
     * The assertion most tests want: something matching this predicate was published
     * after the test began.
     *
     * The window comes from the automatic checkpoint; the *authorship* still comes from
     * you, so match on an id only this test generated.
     */
    inline fun <reified T : Any> shouldBePublished(
        topic: String,
        timeout: Duration = Duration.ofSeconds(30),
        noinline matches: (T) -> Boolean,
    ): T = shouldBePublished(topic, T::class.java, timeout, matches)

    fun <T : Any> shouldBePublished(
        topic: String,
        type: Class<T>,
        timeout: Duration,
        matches: (T) -> Boolean,
    ): T = Journal.record("kafka", "shouldBePublished<${type.simpleName}> $topic") {
        awaitAfter(markFor(topic), topic, type, timeout, matches)
    }

    /** Nothing matching was published since the test began. Give it a short timeout. */
    inline fun <reified T : Any> shouldNotBePublished(
        topic: String,
        within: Duration = Duration.ofSeconds(2),
        noinline matches: (T) -> Boolean,
    ) {
        val found = runCatching { shouldBePublished(topic, T::class.java, within, matches) }
        check(found.isFailure) { "expected nothing matching on '$topic', got ${found.getOrNull()}" }
    }

    fun markFor(topic: String): Checkpoint = marks[topic]
        ?: error(
            "no automatic checkpoint for '$topic'. Add it to `topics:` in the environment " +
                "file, or take one yourself with checkpoint(\"$topic\")."
        )

    private val bootstrapServers = bootstrap.toString()

    val mapper: com.fasterxml.jackson.databind.ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    private val consumer = KafkaConsumer<String, ByteArray>(Properties().apply {
        put(BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(GROUP_ID_CONFIG, group)                       // unique per test; never reused
        put(KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
        put(ENABLE_AUTO_COMMIT_CONFIG, false)
        put(AUTO_OFFSET_RESET_CONFIG, "latest")
    })

    private val producerHolder = lazy {
        org.apache.kafka.clients.producer.KafkaProducer<String, ByteArray>(Properties().apply {
            put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer::class.java.name)
            put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.ByteArraySerializer::class.java.name)
        })
    }
    private val producer get() = producerHolder.value

    /** Seeding events is fixture setup, not a test concern — so it lives here. */
    fun publish(topic: String, value: Any, key: String? = null) = Journal.record("kafka", "publish -> $topic") {
        producer.send(
            org.apache.kafka.clients.producer.ProducerRecord(topic, key, mapper.writeValueAsBytes(value))
        ).get()
    }

    /**
     * Where every partition of [topic] ends right now. Take this *before* acting.
     *
     * The first caller of a run often checkpoints a topic nothing has published to
     * yet. The metadata request itself triggers auto-creation, but the first response
     * can still come back empty, so retry briefly rather than failing a test for a
     * reason that has nothing to do with what it is testing.
     */
    fun checkpoint(topic: String): Checkpoint {
        val deadline = Instant.now().plusSeconds(10L * timeoutScale)
        while (Instant.now() < deadline) {
            val partitions = consumer.partitionsFor(topic, Duration.ofSeconds(5))
                ?.map { TopicPartition(it.topic(), it.partition()) }
                .orEmpty()
            if (partitions.isNotEmpty()) return consumer.endOffsets(partitions)
            Thread.sleep(250)
        }
        error(
            "topic '$topic' has no partitions after 10s. Either it does not exist and " +
                "auto-creation is off, or the broker is not ready."
        )
    }

    inline fun <reified T : Any> awaitAfter(
        mark: Checkpoint,
        topic: String,
        timeout: Duration = Duration.ofSeconds(30),
        noinline matches: (T) -> Boolean,
    ): T = awaitAfter(mark, topic, T::class.java, timeout, matches)

    inline fun <reified T : Any> readAfter(
        mark: Checkpoint,
        topic: String,
        count: Int,
        timeout: Duration = Duration.ofSeconds(30),
        noinline matches: (T) -> Boolean,
    ): List<T> = readAfter(mark, topic, T::class.java, count, timeout, matches)

    /**
     * The first [count] records after [mark] that satisfy [matches], in order.
     *
     * The predicate is mandatory, and that is the whole point: a checkpoint bounds
     * the *window*, not the *authorship*. Any test sharing this topic is publishing
     * into the same window, so an unfiltered batch read silently returns somebody
     * else's events interleaved with yours — which looks exactly like an ordering bug.
     *
     * Separate from [awaitAfter] because that seeks per call, so calling it N times
     * returns the first match N times.
     */
    fun <T : Any> readAfter(
        mark: Checkpoint,
        topic: String,
        type: Class<T>,
        count: Int,
        timeout: Duration,
        matches: (T) -> Boolean,
    ): List<T> {
        consumer.assign(mark.keys)
        mark.forEach { (partition, offset) -> consumer.seek(partition, offset) }

        val deadline = Instant.now().plus(timeout.multipliedBy(timeoutScale.toLong()))
        val collected = mutableListOf<T>()
        while (Instant.now() < deadline && collected.size < count) {
            for (record in consumer.poll(Duration.ofMillis(250))) {
                runCatching { mapper.readValue(record.value(), type) }.getOrNull()
                    ?.takeIf(matches)
                    ?.let { collected += it }
                if (collected.size == count) break
            }
        }
        check(collected.size == count) {
            "expected $count matching ${type.simpleName} on '$topic' after the checkpoint, " +
                "got ${collected.size}: $collected"
        }
        return collected
    }

    fun <T : Any> awaitAfter(
        mark: Checkpoint,
        topic: String,
        type: Class<T>,
        timeout: Duration,
        matches: (T) -> Boolean,
    ): T {
        consumer.assign(mark.keys)
        mark.forEach { (partition, offset) -> consumer.seek(partition, offset) }

        val deadline = Instant.now().plus(timeout.multipliedBy(timeoutScale.toLong()))
        val seen = mutableListOf<String>()

        while (Instant.now() < deadline) {
            for (record in consumer.poll(Duration.ofMillis(250))) {
                val value = runCatching { mapper.readValue(record.value(), type) }.getOrNull()
                if (value != null && matches(value)) return value
                seen += String(record.value())
            }
        }

        // A timeout that reports only "not found" throws away the one moment when it
        // knew everything. Show what did arrive; it is almost always the answer.
        error(
            buildString {
                appendLine("no matching ${type.simpleName} on '$topic' within $timeout.")
                appendLine("saw ${seen.size} record(s) after the checkpoint:")
                seen.takeLast(10).forEach { appendLine("  $it") }
            }
        )
    }

    override fun close() {
        if (producerHolder.isInitialized()) producer.close()
        consumer.close()
    }
}
