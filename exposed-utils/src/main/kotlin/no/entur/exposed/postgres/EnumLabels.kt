package no.entur.exposed.postgres

import com.google.protobuf.Descriptors.EnumDescriptor

/**
 * The labels a [PostgresEnumColumnType] for [descriptor] should accept: every value [descriptor]
 * declares except the zero one - by proto3 convention an enum's zero value is always a
 * `*_UNSPECIFIED` sentinel, never a value a row should actually be stored with. Unlike the
 * generated enum class's own `entries`, nothing here needs to filter out `UNRECOGNIZED` too:
 * [EnumDescriptor.getValues] never includes it to begin with - it's a closed-enum fallback
 * protobuf's Java/Kotlin codegen adds to the generated class itself, not a value the wire schema
 * (or this descriptor) declares.
 */
fun enumLabelsFor(descriptor: EnumDescriptor): Set<String> =
    descriptor.values
        .filterNot { it.number == 0 }
        .map { it.name }
        .toSet()
