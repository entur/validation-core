package no.entur.kafka

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.MDC
import org.springframework.kafka.listener.RecordInterceptor

/**
 * Puts the record's correlation id and sender in the MDC while the listener runs. [missingCorrelationId]
 * lets a service that mints its own ids use a fresh one instead of [NOT_SET].
 */
class TracingRecordInterceptor<K : Any, V : Any>(
    private val missingCorrelationId: () -> String = { NOT_SET },
) : RecordInterceptor<K, V> {
    override fun intercept(
        record: ConsumerRecord<K, V>,
        consumer: Consumer<K, V>,
    ): ConsumerRecord<K, V> {
        MDC.put(CORRELATION_ID_MDC_KEY, record.headers().correlationId() ?: missingCorrelationId())
        MDC.put(CLIENT_NAME_MDC_KEY, record.headers().clientName() ?: NOT_SET)
        return record
    }

    override fun afterRecord(
        record: ConsumerRecord<K, V>,
        consumer: Consumer<K, V>,
    ) {
        MDC.remove(CORRELATION_ID_MDC_KEY)
        MDC.remove(CLIENT_NAME_MDC_KEY)
    }
}
