package no.entur.proto.oneof

import no.entur.proto.testfixtures.v1.OneofHolder
import no.entur.proto.testfixtures.v1.WidgetStatus
import no.entur.proto.testfixtures.v1.note
import no.entur.proto.testfixtures.v1.oneofHolder
import no.entur.proto.testfixtures.v1.statusValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OneofSerializationTest {
    @Test
    fun `serialize then deserialize round-trips a message-valued variant`() {
        val holder = oneofHolder { noteValue = note { message = "m" } }

        val variant = OneofSerialization.serialize(holder, "variant")
        assertEquals("note_value", variant.kind)

        val builder = OneofHolder.newBuilder()
        OneofSerialization.deserialize(builder, "variant", variant)
        val result = builder.build()

        assertEquals(OneofHolder.VariantCase.NOTE_VALUE, result.variantCase)
        assertEquals(holder.noteValue, result.noteValue)
    }

    @Test
    fun `serialize then deserialize round-trips a message-valued variant with an enum field`() {
        val holder =
            oneofHolder {
                statusValue =
                    statusValue {
                        label = "l"
                        status = WidgetStatus.WIDGET_STATUS_ACTIVE
                    }
            }

        val variant = OneofSerialization.serialize(holder, "variant")
        assertEquals("status_value", variant.kind)

        val builder = OneofHolder.newBuilder()
        OneofSerialization.deserialize(builder, "variant", variant)
        val result = builder.build()

        assertEquals(OneofHolder.VariantCase.STATUS_VALUE, result.variantCase)
        assertEquals(holder.statusValue, result.statusValue)
    }

    @Test
    fun `serialize rejects a message with no oneof variant set`() {
        val holder = OneofHolder.getDefaultInstance()

        val exception = assertThrows<IllegalStateException> { OneofSerialization.serialize(holder, "variant") }
        assertTrue(exception.message!!.contains("has no field set for oneof 'variant'"))
    }

    @Test
    fun `deserialize rejects a kind unknown to this schema`() {
        val builder = OneofHolder.newBuilder()
        val variant = OneofVariant("some_future_value", "{}")

        val exception = assertThrows<IllegalStateException> { OneofSerialization.deserialize(builder, "variant", variant) }
        assertTrue(exception.message!!.contains("Unknown rule kind 'some_future_value'"))
    }

    @Test
    fun `serialize rejects a scalar-valued oneof variant`() {
        val holder = oneofHolder { textValue = "x" }

        val exception = assertThrows<IllegalStateException> { OneofSerialization.serialize(holder, "variant") }
        assertTrue(exception.message!!.contains("'text_value' oneof variant is STRING, not a message"))
    }

    @Test
    fun `deserialize rejects a scalar-valued oneof variant`() {
        val builder = OneofHolder.newBuilder()
        val variant = OneofVariant("text_value", "\"x\"")

        val exception = assertThrows<IllegalStateException> { OneofSerialization.deserialize(builder, "variant", variant) }
        assertTrue(exception.message!!.contains("'text_value' oneof variant is STRING, not a message"))
    }
}
