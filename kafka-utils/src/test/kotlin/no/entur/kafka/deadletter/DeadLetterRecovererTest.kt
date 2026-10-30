package no.entur.kafka.deadletter

import com.google.protobuf.StringValue
import no.entur.kafka.CLIENT_NAME_HEADER
import no.entur.kafka.CORRELATION_ID_HEADER
import no.entur.validation.proto.v1.DeadLetter
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.kafka.KafkaException
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.kafka.support.KafkaUtils
import org.springframework.kafka.support.serializer.DeserializationException
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeadLetterRecovererTest {
    private val template = RecordingTemplate()
    private val recoverer = DeadLetterRecoverer(template, DEAD_LETTER_TOPIC, "misharum")

    @AfterEach
    fun clearGroup() = KafkaUtils.clearConsumerGroupId()

    @Test
    fun `wraps the record's exact bytes and coordinates in the envelope`() {
        KafkaUtils.setConsumerGroupId("misharum-dev")
        val record =
            ConsumerRecord<String, Any?>(
                TOPIC,
                0,
                OFFSET,
                1_700_000_000_123L,
                TimestampType.CREATE_TIME,
                3,
                9,
                "RUT",
                "decoded",
                RecordHeaders().add(CORRELATION_ID_HEADER, "corr-1".toByteArray()).add(RAW_VALUE_HEADER, RAW_VALUE),
                Optional.empty(),
            )
        val before = Instant.now()

        val deadLetter =
            recoverer.toDeadLetter(
                record,
                ListenerExecutionFailedException("Listener failed", IllegalStateException("bad", IOException("io"))),
            )

        assertContentEquals(RAW_VALUE, deadLetter.record.rawValue.toByteArray())
        assertEquals(TOPIC, deadLetter.record.topic)
        assertEquals(0, deadLetter.record.partition)
        assertEquals(OFFSET, deadLetter.record.offset)
        assertEquals(1_700_000_000L, deadLetter.record.timestamp.seconds)
        assertEquals(123_000_000, deadLetter.record.timestamp.nanos)
        assertEquals("RUT", deadLetter.record.key)
        assertEquals("misharum", deadLetter.consumer.service)
        assertEquals("corr-1", deadLetter.correlationId)
        assertEquals("misharum-dev", deadLetter.consumer.group)
        assertTrue(deadLetter.deadLetteredAt.seconds >= before.epochSecond)
        with(deadLetter.failure) {
            assertEquals(IllegalStateException::class.java.name, exceptionClass)
            assertEquals("bad", message)
            assertEquals(IOException::class.java.name, causeClass)
            assertEquals("io", causeMessage)
            assertTrue(stacktrace.startsWith("java.lang.IllegalStateException: bad"), stacktrace)
        }
    }

    @Test
    fun `wraps a decoded protobuf message as an Any rather than its bytes`() {
        val message = StringValue.of("RUT")
        val record =
            ConsumerRecord<String, Any?>(TOPIC, 0, OFFSET, "RUT", message).apply {
                headers().add(RAW_VALUE_HEADER, RAW_VALUE)
            }

        val deadLetter = recoverer.toDeadLetter(record, IllegalStateException())

        assertEquals(DeadLetter.Record.ValueCase.MESSAGE, deadLetter.record.valueCase)
        assertEquals(message, deadLetter.record.message.unpack(StringValue::class.java))
    }

    @Test
    fun `dead-letters a tombstone with its value unset`() {
        val deadLetter = recoverer.toDeadLetter(ConsumerRecord<String, Any?>(TOPIC, 0, OFFSET, null, null), IllegalStateException())

        assertEquals(DeadLetter.Record.ValueCase.VALUE_NOT_SET, deadLetter.record.valueCase)
        assertFalse(deadLetter.record.hasKey())
        assertEquals("", deadLetter.correlationId)
        assertEquals("", deadLetter.consumer.group)
    }

    @Test
    fun `an empty value is kept apart from a tombstone`() {
        val deadLetter =
            recoverer.toDeadLetter(
                ConsumerRecord<String, Any?>(TOPIC, 0, OFFSET, "RUT", ByteArray(0)),
                IllegalStateException(),
            )

        assertTrue(deadLetter.record.hasRawValue())
        assertTrue(deadLetter.record.rawValue.isEmpty)
    }

    @Test
    fun `keeps the bytes of a value that could not be decoded, without the raw-value header`() {
        val record =
            ConsumerRecord<String, Any?>(TOPIC, 0, OFFSET, "RUT", null).apply {
                headers().add(KafkaUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER, "serialized exception".toByteArray())
            }
        val failure = DeserializationException("failed", RAW_VALUE, false, IOException("truncated"))

        val deadLetter = recoverer.toDeadLetter(record, ListenerExecutionFailedException("Listener failed", failure))

        assertContentEquals(RAW_VALUE, deadLetter.record.rawValue.toByteArray())
        assertEquals(emptyList(), deadLetter.record.headersList)
    }

    @Test
    fun `dead-letters a tombstone even when its failure's causes loop`() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)

        val deadLetter = recoverer.toDeadLetter(ConsumerRecord<String, Any?>(TOPIC, 0, OFFSET, "RUT", null), first)

        assertEquals(DeadLetter.Record.ValueCase.VALUE_NOT_SET, deadLetter.record.valueCase)
    }

    @Test
    fun `keeps a null header value apart from an empty one`() {
        val record =
            rawRecord().apply {
                headers().add("null", null)
                headers().add("empty", ByteArray(0))
            }

        val headers = recoverer.toDeadLetter(record, IllegalStateException()).record.headersList

        assertEquals(listOf("null" to false, "empty" to true), headers.map { it.key to it.hasValue() })
    }

    @Test
    fun `refuses a decoded value with no bytes to keep`() {
        assertThrows<IllegalStateException> {
            recoverer.toDeadLetter(ConsumerRecord<String, Any?>(TOPIC, 0, OFFSET, "RUT", "decoded"), IllegalStateException())
        }
    }

    @Test
    fun `keeps the original headers in order with duplicates, minus the ones the consumer added`() {
        val record =
            rawRecord().apply {
                headers().add("b", "1".toByteArray())
                headers().add(KafkaUtils.KEY_DESERIALIZER_EXCEPTION_HEADER, ByteArray(1))
                headers().add("a", "2".toByteArray())
                headers().add("b", "3".toByteArray())
            }

        val headers = recoverer.toDeadLetter(record, IllegalStateException()).record.headersList

        assertEquals(listOf("b" to "1", "a" to "2", "b" to "3"), headers.map { it.key to it.value.toStringUtf8() })
    }

    @Test
    fun `truncates a very long stack trace`() {
        val deep = (1..2_000).fold(IllegalStateException("root") as Throwable) { cause, i -> IllegalStateException("level $i", cause) }

        assertEquals(
            16 * 1024,
            recoverer
                .toDeadLetter(rawRecord(), deep)
                .failure.stacktrace.length,
        )
    }

    @Test
    fun `publishes the envelope keyed like the record, with its correlation id`() {
        val record =
            rawRecord().apply {
                headers().add(CORRELATION_ID_HEADER, "corr-1".toByteArray())
                headers().add(CLIENT_NAME_HEADER, "orch".toByteArray())
            }

        recoverer.accept(record, IllegalStateException())

        val published = template.sent.single()
        assertEquals(DEAD_LETTER_TOPIC, published.topic())
        assertNull(published.partition())
        assertEquals("RUT", published.key())
        assertContentEquals(
            RAW_VALUE,
            published
                .value()
                .record.rawValue
                .toByteArray(),
        )
        assertEquals(listOf(CORRELATION_ID_HEADER), published.headers().map { it.key() })
        assertEquals(
            "corr-1",
            published
                .headers()
                .lastHeader(CORRELATION_ID_HEADER)
                .value()
                .decodeToString(),
        )
    }

    @Test
    fun `throws when the publish fails, so the offset is not committed`() {
        template.result = CompletableFuture.failedFuture(IOException("topic does not exist"))

        val thrown = assertThrows<KafkaException> { recoverer.accept(rawRecord(), IllegalStateException()) }

        assertEquals<Class<*>?>(IOException::class.java, thrown.cause?.javaClass)
    }

    @Test
    fun `throws when the publish does not complete in time`() {
        template.result = CompletableFuture()
        val impatient = DeadLetterRecoverer(template, DEAD_LETTER_TOPIC, "misharum", Duration.ofMillis(10))

        assertThrows<KafkaException> { impatient.accept(rawRecord(), IllegalStateException()) }
    }
}
