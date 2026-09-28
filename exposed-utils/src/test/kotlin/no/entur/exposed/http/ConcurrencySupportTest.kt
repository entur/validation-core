package no.entur.exposed.http

import no.entur.exposed.testfixtures.v1.label
import no.entur.exposed.testfixtures.v1.note
import no.entur.exposed.testfixtures.v1.widget
import no.entur.proto.time.toProtoTimestamp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Unit-level coverage for [ConcurrencySupport.etag]'s reflective `Message` overload - no DB, no Spring
 * context, since none of this depends on persistence. [ConcurrencySupportPostgresTest] covers the
 * SQL/JVM cross-check against a real Postgres.
 */
internal class ConcurrencySupportTest {
    @Test
    fun `compute(message) hashes a string IDENTIFIER field the same way as compute(id, updatedAt)`() {
        val instant = Instant.ofEpochSecond(1_700_000_000L, 123_456_000L)
        val message =
            label {
                id = "owner-name"
                updatedAt = instant.toProtoTimestamp()
            }

        assertEquals(ConcurrencySupport.etag("owner-name", instant), ConcurrencySupport.etag(message))
    }

    @Test
    fun `compute(message) hashes a numeric IDENTIFIER field the same way as compute(id, updatedAt)`() {
        val instant = Instant.ofEpochSecond(1_700_000_000L, 123_456_000L)
        val message =
            widget {
                id = 42L
                updatedAt = instant.toProtoTimestamp()
            }

        assertEquals(ConcurrencySupport.etag(42L, instant), ConcurrencySupport.etag(message))
    }

    @Test
    fun `compute(message) is null for a message with no IDENTIFIER or OUTPUT_ONLY fields`() {
        assertEquals(null, ConcurrencySupport.etag(note { message = "m" }))
    }
}
