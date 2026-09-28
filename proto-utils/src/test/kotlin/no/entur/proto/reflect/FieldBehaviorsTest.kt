package no.entur.proto.reflect

import com.google.api.FieldBehavior
import no.entur.proto.testfixtures.v1.Widget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Direct unit coverage for `hasBehavior`/`fieldsWithBehavior` (see FieldBehaviors.kt), against `Widget`'s descriptor. */
internal class FieldBehaviorsTest {
    private val descriptor = Widget.getDescriptor()

    @Test
    fun `hasBehavior is true for a field declaring the requested behavior`() {
        assertTrue(descriptor.findFieldByName("id").hasBehavior(FieldBehavior.IDENTIFIER))
        assertTrue(descriptor.findFieldByName("updated_at").hasBehavior(FieldBehavior.OUTPUT_ONLY))
    }

    @Test
    fun `hasBehavior is false for a field declaring neither IDENTIFIER nor OUTPUT_ONLY`() {
        val nameField = descriptor.findFieldByName("name")
        assertFalse(nameField.hasBehavior(FieldBehavior.IDENTIFIER))
        assertFalse(nameField.hasBehavior(FieldBehavior.OUTPUT_ONLY))
    }

    @Test
    fun `fieldsWithBehavior returns exactly the fields declaring that behavior`() {
        assertEquals(setOf(descriptor.findFieldByName("updated_at")), descriptor.fieldsWithBehavior(FieldBehavior.OUTPUT_ONLY))
        assertEquals(setOf(descriptor.findFieldByName("id")), descriptor.fieldsWithBehavior(FieldBehavior.IDENTIFIER))
    }
}
