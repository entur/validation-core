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
 * Adds the responses an rpc's `(entur.http.v1.failure)` method options declare, and the OpenAPI
 * schema for the message those responses `$ref` (`entur.http.v1.ProblemDetail`), to a
 * gnostic-generated openapi.yaml. gnostic has no mechanism of its own for this: a message is only
 * turned into a `components.schemas` entry when it's reachable from some rpc's actual
 * request/response type, which an HTTP-transcoding-layer error type like ProblemDetail never is,
 * and gnostic's own extension surface (`gnostic.openapi.v3.operation`) can't be extended with a
 * terser, repo-specific option since it's a plain proto3 message with no extension range.
 *
 * Works entirely off a `google.protobuf.FileDescriptorSet` (`buf build --as-file-descriptor-set`),
 * using `DynamicMessage`/`ExtensionRegistry` reflection rather than generated Java classes for the
 * `failure`/`field_behavior` extensions or the schema message - so this has no dependency on any
 * particular repo's generated code and can be copied into another buf-based repo as-is.
 *
 * Deliberately has no Gradle API dependency (see [AugmentOpenApiWithFailures] for the task that
 * wraps this), so its behavior can be exercised directly against a small fixture descriptor set in
 * a plain unit test.
 */
class OpenApiFailureAugmenter {
    fun augment(
        fileDescriptorSet: DescriptorProtos.FileDescriptorSet,
        openApiYaml: String,
    ): String {
        val files = buildFileDescriptors(fileDescriptorSet)
        val registry = buildExtensionRegistry(files)

        val failureExt = findExtension(files, "entur.http.v1.failure")
        val fieldBehaviorExt = findExtension(files, "google.api.field_behavior")

        val failuresByOperationId = collectFailures(files, failureExt, registry)

        val yaml = newYaml()
        val spec: MutableMap<String, Any?> = yaml.load(openApiYaml)

        val components = yamlMap(spec["components"])
        val schemas = yamlMap(components["schemas"])
        schemas["ProblemDetail"] = buildSchema(files, "entur.http.v1.ProblemDetail", fieldBehaviorExt, registry)
        components["schemas"] = schemas.toSortedMap()

        for (pathItem in yamlMap(spec["paths"]).values) {
            val operations = yamlMap(pathItem)
            for (operationKey in operations.keys) {
                val operation = yamlMapOrNull(operations[operationKey]) ?: continue
                val operationId = operation["operationId"] as? String ?: continue
                val failures = failuresByOperationId[operationId] ?: continue

                val responses = yamlMap(operation["responses"])
                for (failure in failures) {
                    responses[failure.code] =
                        linkedMapOf(
                            "description" to failure.description,
                            "content" to
                                linkedMapOf(
                                    "application/problem+json" to
                                        linkedMapOf(
                                            "schema" to linkedMapOf("\$ref" to "#/components/schemas/ProblemDetail"),
                                        ),
                                ),
                        )
                }
            }
        }

        return buildString {
            append("# Generated with protoc-gen-openapi and OpenApiFailureAugmenter\n")
            append("# https://github.com/google/gnostic/tree/master/cmd/protoc-gen-openapi\n")
            append("# https://github.com/entur/validation-core/tree/master/buildSrc/src/main/kotlin/OpenApiFailureAugmenter.kt\n\n")
            append(yaml.dump(spec))
        }
    }

    private data class Failure(val code: String, val description: String)

