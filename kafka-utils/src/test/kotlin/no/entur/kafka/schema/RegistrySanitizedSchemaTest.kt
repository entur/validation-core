package no.entur.kafka.schema

import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.UnknownFieldSet
import no.entur.validation.proto.v1.ValidationProgress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * `ValidationProgress` (via `RuleResult.validation_rule`) is a real message that needs sanitizing:
 * `validation_rule.proto` carries the REST-only annotations this class strips. The stripping itself
 * is covered by proto-utils' own SchemaRegistrySanitizerTest; this only checks the defaults and the
 * wrapping into a [io.confluent.kafka.schemaregistry.protobuf.ProtobufSchema].
 */
class RegistrySanitizedSchemaTest {
    private val schema = RegistrySanitizedSchema(ValidationProgress.getDescriptor())

    @Test
    fun `drops the default REST-only imports from the file and everything it depends on`() {
        allFiles(schema.file).forEach { file ->
            assertFalse(file.dependencies.any { it.name in RegistrySanitizedSchema.DROPPED_IMPORTS })
        }
    }

    @Test
    fun `strips the default extension numbers from every field, including in an imported file`() {
        allFiles(schema.file).forEach { file -> file.messageTypes.forEach(::assertNoDroppedExtensions) }
    }

    @Test
    fun `wraps the sanitized descriptor in a ProtobufSchema named after the message`() {
        assertEquals(ValidationProgress.getDescriptor().fullName, schema.schema.name())
        assertFalse(schema.schema.canonicalString().contains("gnostic"))
    }

    @Test
    fun `exposes the descriptor's own, unqualified message name`() {
        assertEquals("ValidationProgress", schema.messageName)
    }

    private fun allFiles(file: FileDescriptor): List<FileDescriptor> {
        val seen = mutableMapOf<String, FileDescriptor>()

        fun visit(f: FileDescriptor) {
            if (seen.putIfAbsent(f.name, f) == null) f.dependencies.forEach(::visit)
        }
        visit(file)
        return seen.values.toList()
    }

    private fun assertNoDroppedExtensions(descriptor: Descriptor) {
        descriptor.fields.forEach { field ->
            val unknown = UnknownFieldSet.parseFrom(field.options.toByteString())
            assertFalse(unknown.asMap().keys.any { it in RegistrySanitizedSchema.DROPPED_EXTENSION_NUMBERS })
        }
        descriptor.nestedTypes.forEach(::assertNoDroppedExtensions)
    }
}
