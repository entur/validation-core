package no.entur.kafka.deadletter

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import org.springframework.core.NestedRuntimeException
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.ContainerProperties.AckMode
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.kafka.listener.MessageListenerContainer
import org.springframework.kafka.support.serializer.DeserializationException
import org.springframework.messaging.converter.MessageConversionException
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the handler does with a failed record as the container sees it: committed, or seeked back. */
class DeadLetterErrorHandlerTest {
    private val template = RecordingTemplate()
    private val consumer = consumer()
    private val logger = LoggerFactory.getLogger("dead-letter-test") as Logger
    private val logged = ListAppender<ILoggingEvent>().apply { start() }.also { logger.addAppender(it) }

    @Test
    fun `dead-letters a permanent failure once, then commits it`() {
        handler().handleRemaining(permanent(), listOf(rawRecord()), consumer, container())

        assertEquals(
            RAW_VALUE.toList(),
            template.sent
                .single()
                .value()
                .record.rawValue
                .toByteArray()
                .toList(),
        )
        verifyCommitted()
        assertEquals(listOf(Level.ERROR), logged.list.map { it.level })
        assertTrue(
            logged.list
                .single()
                .formattedMessage
                .contains("to $DEAD_LETTER_TOPIC"),
        )
    }

    @Test
    fun `dead-letters a failure Spring calls fatal through the same path`() {
        handler().handleRemaining(listenerFailure(MessageConversionException("not a delivery")), listOf(rawRecord()), consumer, container())

        assertEquals(1, template.sent.size)
        verifyCommitted()
    }

    @Test
    fun `keeps retrying a transient failure, warning each time, without dead-lettering or committing it`() {
        val handler = handler()

        repeat(ATTEMPTS) {
            assertThrows<NestedRuntimeException> { handler.handleRemaining(transient(), listOf(rawRecord()), consumer, container()) }
        }

        verify(consumer, times(ATTEMPTS)).seek(PARTITION, OFFSET)
        assertEquals(emptyList(), template.sent)
        verifyNoCommit()
        val warnings = logged.list.filter { it.level == Level.WARN }
        assertEquals(ATTEMPTS, warnings.size)
        assertTrue(warnings[1].formattedMessage.contains("$TOPIC-0@$OFFSET, key=RUT failed on attempt 2"), warnings[1].formattedMessage)
        assertEquals(IOException::class.java.name, warnings[0].throwableProxy.cause.className)
    }

    @Test
    fun `dead-letters a record whose failure turns permanent while it is being retried`() {
        val handler = handler()
        val decoding = { cause: Exception -> DeserializationException("decoding failed", RAW_VALUE, false, cause) }

        assertThrows<NestedRuntimeException> {
            handler.handleRemaining(listenerFailure(decoding(IOException("registry down"))), listOf(rawRecord()), consumer, container())
        }
        assertEquals(emptyList(), template.sent)

        handler.handleRemaining(listenerFailure(decoding(UnprocessableException())), listOf(rawRecord()), consumer, container())

        assertEquals(1, template.sent.size)
        verifyCommitted()
    }

    @Test
    fun `holds a permanent failure whose dead letter cannot be published, then retries it after a backoff`() {
        template.result = CompletableFuture.failedFuture(IOException("topic does not exist"))
        val handler = handler()

        assertThrows<RuntimeException> { handler.handleRemaining(permanent(), listOf(rawRecord()), consumer, container()) }
        assertEquals(1, template.sent.size)
        verify(consumer).seek(PARTITION, OFFSET)
        verifyNoCommit()

        // Held for a backoff rather than dead-lettered again straight away.
        assertThrows<NestedRuntimeException> { handler.handleRemaining(permanent(), listOf(rawRecord()), consumer, container()) }
        assertEquals(1, template.sent.size)

        template.result = sent()
        clearInvocations(consumer)
        handler.handleRemaining(permanent(), listOf(rawRecord()), consumer, container())

        assertEquals(2, template.sent.size)
        verifyCommitted()
    }

