package no.entur.kafka

import org.apache.kafka.clients.producer.ProducerInterceptor
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata

/**
 * Overwrites [CLIENT_NAME_HEADER] (a dead-lettered record carries its original sender's) and fills a
 * missing [CORRELATION_ID_HEADER] from this thread's MDC, both under the canonical header name. Set it
 * via [org.springframework.kafka.core.KafkaTemplate.setProducerInterceptor] so it runs on the caller's thread.
 */
class TracingProducerInterceptor<K, V>(
    private val clientName: String,
) : ProducerInterceptor<K, V> {
    override fun onSend(record: ProducerRecord<K, V>): ProducerRecord<K, V> {
        val headers = record.headers()
        headers.replace(CLIENT_NAME_HEADER, clientName)
        headers.replace(CORRELATION_ID_HEADER, headers.correlationId() ?: currentCorrelationId() ?: NOT_SET)
        return record
    }

    override fun onAcknowledgement(
        metadata: RecordMetadata?,
        exception: Exception?,
    ) = Unit

    override fun close() = Unit

    override fun configure(configs: Map<String, *>) = Unit
}
