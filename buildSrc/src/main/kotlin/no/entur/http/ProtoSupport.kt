package no.entur.http

import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.Message

/**
 * Finds all file descriptors in the given file descriptor set, recursively walking all imports. It always includes
 * "google/protobuf/descriptor.proto". Returns a map from file names to their file descriptor.
 */
fun buildFileDescriptors(fileDescriptorSet: DescriptorProtos.FileDescriptorSet): Map<String, Descriptors.FileDescriptor> {
    val protosByName = fileDescriptorSet.fileList.associateBy { it.name }
    val built = LinkedHashMap<String, Descriptors.FileDescriptor>()
    // Must be the actual compiled-in FileDescriptor protobuf-java's own DescriptorProtos.MethodOptions/
    // FieldOptions classes use, or extension FieldDescriptors built on top of a freshly-parsed copy
    // won't verify as belonging to those classes' message type when read back via reflection.
    built["google/protobuf/descriptor.proto"] = DescriptorProtos.getDescriptor()
    fun build(name: String): Descriptors.FileDescriptor =
        built.getOrPut(name) {
            val proto = protosByName.getValue(name)
            val deps = proto.dependencyList.map(::build).toTypedArray()
            Descriptors.FileDescriptor.buildFrom(proto, deps, true)
        }

    for (fileProto in fileDescriptorSet.fileList) {
        build(fileProto.name)
    }
    return built
}

/**
 * Builds an extension registry which spans all extensions defined in [fileDescriptors].
 */
fun buildExtensionRegistry(fileDescriptors: Collection<Descriptors.FileDescriptor>): ExtensionRegistry {
    val registry = ExtensionRegistry.newInstance()
    fileDescriptors.flatMap { it.extensions + it.messageTypes.flatMap { it.extensions } }
        .forEach {
            if (it.javaType == Descriptors.FieldDescriptor.JavaType.MESSAGE) {
                registry.add(it, DynamicMessage.getDefaultInstance(it.messageType))
            } else {
                registry.add(it)
            }
        }
    return registry
}

/**
 * Gets all instances of a specific repeated field as a properly typed list.
 */
inline fun <reified T> Message.repeatedField(field: Descriptors.FieldDescriptor): List<T> =
    // getField()'s declared return type is plain Object; reified T lets filterIsInstance verify every
    // element at runtime instead of trusting an unchecked cast to List<T>.
    (getField(field) as List<*>).filterIsInstance<T>()

/**
 * The JSON Schema `type`/`format` pair a protobuf field's own [Descriptors.FieldDescriptor.Type] maps to.
 */
fun jsonSchemaType(field: Descriptors.FieldDescriptor): Pair<String, String?> =
    when (field.type) {
        Descriptors.FieldDescriptor.Type.STRING -> "string" to null
        Descriptors.FieldDescriptor.Type.BOOL -> "boolean" to null
        Descriptors.FieldDescriptor.Type.INT32,
        Descriptors.FieldDescriptor.Type.SINT32,
        Descriptors.FieldDescriptor.Type.SFIXED32, -> "integer" to "int32"
        Descriptors.FieldDescriptor.Type.UINT32,
        Descriptors.FieldDescriptor.Type.FIXED32, -> "integer" to "uint32"
        Descriptors.FieldDescriptor.Type.INT64,
        Descriptors.FieldDescriptor.Type.SINT64,
        Descriptors.FieldDescriptor.Type.SFIXED64, -> "integer" to "int64"
        Descriptors.FieldDescriptor.Type.UINT64,
        Descriptors.FieldDescriptor.Type.FIXED64, -> "integer" to "uint64"
        Descriptors.FieldDescriptor.Type.FLOAT -> "number" to "float"
        Descriptors.FieldDescriptor.Type.DOUBLE -> "number" to "double"
        else -> "string" to null
    }

