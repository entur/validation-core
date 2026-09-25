package no.entur.http

import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.Message

/**
 * Adds the responses an rpc's `(entur.http.v1.failure)` method options declare, and the OpenAPI
 * schema for the message those responses `$ref` (`entur.http.v1.ProblemDetail`), to a
 * gnostic-generated openapi.yaml. gnostic has no mechanism of its own for this: a message is only
 * turned into a `components.schemas` entry when it's reachable from some rpc's actual
 * request/response type, which an HTTP-transcoding-layer error type like ProblemDetail never is,
 * and gnostic's own extension surface (`gnostic.openapi.v3.operation`) can't be extended with a
 * terser, repo-specific option since it's a plain proto3 message with no extension range.
 */
class OpenApiFailureAugmenter {
    fun augment(
        files: Map<String, Descriptors.FileDescriptor>,
        registry: ExtensionRegistry,
        spec: MutableMap<String, Any?>,
    ) {
        val failuresByOperationId = collectFailures(files, findExtension(files, "entur.http.v1.failure"), registry)
        val components = yamlMap(spec["components"])
        val schemas = yamlMap(components["schemas"])
        schemas.putAll(
            buildSchemas(
                files,
                "entur.http.v1.ProblemDetail",
                findExtension(files, "google.api.field_behavior"),
                registry,
            ),
        )
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

    private fun collectFailures(
        files: Map<String, Descriptors.FileDescriptor>,
        failureExt: Descriptors.FieldDescriptor,
        registry: ExtensionRegistry,
    ): Map<String, List<Failure>> {
        val result = LinkedHashMap<String, List<Failure>>()
        val duplicateErrors = mutableListOf<String>()
        for (file in files.values) {
            for (service in file.services) {
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
                    for (failure in failures) {
                        require(HTTP_STATUS_CODE.matches(failure.code)) {
                            "Operation '$operationId' declares (entur.http.v1.failure) with invalid code '${failure.code}' - must be a 3-digit HTTP status code"
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
        }
        // Collected across every operation before failing, rather than stopping at the first one
        // found, so a single build failure surfaces every rpc that needs fixing instead of just
        // the first one hit during iteration.
        check(duplicateErrors.isEmpty()) { duplicateErrors.joinToString("\n") }
        return result
    }

    /**
     * [buildSchema] for [rootMessageFullName], plus - transitively - every message type any of its
     * fields (or its fields' fields, ...) refs, keyed by short name. A message-typed field like
     * `ProblemDetail.errors` (`repeated FieldViolation`) only renders as a valid `$ref` if the
     * referenced message has its own `components.schemas` entry too; gnostic never contributes one
     * for a type unreachable from any rpc's request/response, which is exactly what every message
     * reachable from here is.
     */
    private fun buildSchemas(
        files: Map<String, Descriptors.FileDescriptor>,
        rootMessageFullName: String,
        fieldBehaviorExt: Descriptors.FieldDescriptor,
        registry: ExtensionRegistry,
    ): Map<String, Map<String, Any?>> {
        val result = LinkedHashMap<String, Map<String, Any?>>()
        val queue = ArrayDeque(listOf(rootMessageFullName))
        val queued = mutableSetOf(rootMessageFullName)
        while (queue.isNotEmpty()) {
            val fullName = queue.removeFirst()
            val (schema, referencedMessages) = buildSchema(files, fullName, fieldBehaviorExt, registry)
            result[fullName.substringAfterLast('.')] = schema
            for (referenced in referencedMessages) {
                if (queued.add(referenced)) queue.addLast(referenced)
            }
        }
        return result
    }

    /** [messageFullName]'s own schema, plus the full name of every message-typed field it declares (for [buildSchemas] to recurse into). */
    private fun buildSchema(
        files: Map<String, Descriptors.FileDescriptor>,
        messageFullName: String,
        fieldBehaviorExt: Descriptors.FieldDescriptor,
        registry: ExtensionRegistry,
    ): Pair<Map<String, Any?>, List<String>> {
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
        val referencedMessages = mutableListOf<String>()
        for (field in message.fields) {
            val path = listOf(4, messageIndex, 2, field.index)
            val property = fieldSchema(field, referencedMessages)
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
    ): LinkedHashMap<String, Any?> {
        val single: LinkedHashMap<String, Any?>
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
}
