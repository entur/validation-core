package no.entur.kafka.deadletter

import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.serialization.Deserializer

const val RAW_VALUE_HEADER = "entur_dlt_raw_value"

/** Keeps the consumed bytes, schema-id prefix included, so [DeadLetterRecoverer] can dead-letter a value [delegate] can't decode. */
class RawValueRetainingDeserializer<T>(
    private val delegate: Deserializer<T>,
) : Deserializer<T> {
    override fun configure(
        configs: Map<String, *>,
        isKey: Boolean,
    ) = delegate.configure(configs, isKey)

    override fun deserialize(
        topic: String,
        data: ByteArray?,
    ): T? = delegate.deserialize(topic, data)

    override fun deserialize(
        topic: String,
        headers: Headers,
        data: ByteArray?,
    ): T? {
        // Only this record's own bytes may be read back as its value, never a header it arrived with.
        headers.remove(RAW_VALUE_HEADER)
        if (data != null) {
            headers.add(RAW_VALUE_HEADER, data)
        }
        return delegate.deserialize(topic, headers, data)
    }

    override fun close() = delegate.close()
}
