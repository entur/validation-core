package no.entur.kafka.schema

import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.FileDescriptor
import io.confluent.kafka.schemaregistry.protobuf.ProtobufSchema
import no.entur.proto.schema.withoutFieldOptionExtensionsDeep

/**
 * [descriptor]'s schema with the REST-only `google.api.field_behavior`/gnostic OpenAPI annotations
 * stripped from its own file and, recursively, every file it imports - what registering a message
 * that is also a REST resource needs, since the schema registry has no subject (and this client no
 * permission) for a REST-only import like `gnostic/openapi/v3/annotations.proto`. The wire format
 * is unchanged; see [no.entur.proto.schema.withoutFieldOptionExtensionsDeep] for how.
 */
class RegistrySanitizedSchema(
    descriptor: Descriptor,
    droppedImports: Set<String> = DROPPED_IMPORTS,
    droppedExtensionNumbers: Set<Int> = DROPPED_EXTENSION_NUMBERS,
) {
    val file: FileDescriptor = descriptor.file.withoutFieldOptionExtensionsDeep(droppedImports, droppedExtensionNumbers)

    val schema: ProtobufSchema = ProtobufSchema(file.findMessageTypeByName(descriptor.name))

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
