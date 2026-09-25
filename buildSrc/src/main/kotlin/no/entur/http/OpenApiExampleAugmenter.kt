package no.entur.http

import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.Message
import org.yaml.snakeyaml.Yaml

/**
 * Adds a whole-object `example` to every `components.schemas` entry of a gnostic-generated openapi.yaml,
 * built from each field's own `(gnostic.openapi.v3.property).example` option - so Spectral's
 * `entur-response-body-examples`/`entur-request-body-examples` rules, which only look for an
 * example at the schema/content level rather than nested inside `properties`, stop flagging every
 * operation whose request/response body is a message `$ref`.
 *
 * gnostic itself already copies a field's own `example` option onto that field's property schema
 * for any message reachable from an rpc's request/response type - see the `example: 1` etc. already
 * present throughout the committed specs. It has no equivalent for the message as a whole, and no
 * mechanism at all for a message that isn't reachable that way (`entur.http.v1.ProblemDetail`,
 * `FieldViolation` - added to the spec by [OpenApiFailureAugmenter], not gnostic), so this reads
 * the same per-field option directly off the descriptors for every schema, rather than trusting
 * whatever a property happens to already carry, and assembles one example object per schema from
 * the result.
 */
class OpenApiExampleAugmenter {
    fun augment(
        files: Map<String, Descriptors.FileDescriptor>,
        registry: ExtensionRegistry,
        spec: MutableMap<String, Any?>,
    ) {
        val examplesByMessage =
            collectFieldExamples(files, findExtension(files, "gnostic.openapi.v3.property"), registry)

        val schemas = yamlMap(yamlMap(spec["components"])["schemas"])
        val messageFullNameByShortName = indexMessagesByShortName(files, schemas.keys)

        for (name in schemas.keys) {
            val fullName = messageFullNameByShortName[name] ?: continue
            val properties = yamlMapOrNull(yamlMapOrNull(schemas[name])?.get("properties")) ?: continue
            val fieldExamples = examplesByMessage[fullName] ?: continue
            for ((fieldName, example) in fieldExamples) {
                val property = yamlMapOrNull(properties[fieldName]) ?: continue
                property.putIfAbsent("example", example)
            }
        }

        // Every caller of exampleFor(name) - both the top-level loop below and buildObjectExample,
        // resolving a $ref'd field - embeds its result into its own, distinct Map. Returning the
        // cached Map/List instance itself a second time would let two unrelated places in the
        // spec end up holding the very same object, which SnakeYAML then renders as a YAML anchor
        // (&id001) at the first and an alias (*id001) at the rest, which is unwanted.
        val building = mutableSetOf<String>()
        val built = HashMap<String, Any?>()
        fun exampleFor(name: String): Any? {
            if (name in built) return deepCopy(built[name])
            if (name in building) return null
            val schema = yamlMapOrNull(schemas[name]) ?: return null
            building += name
            val example = buildObjectExample(schema, ::exampleFor)
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
     *  One example object per `components.schemas` entry's own `properties`, recursing through a `$ref`
     *  (bare or `allOf`-wrapped) or a `repeated` field's array wrapper via [resolve]. A field with no example anywhere
     *  in its chain, or a cycle is simply left out of the result, since a partial example is still a valid one.
     *  */
    private fun buildObjectExample(
        schema: Map<String, Any?>,
        resolve: (String) -> Any?,
    ): Map<String, Any?>? {
        val properties = yamlMapOrNull(schema["properties"]) ?: return null
        val result = LinkedHashMap<String, Any?>()
        for ((fieldName, propertySchemaAny) in properties) {
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
        return null
    }

    private fun deepCopy(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> value.entries.associateTo(LinkedHashMap()) { (k, v) -> k to deepCopy(v) }
            is List<*> -> value.map { deepCopy(it) }
            else -> value
        }

    private fun refName(schema: Map<String, Any?>): String? =
        (schema["\$ref"] as? String)?.substringAfterLast('/')

    private fun allOfRefName(schema: Map<String, Any?>): String? {
        val allOf = schema["allOf"] as? List<*> ?: return null
        return allOf.asSequence().mapNotNull { yamlMapOrNull(it)?.let(::refName) }.firstOrNull()
    }

    /**
     *  Every message's declared fields, each mapped to the value its own `(gnostic.openapi.v3.property).example`
     *  option declares - parsed from its raw YAML text - if it has one.
     *  */
    private fun collectFieldExamples(
        files: Map<String, Descriptors.FileDescriptor>,
        propertyExt: Descriptors.FieldDescriptor,
        registry: ExtensionRegistry,
    ): Map<String, Map<String, Any?>> {
        val result = LinkedHashMap<String, Map<String, Any?>>()
        for (file in files.values) {
            for (message in file.messageTypes) {
                val fieldExamples = LinkedHashMap<String, Any?>()
                for (field in message.fields) {
                    val reparsed =
                        DescriptorProtos.FieldOptions.parseFrom(field.toProto().options.toByteString(), registry)
                    fieldExample(reparsed, propertyExt)?.let { fieldExamples[field.name] = it }
                }
                if (fieldExamples.isNotEmpty()) result[message.fullName] = fieldExamples
            }
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
        val any = property.getField(exampleField) as Message
        val yamlField = any.descriptorForType.findFieldByName("yaml") ?: return null
        val yamlText = any.getField(yamlField) as? String
        return yamlText?.takeIf { it.isNotEmpty() }?.let { Yaml().load(it) }
    }

    /**
     * Each name in [schemaNames] mapped to the full name of the one message declared anywhere in
     * [files] with that short (unqualified) name, for matching a `components.schemas` key (gnostic
     * always keys by short name) back to the descriptor it came from. Only [schemaNames] are
     * resolved, rather than every message in the descriptor set, since the latter includes plenty
     * of types - `google.protobuf.Any`, gnostic's own annotation messages, ... - that only collide
     * on short name with each other because neither is ever actually reachable as a
     * `components.schemas` entry in the first place.
     */
    private fun indexMessagesByShortName(
        files: Map<String, Descriptors.FileDescriptor>,
        schemaNames: Set<String>,
    ): Map<String, String> {
        val messagesByShortName = files.values.asSequence().flatMap { it.messageTypes }.groupBy { it.name }
        val result = HashMap<String, String>()
        for (name in schemaNames) {
            val candidates = messagesByShortName[name] ?: continue
            check(candidates.size == 1) {
                "Schema '$name' matches more than one message by short name - " +
                        "OpenApiExampleAugmenter can't tell which one it refers to: " +
                        candidates.joinToString { it.fullName }
            }
            result[name] = candidates.single().fullName
        }
        return result
    }
}
