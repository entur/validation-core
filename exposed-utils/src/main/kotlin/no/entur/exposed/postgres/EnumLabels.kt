package no.entur.exposed.postgres

import com.google.protobuf.Descriptors.EnumDescriptor

/** The labels a [PostgresEnumColumnType] accepts: every declared value except the zero `*_UNSPECIFIED` one. */
fun enumLabelsFor(descriptor: EnumDescriptor): Set<String> =
    descriptor.values
        .filterNot { it.number == 0 }
        .map { it.name }
        .toSet()
