package no.entur.exposed.paging

import no.entur.exposed.testfixtures.v1.Widget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class FilterableEnumPropertyTest {
    @Test
    fun `a non-enum field is rejected, not silently accepted`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                FilterableProperties.builder<Widget>(Widget.getDescriptor()).field("name", WidgetTable.name)
            }

        assertTrue(exception.message!!.contains("enum"))
    }

    @Test
    fun `a repeated field is rejected, not silently accepted as if it were singular`() {
        val partsField = Widget.getDescriptor().findFieldByName("parts")

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                FilterableEnumProperty<Widget>(listOf(partsField), WidgetTable.status)
            }

        assertTrue(exception.message!!.contains("repeated"))
    }

    @Test
    fun `nesting through a field that isn't a message is rejected`() {
        val builder = FilterableProperties.builder<Widget>(Widget.getDescriptor())

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                builder.field("name.whatever", WidgetTable.status)
            }

        assertTrue(exception.message!!.contains("name"))
    }

    @Test
    fun `an unknown field is rejected`() {
        val properties = FilterableProperties.builder<Widget>(Widget.getDescriptor()).field("status", WidgetTable.status).build()

        val exception = assertThrows(IllegalArgumentException::class.java) { properties.byName("bogus") }

        assertTrue(exception.message!!.contains("bogus"))
    }

    @Test
    fun `a top-level enum field's filter name is just its own json name`() {
        val properties = FilterableProperties.builder<Widget>(Widget.getDescriptor()).field("status", WidgetTable.status).build()

        assertEquals("status", properties.byName("status").name)
    }

    @Test
    fun `a nested enum field's filter name is the dotted path to it`() {
        val properties =
            FilterableProperties
                .builder<Widget>(Widget.getDescriptor())
                .field("stats.nested_status", WidgetTable.statsNestedStatus)
                .build()

        assertEquals("stats.nestedStatus", properties.byName("stats.nestedStatus").name)
    }

    @Test
    fun `equalCondition accepts a value that's one of the proto enum's own declared labels`() {
        val property =
            FilterableProperties
                .builder<Widget>(
                    Widget.getDescriptor(),
                ).field("status", WidgetTable.status)
                .build()
                .byName("status")

        // No exception - just confirms the condition builds.
        property.equalCondition("WIDGET_STATUS_ACTIVE")
    }

    @Test
    fun `equalCondition rejects a value that isn't one of the proto enum's own declared labels`() {
        val property =
            FilterableProperties
                .builder<Widget>(
                    Widget.getDescriptor(),
                ).field("status", WidgetTable.status)
                .build()
                .byName("status")

        val exception = assertThrows(IllegalArgumentException::class.java) { property.equalCondition("NOT_A_STATUS") }

        assertTrue(exception.message!!.contains("NOT_A_STATUS"))
    }

    @Test
    fun `equalCondition rejects the unspecified zero value, like PostgresEnumColumnType does`() {
        val property =
            FilterableProperties
                .builder<Widget>(
                    Widget.getDescriptor(),
                ).field("status", WidgetTable.status)
                .build()
                .byName("status")

        val exception = assertThrows(IllegalArgumentException::class.java) { property.equalCondition("WIDGET_STATUS_UNSPECIFIED") }

        assertTrue(exception.message!!.contains("WIDGET_STATUS_UNSPECIFIED"))
    }
}
