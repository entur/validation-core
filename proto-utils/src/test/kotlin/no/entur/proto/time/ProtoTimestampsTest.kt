package no.entur.proto.time

import com.google.protobuf.Timestamp
import com.google.protobuf.timestamp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal class ProtoTimestampsTest {
    @Test
    fun `toProtoTimestamp carries seconds and nanos over`() {
        val timestamp = Instant.ofEpochSecond(1_700_000_000L, 123_456_789L).toProtoTimestamp()
        assertEquals(1_700_000_000L, timestamp.seconds)
        assertEquals(123_456_789, timestamp.nanos)
    }

    @Test
    fun `toInstant carries seconds and nanos over`() {
        val instant =
            timestamp {
                seconds = 1_700_000_000L
                nanos = 123_456_789
            }.toInstant()
        assertEquals(1_700_000_000L, instant.epochSecond)
        assertEquals(123_456_789, instant.nano)
    }

    @Test
    fun `an Instant survives a round trip through Timestamp`() {
        val instant = Instant.ofEpochSecond(1_700_000_000L, 123_456_789L)
        assertEquals(instant, instant.toProtoTimestamp().toInstant())
    }

    @Test
    fun `a Timestamp survives a round trip through Instant`() {
        val timestamp =
            timestamp {
                seconds = 42L
                nanos = 123_456_789
            }
        assertEquals(timestamp, timestamp.toInstant().toProtoTimestamp())
    }

    @Test
    fun `epoch converts to a zero Timestamp`() {
        assertEquals(Timestamp.getDefaultInstance(), Instant.EPOCH.toProtoTimestamp())
    }

    @Test
    fun `an instant before the epoch round trips with a negative seconds count`() {
        val instant = Instant.ofEpochSecond(-1_000L, 500L)
        val timestamp = instant.toProtoTimestamp()

        assertEquals(-1_000L, timestamp.seconds)
        assertEquals(500, timestamp.nanos)
        assertEquals(instant, timestamp.toInstant())
    }

    @Test
    fun `nanos at the top of their range are preserved`() {
        val instant = Instant.ofEpochSecond(0L, 999_999_999L)
        assertEquals(instant, instant.toProtoTimestamp().toInstant())
    }

    @Test
    fun `an OffsetDateTime's toProtoTimestamp carries seconds and nanos over`() {
        val offsetDateTime =
            OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(1_700_000_000L, 123_456_789L),
                ZoneOffset.ofHours(2),
            )
        val timestamp = offsetDateTime.toProtoTimestamp()
        assertEquals(1_700_000_000L, timestamp.seconds)
        assertEquals(123_456_789, timestamp.nanos)
    }

    @Test
    fun `an OffsetDateTime survives a round trip through Timestamp`() {
        val offsetDateTime = OffsetDateTime.of(2023, 11, 14, 22, 13, 20, 123_456_789, ZoneOffset.ofHours(-5))
        assertEquals(offsetDateTime.toInstant(), offsetDateTime.toProtoTimestamp().toInstant())
    }

    @Test
    fun `OffsetDateTimes representing the same instant in different offsets produce the same Timestamp`() {
        val instant = Instant.ofEpochSecond(1_700_000_000L, 123_456_789L)
        val atUtc = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
        val atPlusNine = OffsetDateTime.ofInstant(instant, ZoneOffset.ofHours(9))

        assertEquals(atUtc.toProtoTimestamp(), atPlusNine.toProtoTimestamp())
    }
}
