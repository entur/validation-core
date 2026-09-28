package no.entur.proto.time

import com.google.protobuf.Timestamp
import java.time.Instant

/**
 * Converts a java [Instant] to a proto [Timestamp].
 */
fun Instant.toProtoTimestamp(): Timestamp =
    Timestamp
        .newBuilder()
        .setSeconds(epochSecond)
        .setNanos(nano)
        .build()

/**
 * Converts a proto [Timestamp] to a java [Instant].
 */
fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())
