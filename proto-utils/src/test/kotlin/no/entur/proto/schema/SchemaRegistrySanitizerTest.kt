package no.entur.proto.schema

import com.google.protobuf.ByteString
import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.Timestamp
import com.google.protobuf.UnknownFieldSet
import no.entur.proto.testfixtures.v1.Annotated
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Direct unit coverage for SchemaRegistrySanitizer.kt, against `Annotated`'s descriptor. */
internal class SchemaRegistrySanitizerTest {
    private val droppedImports = setOf("gnostic/openapi/v3/annotations.proto", "google/api/field_behavior.proto")

    // google.api.field_behavior = 1052, gnostic.openapi.v3.property = 1143.
    private val droppedExtensionNumbers = setOf(1052, 1143)

    private val sanitized = Annotated.getDescriptor().file.withoutFieldOptionExtensions(droppedImports, droppedExtensionNumbers)

    private fun noneDropped(options: ByteString) =
        UnknownFieldSet
            .parseFrom(options)
            .asMap()
            .keys
            .none { it in droppedExtensionNumbers }

    @Test
    fun `drops the named imports and keeps the rest`() {
        assertFalse(sanitized.dependencyList.any { it in droppedImports })
        assertTrue("google/protobuf/timestamp.proto" in sanitized.dependencyList)
    }

    @Test
    fun `strips both extensions from a field declaring them, including a repeated one`() {
        val id =
            sanitized.messageTypeList
                .single { it.name == "Annotated" }
                .fieldList
                .single { it.name == "id" }
        assertTrue(id.hasOptions())
        assertTrue(noneDropped(id.options.toByteString()))
    }

    @Test
    fun `leaves a field with no options alone`() {
        val plain =
            sanitized.messageTypeList
                .single { it.name == "Annotated" }
                .fieldList
                .single { it.name == "plain" }
        assertFalse(plain.hasOptions())
    }

    @Test
    fun `recurses into a nested message type`() {
        val child =
            sanitized.messageTypeList
                .single { it.name == "Annotated" }
                .nestedTypeList
                .single { it.name == "Child" }
        val value = child.fieldList.single { it.name == "value" }
        assertTrue(value.hasOptions())
        assertTrue(noneDropped(value.options.toByteString()))
    }

    @Test
    fun `builds back into a live descriptor using only its remaining dependencies`() {
        FileDescriptor.buildFrom(sanitized, arrayOf(Timestamp.getDescriptor().file))
    }
}
