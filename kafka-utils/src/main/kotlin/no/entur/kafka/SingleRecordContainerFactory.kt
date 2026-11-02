package no.entur.kafka

import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.kafka.listener.ContainerProperties.AckMode
import org.springframework.kafka.listener.RecordInterceptor

/**
 * A container factory for one record at a time: `concurrency(1)` and [AckMode.MANUAL_IMMEDIATE],
 * so a listener acknowledges only once it has actually finished with a record, not before.
 * [pollTimeout], left unset, keeps whatever [ConcurrentKafkaListenerContainerFactory] defaults to.
 */
fun <K : Any, V : Any> singleRecordContainerFactory(
    consumerFactory: ConsumerFactory<K, V>,
    errorHandler: CommonErrorHandler,
    recordInterceptor: RecordInterceptor<K, V> = TracingRecordInterceptor(),
    pollTimeout: Long? = null,
): ConcurrentKafkaListenerContainerFactory<K, V> =
    ConcurrentKafkaListenerContainerFactory<K, V>().apply {
        setConcurrency(1)
        setConsumerFactory(consumerFactory)
        setRecordInterceptor(recordInterceptor)
        containerProperties.ackMode = AckMode.MANUAL_IMMEDIATE
        pollTimeout?.let { containerProperties.pollTimeout = it }
        setCommonErrorHandler(errorHandler)
    }
