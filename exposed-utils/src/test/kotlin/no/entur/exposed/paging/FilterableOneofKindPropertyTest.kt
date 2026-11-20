package no.entur.exposed.paging

import no.entur.exposed.testfixtures.v1.Widget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class FilterableOneofKindPropertyTest {
    @Test
    fun `an unknown oneof name is rejected`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                FilterableProperties.builder<Widget>(Widget.getDescriptor()).oneofKind("variantKind", "bogus", WidgetTable.variantKind)
            }

        assertTrue(exception.message!!.contains("bogus"))
    }

    @Test
    fun `the filter name is exactly what the caller passed in, unlike a real field`() {
        val properties =
            FilterableProperties
                .builder<Widget>(
                    Widget.getDescriptor(),
                ).oneofKind("variantKind", "variant", WidgetTable.variantKind)
                .build()

        assertEquals("variantKind", properties.byName("variantKind").name)
    }

    @Test
    fun `equalCondition accepts an upper-cased variant field name`() {
        val property =
            FilterableProperties
                .builder<Widget>(Widget.getDescriptor())
                .oneofKind("variantKind", "variant", WidgetTable.variantKind)
                .build()
                .byName("variantKind")

        property.equalCondition("PART_VARIANT")
        property.equalCondition("DETAIL_VARIANT")
    }

    @Test
    fun `equalCondition rejects a value that isn't one of the oneof's own variant field names`() {
        val property =
            FilterableProperties
                .builder<Widget>(Widget.getDescriptor())
                .oneofKind("variantKind", "variant", WidgetTable.variantKind)
                .build()
                .byName("variantKind")

        val exception = assertThrows(IllegalArgumentException::class.java) { property.equalCondition("BOGUS") }

        assertTrue(exception.message!!.contains("BOGUS"))
    }

    @Test
    fun `equalCondition rejects the lower-cased raw DB form - clients must use the upper-cased form`() {
        val property =
            FilterableProperties
                .builder<Widget>(Widget.getDescriptor())
                .oneofKind("variantKind", "variant", WidgetTable.variantKind)
                .build()
                .byName("variantKind")

        assertThrows(IllegalArgumentException::class.java) { property.equalCondition("part_variant") }
    }
}
