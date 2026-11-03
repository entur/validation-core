package no.entur.proto.time

import com.google.protobuf.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Converts a Java [Instant] to a proto [Timestamp]. */
fun Instant.toProtoTimestamp(): Timestamp =
    Timestamp
        .newBuilder()
        .setSeconds(epochSecond)
        .setNanos(nano)
        .build()

/** Converts a proto [Timestamp] to a Java [Instant]. */
fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())

/** Converts an [OffsetDateTime] to a proto [Timestamp]. */
fun OffsetDateTime.toProtoTimestamp(): Timestamp = toInstant().toProtoTimestamp()

/** Converts a proto [Timestamp] to an [OffsetDateTime] in UTC. */
fun Timestamp.toOffsetDateTime(): OffsetDateTime = toInstant().atOffset(ZoneOffset.UTC)
