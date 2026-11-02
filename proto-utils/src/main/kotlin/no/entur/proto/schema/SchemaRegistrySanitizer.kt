package no.entur.proto.schema

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FieldOptions
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.UnknownFieldSet

/**
 * This file's descriptor without [droppedImports] and without [droppedExtensionNumbers] on any field's
 * options. A message that is both a REST resource and a Kafka record carries REST-only annotations
 * (e.g. gnostic's), and the schema registry has no subject for third-party imports like
 * `gnostic/openapi/v3/annotations.proto`.
 *
 * Extensions are raw field numbers because an extension with no generated class on the classpath has
 * no typed descriptor. Only field options are touched: dropping an import still used elsewhere makes
 * the descriptor fail to build.
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

/** [withoutFieldOptionExtensions] applied to this file and everything it transitively imports. */
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

/** Via [UnknownFieldSet], since on the wire a known option and an unregistered extension look the same. */
private fun FieldOptions.withoutExtensions(droppedExtensionNumbers: Set<Int>): FieldOptions {
    val kept = UnknownFieldSet.newBuilder()
    UnknownFieldSet.parseFrom(toByteString()).asMap().forEach { (number, field) ->
        if (number !in droppedExtensionNumbers) kept.addField(number, field)
    }
    return FieldOptions.parseFrom(kept.build().toByteString())
}
