package no.entur.kafka.schema

import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.FileDescriptor
import io.confluent.kafka.schemaregistry.protobuf.ProtobufSchema
import no.entur.proto.schema.withoutFieldOptionExtensionsDeep

/**
 * [descriptor]'s schema with the REST-only `google.api.field_behavior`/gnostic OpenAPI annotations
 * stripped from its own file and recursively every file it imports.
 */
class RegistrySanitizedSchema(
    descriptor: Descriptor,
    droppedImports: Set<String> = DROPPED_IMPORTS,
    droppedExtensionNumbers: Set<Int> = DROPPED_EXTENSION_NUMBERS,
) {
    val messageName: String = descriptor.name

    val file: FileDescriptor = descriptor.file.withoutFieldOptionExtensionsDeep(droppedImports, droppedExtensionNumbers)

    val schema: ProtobufSchema = ProtobufSchema(file.findMessageType(descriptor))

    companion object {
        /** `google.api.field_behavior` and `gnostic.openapi.v3.property`, as FieldOptions extension numbers. */
        val DROPPED_EXTENSION_NUMBERS = setOf(1052, 1143)

        val DROPPED_IMPORTS =
            setOf(
                "google/api/field_behavior.proto",
                "gnostic/openapi/v3/annotations.proto",
            )
    }
}

/**
 * The message in this (sanitized) file at [original]'s nesting path. [FileDescriptor.findMessageTypeByName] only
 * resolves top-level messages.
 */
private fun FileDescriptor.findMessageType(original: Descriptor): Descriptor {
    val path = generateSequence(original) { it.containingType }.map { it.name }.toList().asReversed()
    var current = checkNotNull(findMessageTypeByName(path.first())) { "No top-level message named ${path.first()} in $name" }
    for (name in path.drop(1)) {
        current = checkNotNull(current.findNestedTypeByName(name)) { "No nested message named $name in ${current.fullName}" }
    }
    return current
}
