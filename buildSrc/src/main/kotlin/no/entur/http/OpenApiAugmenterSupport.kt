package no.entur.http

import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.Message
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Represent
import org.yaml.snakeyaml.representer.Representer

/**
 * Reflection and YAML plumbing shared by various augmenters. Augmenters post-process a gnostic-generated openapi.yaml
 * against a `google.protobuf.FileDescriptorSet` (`buf build --as-file-descriptor-set`), using
 * `DynamicMessage`/`ExtensionRegistry` reflection. So none of this has a dependency on any particular repo's generated
 * code and the whole file can be copied into another buf-based repo alongside either of them.
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

fun buildExtensionRegistry(files: Map<String, Descriptors.FileDescriptor>): ExtensionRegistry {
    val registry = ExtensionRegistry.newInstance()
    for (file in files.values) {
        val allExtensions = file.extensions + file.messageTypes.flatMap { it.extensions }
        for (ext in allExtensions) {
            if (ext.javaType == Descriptors.FieldDescriptor.JavaType.MESSAGE) {
                registry.add(ext, DynamicMessage.getDefaultInstance(ext.messageType))
            } else {
                registry.add(ext)
            }
        }
    }
    return registry
}

fun findExtension(
    files: Map<String, Descriptors.FileDescriptor>,
    fullName: String,
): Descriptors.FieldDescriptor =
    files.values.asSequence()
        .flatMap { it.extensions.asSequence() + it.messageTypes.asSequence().flatMap { m -> m.extensions.asSequence() } }
        .firstOrNull { it.fullName == fullName }
        ?: error("Extension '$fullName' not found in the descriptor set")

// getField()'s declared return type is plain Object; reified T lets filterIsInstance verify every
// element at runtime instead of trusting an unchecked cast to List<T>.
inline fun <reified T> Message.repeatedField(field: Descriptors.FieldDescriptor): List<T> =
    (getField(field) as List<*>).filterIsInstance<T>()

// Block style throughout, and multi-line strings as literal blocks (`|`) rather than one line with
// escaped `\n`s.
fun newOpenApiYaml(): Yaml {
    val options =
        DumperOptions().apply {
            indent = 4
            indicatorIndent = 2
            indentWithIndicator = false
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        }
    val representer =
        object : Representer(options) {
            init {
                representers[String::class.java] =
                    Represent { data ->
                        val s = data as String
                        if (s.contains('\n')) {
                            representScalar(Tag.STR, s, DumperOptions.ScalarStyle.LITERAL)
                        } else {
                            representScalar(Tag.STR, s)
                        }
                    }
            }
        }
    return Yaml(representer, options)
}

// SnakeYAML's object graph is untyped (Object/LinkedHashMap), so recovering the OpenAPI-shaped
// Map<String, Any?> it's known to contain needs a cast that erasure can't verify.
@Suppress("UNCHECKED_CAST")
fun yamlMap(value: Any?): MutableMap<String, Any?> = value as MutableMap<String, Any?>

@Suppress("UNCHECKED_CAST")
fun yamlMapOrNull(value: Any?): MutableMap<String, Any?>? = value as? MutableMap<String, Any?>
