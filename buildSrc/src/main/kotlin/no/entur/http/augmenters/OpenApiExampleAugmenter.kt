package no.entur.http.augmenters

import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.Message
import no.entur.http.deepCopy
import no.entur.http.requireExtension
import no.entur.http.yamlMap
import no.entur.http.yamlMapOrNull
import org.yaml.snakeyaml.Yaml

/**
 * Adds a whole-object `example` to every `components.schemas` entry, built from the fields'
 * `(gnostic.openapi.v3.property).example` options. gnostic only sets per-field examples, and only on
 * messages reachable from an rpc.
 */
class OpenApiExampleAugmenter {
    fun augment(
        files: Collection<Descriptors.FileDescriptor>,
        registry: ExtensionRegistry,
        spec: MutableMap<String, Any?>,
    ) {
        val examplesByMessage = collectFieldExamples(files, registry)

        val schemas = yamlMap(yamlMap(spec["components"])["schemas"])
        val messageFullNameByShortName = indexMessagesByShortName(files, schemas.keys)
        val oneofFieldGroupsByMessage = collectOneofFieldGroups(files)

        for (name in schemas.keys) {
            val fullName = messageFullNameByShortName[name] ?: continue
            val properties = yamlMapOrNull(yamlMapOrNull(schemas[name])?.get("properties")) ?: continue
            val fieldExamples = examplesByMessage[fullName] ?: continue
            for ((fieldName, example) in fieldExamples) {
                val property = yamlMapOrNull(properties[fieldName]) ?: continue
                property.putIfAbsent("example", example)
            }
        }

        val building = mutableSetOf<String>()
        val built = HashMap<String, Any?>()
        fun exampleFor(name: String): Any? {
            if (name in built) return deepCopy(built[name])
            if (name in building) return null
            val schema = yamlMapOrNull(schemas[name]) ?: return null
            val oneofFieldGroups =
                messageFullNameByShortName[name]?.let { oneofFieldGroupsByMessage[it] } ?: emptyList()
            building += name
            val example = buildObjectExample(schema, oneofFieldGroups, ::exampleFor)
            building -= name
            built[name] = example
            return example
        }

        for (name in schemas.keys) {
            val schema = yamlMap(schemas[name])
            if ("example" in schema) continue
            val example = exampleFor(name) ?: continue
            schema["example"] = example
        }
    }

    /**
     * Builds one example object from [schema]'s properties, following `$ref`s, arrays and maps through [resolve].
     * Fields without an example, or on a cycle, are left out. Only the first field of each `oneof` in
     * [oneofFieldGroups] is used, since a real instance sets just one.
     */
    private fun buildObjectExample(
        schema: Map<String, Any?>,
        oneofFieldGroups: List<Set<String>>,
        resolve: (String) -> Any?,
    ): Map<String, Any>? {
        val properties = yamlMapOrNull(schema["properties"]) ?: return null
        val result = LinkedHashMap<String, Any>()
        val chosenOneofFieldGroups = mutableSetOf<Set<String>>()
        for ((fieldName, propertySchemaAny) in properties) {
            val oneofFieldGroup = oneofFieldGroups.firstOrNull { fieldName in it }
            if (oneofFieldGroup != null && !chosenOneofFieldGroups.add(oneofFieldGroup)) continue
            val propertySchema = yamlMapOrNull(propertySchemaAny) ?: continue
            val value = propertyExampleValue(propertySchema, resolve) ?: continue
            result[fieldName] = value
        }
        return result.ifEmpty { null }
    }

    private fun propertyExampleValue(
        propertySchema: Map<String, Any?>,
        resolve: (String) -> Any?,
    ): Any? {
        propertySchema["example"]?.let { return it }
        refName(propertySchema)?.let { return resolve(it) }
        allOfRefName(propertySchema)?.let { return resolve(it) }
        if (propertySchema["type"] == "array") {
            val items = yamlMapOrNull(propertySchema["items"]) ?: return null
            val itemValue = items["example"] ?: refName(items)?.let(resolve) ?: return null
            return listOf(itemValue)
        }
        if (propertySchema["type"] == "object") {
            // A `map<string, ...>` field has no real key to show; "additionalProp1" is Swagger UI's placeholder.
            val additionalProperties = yamlMapOrNull(propertySchema["additionalProperties"]) ?: return null
            val valueExample =
                additionalProperties["example"] ?: refName(additionalProperties)?.let(resolve) ?: return null
            return mapOf("additionalProp1" to valueExample)
        }
        return null
    }

