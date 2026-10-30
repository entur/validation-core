package no.entur.proto.schema

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FieldOptions
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.UnknownFieldSet

/**
 * A copy of this file's descriptor with [droppedImports] removed from its `import` statements, and
 * [droppedExtensionNumbers] removed from every field's options - for registering a schema with a
 * system that has no use for annotations meant for a different consumer.
 *
 * (A message can be both a REST resource (needing e.g. `google.api.field_behavior`/gnostic OpenAPI
 * annotations for spec generation) and a Kafka record (needing none of that) at once; proto3 has no
 * way to attach those annotations from anywhere but the field itself, so the live descriptor always
 * carries both. This produces a reduced projection without touching the canonical `.proto` source,
 * so a registry that has no permission to create subjects for third-party imports like
 * `gnostic/openapi/v3/annotations.proto` never needs to see them.)
 *
 * [droppedExtensionNumbers] are raw field numbers, not typed extension descriptors: an extension
 * declared by a `.proto` file with no corresponding generated Java/Kotlin class on this classpath
 * has no typed accessor to clear by. Every extension known to this JVM or not, is still just a field
 * number on the wire, which [withoutFieldOptionExtensions] strips either way.
 *
 * Only field-level options are touched. A caller dropping an import that's still relied on by some
 * file-, message- or method-level option ends up with a descriptor that fails to build - that failure
 * is the signal the dropped set was wrong, not something this function tries to detect up front.
 */
fun FileDescriptor.withoutFieldOptionExtensions(
    droppedImports: Set<String>,
    droppedExtensionNumbers: Set<Int>,
): FileDescriptorProto {
    val proto = toProto()
    return proto
        .toBuilder()
        .clearDependency()
        .addAllDependency(proto.dependencyList.filterNot { it in droppedImports })
        .clearMessageType()
        .addAllMessageType(proto.messageTypeList.map { it.withoutFieldOptionExtensions(droppedExtensionNumbers) })
        .build()
}

/**
 * [this] and every file it transitively depends on, each sanitized the same way
 * [withoutFieldOptionExtensions] sanitizes a single file.
 */
fun FileDescriptor.withoutFieldOptionExtensionsDeep(
    droppedImports: Set<String>,
    droppedExtensionNumbers: Set<Int>,
): FileDescriptor {
    val built = mutableMapOf<String, FileDescriptor>()

    fun sanitize(file: FileDescriptor): FileDescriptor =
        built.getOrPut(file.name) {
            val dependencies =
                file.dependencies
                    .filterNot { it.name in droppedImports }
                    .map(::sanitize)
                    .toTypedArray()
            FileDescriptor.buildFrom(
                file.withoutFieldOptionExtensions(droppedImports, droppedExtensionNumbers),
                dependencies,
            )
        }
    return sanitize(this)
}

private fun DescriptorProto.withoutFieldOptionExtensions(droppedExtensionNumbers: Set<Int>): DescriptorProto =
    toBuilder()
        .clearField()
        .addAllField(
            fieldList.map { field ->
                if (!field.hasOptions()) {
                    field
                } else {
                    field.toBuilder().setOptions(field.options.withoutExtensions(droppedExtensionNumbers)).build()
                }
            },
        ).clearNestedType()
        .addAllNestedType(nestedTypeList.map { it.withoutFieldOptionExtensions(droppedExtensionNumbers) })
        .build()

/**
 * Round-trips through [UnknownFieldSet] to drop [droppedExtensionNumbers] by raw field number. (On the wire,
 * a "known" option field and an unregistered extension are indistinguishable.)
 */
private fun FieldOptions.withoutExtensions(droppedExtensionNumbers: Set<Int>): FieldOptions {
    val kept = UnknownFieldSet.newBuilder()
    UnknownFieldSet.parseFrom(toByteString()).asMap().forEach { (number, field) ->
        if (number !in droppedExtensionNumbers) kept.addField(number, field)
    }
    return FieldOptions.parseFrom(kept.build().toByteString())
}
