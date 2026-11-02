package no.entur.kafka

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.kafka.listener.ConsumerProperties
import org.springframework.kafka.listener.ContainerProperties.AckMode
import org.springframework.kafka.listener.RecordInterceptor

class SingleRecordContainerFactoryTest {
    @Suppress("UNCHECKED_CAST")
    private val consumerFactory = mock(ConsumerFactory::class.java) as ConsumerFactory<String, String>
    private val errorHandler = mock(CommonErrorHandler::class.java)

    @Test
    fun `processes one record at a time, acknowledging only once the listener is done with it`() {
        val factory = singleRecordContainerFactory(consumerFactory, errorHandler)

        assertEquals(1, privateField(factory, "concurrency"))
        assertEquals(AckMode.MANUAL_IMMEDIATE, factory.containerProperties.ackMode)
        assertSame(consumerFactory, factory.consumerFactory)
        assertSame(errorHandler, privateField(factory, "commonErrorHandler"))
    }

    @Test
    fun `defaults to a tracing record interceptor`() {
        val factory = singleRecordContainerFactory(consumerFactory, errorHandler)

        assertEquals(TracingRecordInterceptor::class.java, factory.recordInterceptor!!::class.java)
    }

    @Test
    fun `takes a different record interceptor when given one`() {
        @Suppress("UNCHECKED_CAST")
        val recordInterceptor = mock(RecordInterceptor::class.java) as RecordInterceptor<String, String>

        val factory = singleRecordContainerFactory(consumerFactory, errorHandler, recordInterceptor = recordInterceptor)

        assertSame(recordInterceptor, factory.recordInterceptor)
    }

    @Test
    fun `leaves the poll timeout at its default when none is given`() {
        val factory = singleRecordContainerFactory(consumerFactory, errorHandler)

        assertEquals(ConsumerProperties.DEFAULT_POLL_TIMEOUT, factory.containerProperties.pollTimeout)
    }

    @Test
    fun `overrides the poll timeout when given one`() {
        val factory = singleRecordContainerFactory(consumerFactory, errorHandler, pollTimeout = 3_000L)

        assertEquals(3_000L, factory.containerProperties.pollTimeout)
    }

    /** [name] has no public getter on [AbstractKafkaListenerContainerFactory][org.springframework.kafka.config.AbstractKafkaListenerContainerFactory]. */
    private fun privateField(
        target: Any,
        name: String,
    ): Any? =
        generateSequence(target::class.java) { it.superclass }
            .mapNotNull { type -> runCatching { type.getDeclaredField(name) }.getOrNull() }
            .first()
            .apply { isAccessible = true }
            .get(target)
}
