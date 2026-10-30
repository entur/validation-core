package no.entur.http

import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.Message
import org.gradle.internal.impldep.com.google.common.collect.Maps
import org.yaml.snakeyaml.Yaml

/**
 * Adds a whole-object `example` to every `components.schemas` entry of a gnostic-generated openapi.yaml,
 * built from each field's own `(gnostic.openapi.v3.property).example` option.
 *
 * gnostic itself already copies a field's own `example` option onto that field's property schema for any message
 * reachable from an rpc's request/response type. But it has no equivalent for the message as a  whole, and no
 * mechanism at all for a message that isn't reachable that way, so this class reads the same per-field option directly
 * off the descriptors for every schema, and assembles one example object per schema from the result.
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
     * One example object per `components.schemas` entry's own `properties`, recursing through a `$ref`,
     * a `repeated` field's array wrapper, or a `map<string, ...>` field's `additionalProperties` wrapper via
     * [resolve]. A field with no example anywhere in its chain, or a cycle is simply left out of the result.
     *
     * [oneofFieldGroups] is the message's own `oneof` groups (as field-name sets). A real instance can only ever
     * populate one member of each, so only the first field declared in each group is considered;
     * whether or not that first field ends up with a usable example of its own.
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
            // A protobuf `map<string, ...>` field: gnostic renders it as `additionalProperties`
            // rather than a `$ref`/`properties` schema of its own, so there's no real key to put in
            // the example - "additionalProp1" mirrors Swagger UI's own placeholder for the same case.
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
     * Every message's declared fields, each mapped to the value its own `(gnostic.openapi.v3.property).example`
     * option declares - if it has one.
     */
    private fun collectFieldExamples(
        files: Collection<Descriptors.FileDescriptor>,
        registry: ExtensionRegistry,
    ): Map<String, Map<String, Any>> {
        val propertyExt = registry.findImmutableExtensionByName("gnostic.openapi.v3.property").descriptor
        val result = LinkedHashMap<String, Map<String, Any>>()
        files.flatMap { it.messageTypes }
            .forEach {
                val fieldExamples = LinkedHashMap<String, Any>()
                for (field in it.fields) {
                    val reparsed =
                        DescriptorProtos.FieldOptions.parseFrom(field.toProto().options.toByteString(), registry)
                    // Keyed by jsonName (camelCase), not the proto field's own snake_case name, to match the
                    // property names gnostic itself renders into components.schemas.*.properties.
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

    /**
     * Each name in [schemaNames] mapped to the full name of the one message declared anywhere in
     * [files] with that short (unqualified) name, for matching a `components.schemas` key back to the descriptor it
     * came from. Only [schemaNames] are resolved.
     */
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
     * Each message's own `oneof` groups, as a list of field-name (jsonName) sets. Uses
     * [Descriptors.FieldDescriptor.getRealContainingOneof] to avoid picking up a proto3 `optional`
     * field's own synthetic single-field.
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
        return Maps.filterValues(result) { it.isNotEmpty() }
    }
}