    @Test
    fun `without a dead-letter topic, retries a permanent failure by default and says why`() {
        assertThrows<NestedRuntimeException> {
            handler(recoverer = null).handleRemaining(permanent(), listOf(rawRecord()), consumer, container())
        }

        verify(consumer).seek(PARTITION, OFFSET)
        verifyNoCommit()
        assertTrue(
            logged.list
                .single { it.level == Level.WARN }
                .formattedMessage
                .contains("no dead-letter topic is configured"),
        )
    }

    @Test
    fun `without a dead-letter topic, can log and commit a permanent failure instead`() {
        handler(recoverer = null, withoutDeadLetterTopic = WithoutDeadLetterTopic.LOG_AND_COMMIT)
            .handleRemaining(permanent(), listOf(rawRecord()), consumer, container())

        verifyCommitted()
        assertEquals(listOf(Level.ERROR), logged.list.map { it.level })
        assertTrue(
            logged.list
                .single()
                .formattedMessage
                .contains("Giving up on $TOPIC-0@$OFFSET"),
        )
    }

    @Test
    fun `without a dead-letter topic, still retries a transient failure`() {
        assertThrows<NestedRuntimeException> {
            handler(recoverer = null, withoutDeadLetterTopic = WithoutDeadLetterTopic.LOG_AND_COMMIT)
                .handleRemaining(transient(), listOf(rawRecord()), consumer, container())
        }

        verify(consumer).seek(PARTITION, OFFSET)
        verifyNoCommit()
    }

    @Test
    fun `logs an untrusted key without its control characters`() {
        val record =
            ConsumerRecord<String, Any?>(TOPIC, 0, OFFSET, "RUT\nforged line", "decoded").apply {
                headers().add(RAW_VALUE_HEADER, RAW_VALUE)
            }

        handler().handleRemaining(permanent(), listOf(record), consumer, container())

        assertTrue(
            logged.list
                .single()
                .formattedMessage
                .contains("key=RUT_forged line"),
        )
    }

    @Test
    fun `caps the dead-letter retry interval however many attempts have failed`() {
        val retry = RetryPolicy()

        assertEquals(1_000L, retry.interval(1))
        assertEquals(2_000L, retry.interval(2))
        for (attempts in listOf(7, 64, 10_000, Int.MAX_VALUE)) {
            assertEquals(60_000L, retry.interval(attempts), "attempts=$attempts")
        }
    }

    private fun handler(
        recoverer: DeadLetterRecoverer? = DeadLetterRecoverer(template, DEAD_LETTER_TOPIC, "misharum"),
        withoutDeadLetterTopic: WithoutDeadLetterTopic = WithoutDeadLetterTopic.RETRY,
    ) = deadLetteringErrorHandler(
        recoverer,
        FailureClassifier(overrides = { if (it is UnprocessableException) Verdict.PERMANENT else Verdict.NONE }),
        withoutDeadLetterTopic = withoutDeadLetterTopic,
        logger = logger,
    )

    private class UnprocessableException : RuntimeException("Tombstone")

    private fun verifyCommitted() =
        verify(consumer).commitSync(eq(mapOf(PARTITION to OffsetAndMetadata(OFFSET + 1))), any(Duration::class.java))

    private fun verifyNoCommit() =
        verify(consumer, never()).commitSync(any<Map<TopicPartition, OffsetAndMetadata>>(), any(Duration::class.java))

    @Suppress("UNCHECKED_CAST")
    private fun consumer(): Consumer<String, Any> = mock(Consumer::class.java) as Consumer<String, Any>

    /** Reports itself stopped, so the backoff's stoppable sleep returns at once. */
    private fun container(): MessageListenerContainer {
        val properties =
            ContainerProperties(TOPIC).apply {
                ackMode = AckMode.MANUAL_IMMEDIATE
                syncCommitTimeout = Duration.ofSeconds(5)
            }
        return mock(MessageListenerContainer::class.java).also { `when`(it.containerProperties).thenReturn(properties) }
    }

    private fun listenerFailure(cause: Exception) = ListenerExecutionFailedException("Listener failed", cause)

    private fun permanent() = listenerFailure(UnprocessableException())

    private fun transient() = listenerFailure(IOException("connection reset"))

    private companion object {
        const val ATTEMPTS = 5
    }
}