    private fun buildFileDescriptors(
        fileDescriptorSet: DescriptorProtos.FileDescriptorSet,
    ): Map<String, Descriptors.FileDescriptor> {
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

    private fun buildExtensionRegistry(files: Map<String, Descriptors.FileDescriptor>): ExtensionRegistry {
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

    private fun findExtension(
        files: Map<String, Descriptors.FileDescriptor>,
        fullName: String,
    ): Descriptors.FieldDescriptor =
        files.values.asSequence()
            .flatMap { it.extensions.asSequence() + it.messageTypes.asSequence().flatMap { m -> m.extensions.asSequence() } }
            .firstOrNull { it.fullName == fullName }
            ?: error("Extension '$fullName' not found in the descriptor set")

    private fun collectFailures(
        files: Map<String, Descriptors.FileDescriptor>,
        failureExt: Descriptors.FieldDescriptor,
        registry: ExtensionRegistry,
    ): Map<String, List<Failure>> {
        val result = LinkedHashMap<String, List<Failure>>()
        for (file in files.values) {
            for (service in file.services) {
                for (method in service.methods) {
                    val reparsed = DescriptorProtos.MethodOptions.parseFrom(method.options.toByteString(), registry)
                    val failureMessages = reparsed.repeatedField<Message>(failureExt)
                    if (failureMessages.isEmpty()) continue
                    val operationId = "${service.name}_${method.name}"
                    result[operationId] =
                        failureMessages.map { msg ->
                            val descriptor = msg.descriptorForType
                            Failure(
                                code = msg.getField(descriptor.findFieldByName("code")) as String,
                                description = msg.getField(descriptor.findFieldByName("description")) as String,
                            )
                        }
                }
            }
        }
        return result
    }

    private fun buildSchema(
        files: Map<String, Descriptors.FileDescriptor>,
        messageFullName: String,
        fieldBehaviorExt: Descriptors.FieldDescriptor,
        registry: ExtensionRegistry,
    ): Map<String, Any?> {
        val file = files.values.first { it.messageTypes.any { m -> m.fullName == messageFullName } }
        val messageProto = file.toProto()
        val messageIndex = messageProto.messageTypeList.indexOfFirst { it.name == messageFullName.substringAfterLast('.') }
        val message = file.findMessageTypeByName(messageProto.messageTypeList[messageIndex].name)

        val comments = HashMap<List<Int>, String>()
        for (location in messageProto.sourceCodeInfo.locationList) {
            if (location.hasLeadingComments()) {
                comments[location.pathList] = location.leadingComments.trim()
            }
        }

        val properties = LinkedHashMap<String, Any?>()
        val required = mutableListOf<String>()
        for (field in message.fields) {
            val path = listOf(4, messageIndex, 2, field.index)
            val (type, format) = jsonSchemaType(field)
            val property = LinkedHashMap<String, Any?>()
            property["type"] = type
            if (format != null) property["format"] = format
            comments[path]?.let { property["description"] = it }
            properties[field.name] = property

            val rawFieldOptions = field.toProto().options
            val reparsed = DescriptorProtos.FieldOptions.parseFrom(rawFieldOptions.toByteString(), registry)
            val behaviors = reparsed.repeatedField<Descriptors.EnumValueDescriptor>(fieldBehaviorExt)
            if (behaviors.any { it.name == "REQUIRED" }) required += field.name
        }

        val schema = LinkedHashMap<String, Any?>()
        comments[listOf(4, messageIndex)]?.let { schema["description"] = it }
        if (required.isNotEmpty()) schema["required"] = required
        schema["properties"] = properties
        return schema
    }

    private fun jsonSchemaType(field: Descriptors.FieldDescriptor): Pair<String, String?> =
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

    // Block style throughout, and multi-line strings (proto comments, failure descriptions) as
    // literal blocks (`|`) rather than one line with escaped `\n`s.
    private fun newYaml(): Yaml {
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

    // SnakeYAML's object graph is untyped (Object/LinkedHashMap), so recovering the
    // OpenAPI-shaped Map<String, Any?> it's known to contain needs a cast that erasure can't
    // verify.
    @Suppress("UNCHECKED_CAST")
    private fun yamlMap(value: Any?): MutableMap<String, Any?> = value as MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun yamlMapOrNull(value: Any?): MutableMap<String, Any?>? = value as? MutableMap<String, Any?>

    // getField()'s declared return type is plain Object; reified T lets filterIsInstance verify
    // every element at runtime instead of trusting an unchecked cast to List<T>.
    private inline fun <reified T> Message.repeatedField(field: Descriptors.FieldDescriptor): List<T> =
        (getField(field) as List<*>).filterIsInstance<T>()
}
