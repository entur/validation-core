package no.entur.kafka.deadletter

import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RawValueRetainingDeserializerTest {
    @Test
    fun `keeps the bytes on the record and hands back what the delegate decoded`() {
        val headers = RecordHeaders()

        val value = RawValueRetainingDeserializer(StringDeserializer()).deserialize(TOPIC, headers, BYTES)

        assertEquals("RUT", value)
        assertContentEquals(BYTES, headers.lastHeader(RAW_VALUE_HEADER).value())
    }

    @Test
    fun `keeps the bytes even when the delegate cannot decode them`() {
        val headers = RecordHeaders()
        val failing =
            object : Deserializer<String> {
                override fun deserialize(
                    topic: String,
                    data: ByteArray?,
                ): String = error("not a ValidationDelivery")
            }

        assertThrows<IllegalStateException> { RawValueRetainingDeserializer(failing).deserialize(TOPIC, headers, BYTES) }

        assertContentEquals(BYTES, headers.lastHeader(RAW_VALUE_HEADER).value())
    }

    @Test
    fun `adds nothing for a tombstone`() {
        val headers: Headers = RecordHeaders()

        RawValueRetainingDeserializer(StringDeserializer()).deserialize(TOPIC, headers, null as ByteArray?)

        assertNull(headers.lastHeader(RAW_VALUE_HEADER))
    }

    @Test
    fun `drops a raw-value header the record arrived with`() {
        val tombstone = RecordHeaders().apply { add(RAW_VALUE_HEADER, "stale".toByteArray()) }
        val record = RecordHeaders().apply { add(RAW_VALUE_HEADER, "stale".toByteArray()) }

        RawValueRetainingDeserializer(StringDeserializer()).deserialize(TOPIC, tombstone, null as ByteArray?)
        RawValueRetainingDeserializer(StringDeserializer()).deserialize(TOPIC, record, BYTES)

        assertNull(tombstone.lastHeader(RAW_VALUE_HEADER))
        assertEquals(1, record.headers(RAW_VALUE_HEADER).count())
        assertContentEquals(BYTES, record.lastHeader(RAW_VALUE_HEADER).value())
    }

    private companion object {
        const val TOPIC = "validation-delivery-dev"
        val BYTES = "RUT".toByteArray()
    }
}
