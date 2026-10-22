package no.entur.exposed.postgres

import com.google.protobuf.Descriptors.EnumDescriptor

/**
 * The labels a [PostgresEnumColumnType] for [descriptor] should accept: every value [descriptor]
 * declares except the zero one - by proto3 convention an enum's zero value is always a `*_UNSPECIFIED` sentinel.
 * Note that the `UNRECOGNIZED` enum value is also not included. This is a -1 sentinel never listed in the
 * description values.
 */
fun enumLabelsFor(descriptor: EnumDescriptor): Set<String> =
    descriptor.values
        .filterNot { it.number == 0 }
        .map { it.name }
        .toSet()
