package no.entur.http.augmenters

import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.Message
import no.entur.http.repeatedField
import no.entur.http.requireExtension
import no.entur.http.yamlMap
import no.entur.http.yamlMapOrNull

/**
 * Marks a query parameter `required` when its request field is `(google.api.field_behavior) = REQUIRED`, and
 * copies the field's `(gnostic.openapi.v3.property).min_items` onto the parameter's schema. gnostic does
 * neither for query parameters, only for path parameters and body properties.
 */
class OpenApiQueryParameterAugmenter {
    fun augment(
        files: Collection<Descriptors.FileDescriptor>,
        registry: ExtensionRegistry,
        spec: MutableMap<String, Any?>,
    ) {
        val fieldBehaviorExt = registry.requireExtension("google.api.field_behavior")
        val propertyExt = registry.requireExtension("gnostic.openapi.v3.property")
        val requestByOperationId =
            files
                .flatMap { it.services }
                .flatMap { service -> service.methods.map { "${service.name}_${it.name}" to it.inputType } }
                .toMap()

        for (pathItem in yamlMap(spec["paths"]).values) {
            for (operation in yamlMap(pathItem).values.mapNotNull(::yamlMapOrNull)) {
                val request = requestByOperationId[operation["operationId"] as? String] ?: continue
                val parameters = operation["parameters"] as? List<*> ?: continue
                for (parameter in parameters.mapNotNull(::yamlMapOrNull)) {
                    if (parameter["in"] != "query") continue
                    // A nested message's field is a dotted parameter name, which no top-level field matches.
                    val field = request.fields.firstOrNull { it.jsonName == parameter["name"] } ?: continue
                    val options = DescriptorProtos.FieldOptions.parseFrom(field.toProto().options.toByteString(), registry)
                    if (options.repeatedField<Descriptors.EnumValueDescriptor>(fieldBehaviorExt).any { it.name == "REQUIRED" }) {
                        parameter["required"] = true
                    }
                    minItems(options, propertyExt)?.let { yamlMap(parameter["schema"])["minItems"] = it }
                }
            }
        }
    }

    private fun minItems(
        options: DescriptorProtos.FieldOptions,
        propertyExt: Descriptors.FieldDescriptor,
    ): Long? {
        if (!options.hasField(propertyExt)) return null
        val property = options.getField(propertyExt) as Message
        val minItemsField = property.descriptorForType.findFieldByName("min_items") ?: return null
        return (property.getField(minItemsField) as Number).toLong().takeIf { it > 0 }
    }
}
