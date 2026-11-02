package no.entur.proto.oneof

import com.google.protobuf.Descriptors
import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import no.entur.proto.reflect.DescriptorCache

/** A oneof's set field name and its value as JSON. */
data class OneofVariant(
    val kind: String,
    val json: String,
)

/** Persists a `oneof` as an [OneofVariant]. Only message-typed variants are supported, since they're stored as JSON. */
object OneofSerialization {
    private val printer: JsonFormat.Printer = JsonFormat.printer().preservingProtoFieldNames()
    private val parser: JsonFormat.Parser = JsonFormat.parser().ignoringUnknownFields()
    private val oneofsByDescriptor =
        DescriptorCache { descriptor: Descriptors.Descriptor -> descriptor.oneofs.associateBy { it.name } }

    fun serialize(
        message: Message,
        oneofName: String,
    ): OneofVariant {
        val field =
            message.getOneofFieldDescriptor(message.descriptorForType.oneofDescriptor(oneofName))
                ?: error("${message.descriptorForType.fullName} has no field set for oneof '$oneofName'")
        requireMessageTyped(field, message.descriptorForType)
        return OneofVariant(field.name, printer.print(message.getField(field) as Message))
    }

    /** Unknown JSON fields are ignored. */
    fun deserialize(
        builder: Message.Builder,
        oneofName: String,
        variant: OneofVariant,
    ) {
        val field =
            builder.descriptorForType
                .oneofDescriptor(oneofName)
                .fields
                .find { it.name == variant.kind }
                ?: error("Unknown field '${variant.kind}' for oneof '$oneofName' on ${builder.descriptorForType.fullName}.")
        requireMessageTyped(field, builder.descriptorForType)
        val fieldBuilder = builder.newBuilderForField(field)
        parser.merge(variant.json, fieldBuilder)
        builder.setField(field, fieldBuilder.build())
    }

    private fun requireMessageTyped(
        field: Descriptors.FieldDescriptor,
        descriptor: Descriptors.Descriptor,
    ) = check(field.javaType == Descriptors.FieldDescriptor.JavaType.MESSAGE) {
        "${descriptor.fullName}'s '${field.name}' oneof variant is ${field.javaType}, not a message - " +
            "OneofSerialization only supports message-valued oneofs"
    }

    private fun Descriptors.Descriptor.oneofDescriptor(oneofName: String) =
        oneofsByDescriptor[this]?.get(oneofName)
            ?: error("${this.fullName} has no oneof named '$oneofName'")
}
