package no.entur.http

import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.Message

/**
 * Adds the responses an rpc's `(entur.http.v1.failure)` method options declare and the OpenAPI
 * schema for the message those responses `$ref` (`entur.http.v1.ProblemDetail`) to a
 * gnostic-generated openapi.yaml.
 */
class OpenApiFailureAugmenter {
    fun augment(
        files: Collection<Descriptors.FileDescriptor>,
        registry: ExtensionRegistry,
        spec: MutableMap<String, Any?>,
    ) {
        val failuresByOperationId = collectFailures(files, registry)
        val components = yamlMap(spec["components"])
        val schemas = yamlMap(components["schemas"])
        schemas.putAll(buildSchemas(files, registry))
        components["schemas"] = schemas.toSortedMap()

        for (pathItem in yamlMap(spec["paths"]).values) {
            val operations = yamlMap(pathItem)
            for (operationKey in operations.keys) {
                val operation = yamlMapOrNull(operations[operationKey]) ?: continue
                val operationId = operation["operationId"] as? String ?: continue
                val failures = failuresByOperationId[operationId] ?: continue

                val responses = yamlMap(operation["responses"])
                for (failure in failures) {
                    check(failure.code !in responses) {
                        "Operation '$operationId' response '${failure.code}' from (entur.http.v1.failure) collides with an existing response in the generated spec"
                    }
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
    }

    private data class Failure(val code: String, val description: String)

    // OpenAPI allows "default" and range patterns ("4XX") as response keys too, but
    // (entur.http.v1.failure) only ever documents one specific, literal status code.
    private val HTTP_STATUS_CODE = Regex("^[1-5]\\d{2}$")

    // descriptor.proto's own field numbers, used to build SourceCodeInfo.Location.path entries -
    // documented there as alternating (field number, index) pairs locating a declaration in the
    // file: FileDescriptorProto.message_type = 4, DescriptorProto.field = 2.
    private val MESSAGE_TYPE_FIELD_NUMBER = 4
    private val FIELD_FIELD_NUMBER = 2

    private fun collectFailures(
        files: Collection<Descriptors.FileDescriptor>,
        registry: ExtensionRegistry,
    ): Map<String, List<Failure>> {
        val failureExt = registry.findImmutableExtensionByName("entur.http.v1.failure").descriptor
        val result = LinkedHashMap<String, List<Failure>>()
        val duplicateErrors = mutableListOf<String>()
        files.flatMap { it.services }
            .forEach { service ->
                for (method in service.methods) {
                    val reparsed = DescriptorProtos.MethodOptions.parseFrom(method.options.toByteString(), registry)
                    val failureMessages = reparsed.repeatedField<Message>(failureExt)
                    if (failureMessages.isEmpty()) continue
                    val operationId = "${service.name}_${method.name}"
                    val failures =
                        failureMessages.map { msg ->
                            val descriptor = msg.descriptorForType
                            Failure(
                                code = msg.getField(descriptor.findFieldByName("code")) as String,
                                description = msg.getField(descriptor.findFieldByName("description")) as String,
                            )
                        }
                    for ((code) in failures) {
                        require(HTTP_STATUS_CODE.matches(code)) {
                            "Operation '$operationId' declares (entur.http.v1.failure) with invalid code '$code' - must be a 3-digit HTTP status code"
                        }
                    }
                    val duplicateCodes = failures.groupingBy { it.code }.eachCount().filterValues { it > 1 }.keys
                    if (duplicateCodes.isNotEmpty()) {
                        duplicateErrors +=
                            "Operation '$operationId' declares (entur.http.v1.failure) code(s) ${duplicateCodes.joinToString { "'$it'" }} more than once"
                    }
                    result[operationId] = failures
                }
            }
        // Collected all failures, so a single build failure surfaces every rpc that needs
        // fixing instead of just the first one hit during iteration.
        check(duplicateErrors.isEmpty()) { duplicateErrors.joinToString("\n") }
        return result
    }

    /**
     * [buildSchema] for "entur.http.v1.ProblemDetail", plus - transitively - every message type any of its
     * fields and sub-fields refs, keyed by short name.
     */
    private fun buildSchemas(
        files: Collection<Descriptors.FileDescriptor>,
        registry: ExtensionRegistry,
    ): Map<String, Map<String, Any>> {
        val result = LinkedHashMap<String, Map<String, Any>>()
        val queue = ArrayDeque(listOf("entur.http.v1.ProblemDetail"))
        val queued = mutableSetOf("entur.http.v1.ProblemDetail")
        while (queue.isNotEmpty()) {
            val fullName = queue.removeFirst()
            val (schema, referencedMessages) = buildSchema(files, fullName, registry)
            result[fullName.substringAfterLast('.')] = schema
            for (referenced in referencedMessages) {
                if (queued.add(referenced)) queue.addLast(referenced)
            }
        }
        return result
    }

    /**
     * [messageFullName]'s own schema, plus the full name of every message-typed field it declares.
     * */
    private fun buildSchema(
        files: Collection<Descriptors.FileDescriptor>,
        messageFullName: String,
        registry: ExtensionRegistry,
    ): Pair<Map<String, Any>, List<String>> {
        val fieldBehaviorExt = registry.findImmutableExtensionByName("google.api.field_behavior").descriptor
        val file = files.first { it.messageTypes.any { m -> m.fullName == messageFullName } }
        val messageProto = file.toProto()
        val messageIndex =
            messageProto.messageTypeList.indexOfFirst { it.name == messageFullName.substringAfterLast('.') }
        val message = file.findMessageTypeByName(messageProto.messageTypeList[messageIndex].name)

        val comments = messageProto.sourceCodeInfo.locationList
            .filter { it.hasLeadingComments() }
            .associateBy ({ it.pathList }, { it.leadingComments.trim() })

        val properties = LinkedHashMap<String, Any>()
        val required = mutableListOf<String>()
        val referencedMessages = mutableListOf<String>()
        for (field in message.fields) {
            val path = listOf(MESSAGE_TYPE_FIELD_NUMBER, messageIndex, FIELD_FIELD_NUMBER, field.index)
            val property = fieldSchema(field, referencedMessages)
            comments[path]?.let { property["description"] = it }
            properties[field.name] = property

            val rawFieldOptions = field.toProto().options
            val reparsed = DescriptorProtos.FieldOptions.parseFrom(rawFieldOptions.toByteString(), registry)
            val behaviors = reparsed.repeatedField<Descriptors.EnumValueDescriptor>(fieldBehaviorExt)
            if (behaviors.any { it.name == "REQUIRED" }) required += field.name
        }

        val schema = LinkedHashMap<String, Any>()
        comments[listOf(MESSAGE_TYPE_FIELD_NUMBER, messageIndex)]?.let { schema["description"] = it }
        if (required.isNotEmpty()) schema["required"] = required
        schema["type"] = "object"
        schema["properties"] = properties
        return schema to referencedMessages
    }

    /**
     * One field's own schema fragment. A message-typed field `$ref`s its type's own
     * `components.schemas` entry (appending its full name to [referencedMessages] for the caller to
     * recurse into) - wrapped in `allOf` for a singular field, since a bare `$ref` can't carry a
     * sibling `description` in OpenAPI 3.0; a `repeated` field of either kind wraps in a `type:
     * array` envelope instead, same as gnostic's own output for a `repeated` message field
     * elsewhere in the spec.
     */
    private fun fieldSchema(
        field: Descriptors.FieldDescriptor,
        referencedMessages: MutableList<String>,
    ): LinkedHashMap<String, Any> {
        val single: LinkedHashMap<String, Any>
        if (field.javaType == Descriptors.FieldDescriptor.JavaType.MESSAGE) {
            referencedMessages += field.messageType.fullName
            single = linkedMapOf("\$ref" to "#/components/schemas/${field.messageType.name}")
        } else {
            val (type, format) = jsonSchemaType(field)
            single = linkedMapOf("type" to type)
            if (format != null) single["format"] = format
        }

        return when {
            field.isRepeated -> linkedMapOf("type" to "array", "items" to single)
            field.javaType == Descriptors.FieldDescriptor.JavaType.MESSAGE -> linkedMapOf("allOf" to listOf(single))
            else -> single
        }
    }
}
