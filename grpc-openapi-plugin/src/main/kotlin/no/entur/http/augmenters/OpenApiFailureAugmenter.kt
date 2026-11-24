package no.entur.http.augmenters

import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.Message
import no.entur.http.jsonSchemaType
import no.entur.http.repeatedField
import no.entur.http.requireExtension
import no.entur.http.yamlMap
import no.entur.http.yamlMapOrNull

/** Adds the responses from each rpc's `(entur.http.v1.failure)` options, and the `ProblemDetail` schema they `$ref`. */
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

    private fun collectFailures(
        files: Collection<Descriptors.FileDescriptor>,
        registry: ExtensionRegistry,
    ): Map<String, List<Failure>> {
        val failureExt = registry.requireExtension("entur.http.v1.failure")
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
        // Collected rather than thrown on the first hit, so one failed build lists every rpc to fix
        // instead of one per rebuild.
        check(duplicateErrors.isEmpty()) { duplicateErrors.joinToString("\n") }
        return result
    }

    /** ProblemDetail's schema plus every message it references transitively, keyed by short name. */
    private fun buildSchemas(
        files: Collection<Descriptors.FileDescriptor>,
        registry: ExtensionRegistry,
    ): Map<String, Map<String, Any>> {
        val fieldBehaviorExt = registry.requireExtension("google.api.field_behavior")
        val result = LinkedHashMap<String, Map<String, Any>>()
        val queue = ArrayDeque(listOf("entur.http.v1.ProblemDetail"))
        val queued = mutableSetOf("entur.http.v1.ProblemDetail")
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

    /** [messageFullName]'s schema, plus the full name of every message type its fields reference. */
    private fun buildSchema(
        files: Collection<Descriptors.FileDescriptor>,
        messageFullName: String,
        fieldBehaviorExt: Descriptors.FieldDescriptor,
        registry: ExtensionRegistry,
    ): Pair<Map<String, Any>, List<String>> {
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
            // SourceCodeInfo.Location.path: alternating (field number, index) pairs, per descriptor.proto.
            val path =
                listOf(
                    DescriptorProtos.FileDescriptorProto.MESSAGE_TYPE_FIELD_NUMBER,
                    messageIndex,
                    DescriptorProtos.DescriptorProto.FIELD_FIELD_NUMBER,
                    field.index,
                )
            val property = fieldSchema(field, referencedMessages)
            comments[path]?.let { property["description"] = it }
            properties[field.name] = property

            val rawFieldOptions = field.toProto().options
            val reparsed = DescriptorProtos.FieldOptions.parseFrom(rawFieldOptions.toByteString(), registry)
            val behaviors = reparsed.repeatedField<Descriptors.EnumValueDescriptor>(fieldBehaviorExt)
            if (behaviors.any { it.name == "REQUIRED" }) required += field.name
        }

        val schema = LinkedHashMap<String, Any>()
        comments[listOf(DescriptorProtos.FileDescriptorProto.MESSAGE_TYPE_FIELD_NUMBER, messageIndex)]?.let {
            schema["description"] = it
        }
        if (required.isNotEmpty()) schema["required"] = required
        schema["type"] = "object"
        schema["properties"] = properties
        return schema to referencedMessages
    }

    /**
     * One field's schema fragment. A singular message field's `$ref` is wrapped in `allOf`, since OpenAPI 3.0 ignores
     * a `description` next to a bare `$ref`. Message types are appended to [referencedMessages] for the caller to
     * recurse into.
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
