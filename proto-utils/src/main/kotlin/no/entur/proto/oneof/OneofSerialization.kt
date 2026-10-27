package no.entur.proto.oneof

import com.google.protobuf.Descriptors
import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat

/** Serialization of a protobuf oneof value. */
data class OneofVariant(
    /** The set field's proto name. */
    val kind: String,
    /** Its contents, serialized as JSON. */
    val json: String,
)

/**
 * Generic persistence for a proto message's `oneof` field.
 */
object OneofSerialization {
    private val printer: JsonFormat.Printer = JsonFormat.printer().preservingProtoFieldNames()
    private val parser: JsonFormat.Parser = JsonFormat.parser().ignoringUnknownFields()

    /** [message]'s set variant of its `oneofName` oneof. */
    fun serialize(
        message: Message,
        oneofName: String,
    ): OneofVariant {
        val field =
            message.getOneofFieldDescriptor(oneofDescriptor(message.descriptorForType, oneofName))
                ?: error("${message.descriptorForType.fullName} has no field set for oneof '$oneofName'")
        val variantMessage = message.getField(field) as Message
        return OneofVariant(field.name, printer.print(variantMessage))
    }

    /** Sets [variant] as [builder]'s `oneofName` oneof variant. */
    fun deserialize(
        builder: Message.Builder,
        oneofName: String,
        variant: OneofVariant,
    ) {
        val field =
            oneofDescriptor(builder.descriptorForType, oneofName).fields.find { it.name == variant.kind }
                ?: error(
                    "Unknown rule kind '${variant.kind}' for oneof '$oneofName' on ${builder.descriptorForType.fullName}.",
                )
        val fieldBuilder = builder.newBuilderForField(field)
        parser.merge(variant.json, fieldBuilder)
        builder.setField(field, fieldBuilder.build())
    }

    private fun oneofDescriptor(
        descriptor: Descriptors.Descriptor,
        oneofName: String,
    ) = descriptor.oneofs.find { it.name == oneofName }
        ?: error("${descriptor.fullName} has no oneof named '$oneofName'")
}
