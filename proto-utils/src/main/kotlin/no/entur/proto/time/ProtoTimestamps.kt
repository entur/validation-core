package no.entur.proto.time

import com.google.protobuf.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

fun Instant.toProtoTimestamp(): Timestamp =
    Timestamp
        .newBuilder()
        .setSeconds(epochSecond)
        .setNanos(nano)
        .build()

fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())

fun OffsetDateTime.toProtoTimestamp(): Timestamp = toInstant().toProtoTimestamp()

/** In UTC. */
fun Timestamp.toOffsetDateTime(): OffsetDateTime = toInstant().atOffset(ZoneOffset.UTC)