    private fun refName(schema: Map<String, Any?>): String? =
        (schema["\$ref"] as? String)?.substringAfterLast('/')

    private fun allOfRefName(schema: Map<String, Any?>): String? {
        val allOf = schema["allOf"] as? List<*> ?: return null
        return allOf.asSequence().mapNotNull { yamlMapOrNull(it)?.let(::refName) }.firstOrNull()
    }

    /**
     * Each message's fields mapped to their `(gnostic.openapi.v3.property).example` values, for the fields that
     * declare one.
     */
    private fun collectFieldExamples(
        files: Collection<Descriptors.FileDescriptor>,
        registry: ExtensionRegistry,
    ): Map<String, Map<String, Any>> {
        val propertyExt = registry.requireExtension("gnostic.openapi.v3.property")
        val result = LinkedHashMap<String, Map<String, Any>>()
        files.flatMap { it.messageTypes }
            .forEach {
                val fieldExamples = LinkedHashMap<String, Any>()
                for (field in it.fields) {
                    val reparsed =
                        DescriptorProtos.FieldOptions.parseFrom(field.toProto().options.toByteString(), registry)
                    // jsonName, to match gnostic's property names.
                    fieldExample(reparsed, propertyExt)?.let { fieldExamples[field.jsonName] = it }
                }
                if (fieldExamples.isNotEmpty()) result[it.fullName] = fieldExamples
            }
        return result
    }

    private fun fieldExample(
        fieldOptions: DescriptorProtos.FieldOptions,
        propertyExt: Descriptors.FieldDescriptor,
    ): Any? {
        if (!fieldOptions.hasField(propertyExt)) return null
        val property = fieldOptions.getField(propertyExt) as Message
        val exampleField = property.descriptorForType.findFieldByName("example") ?: return null
        if (!property.hasField(exampleField)) return null
        val example = property.getField(exampleField) as Message
        val yamlField = example.descriptorForType.findFieldByName("yaml") ?: return null
        val yamlText = example.getField(yamlField) as? String
        return yamlText?.takeIf { it.isNotEmpty() }?.let { Yaml().load(it) }
    }

    /** Maps each `components.schemas` key (a short message name) to the message's full name. */
    private fun indexMessagesByShortName(
        files: Collection<Descriptors.FileDescriptor>,
        schemaNames: Set<String>,
    ): Map<String, String> {
        val messagesByShortName = files.flatMap { it.messageTypes }.groupBy { it.name }
        return schemaNames
            .filter { messagesByShortName.containsKey(it) }
            .associateWith { name ->
                val candidates = messagesByShortName[name]!!
                check(candidates.size == 1) {
                    "Schema '$name' matches more than one message by short name - " +
                            "OpenApiExampleAugmenter can't tell which one it refers to: " +
                            candidates.joinToString { it.fullName }
                }
                candidates.single().fullName
            }
    }

    /**
     * Each message's `oneof` groups, as sets of field JSON names. Real oneofs only: a proto3 `optional` field's
     * synthetic oneof is skipped.
     */
    private fun collectOneofFieldGroups(files: Collection<Descriptors.FileDescriptor>): Map<String, List<Set<String>>> {
        val result = files.flatMap { it.messageTypes }
            .associateBy(
                { it.fullName },
                { message ->
                    message.fields
                        .mapNotNull { it.realContainingOneof }
                        .distinct()
                        .map { oneof -> oneof.fields.map { it.jsonName }.toSet() }
                },
            )
        return result.filterValues { it.isNotEmpty() }
    }
}
