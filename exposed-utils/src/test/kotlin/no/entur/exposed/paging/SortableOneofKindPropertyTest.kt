package no.entur.exposed.paging

import no.entur.exposed.testfixtures.v1.Widget
import no.entur.exposed.testfixtures.v1.detail
import no.entur.exposed.testfixtures.v1.part
import no.entur.exposed.testfixtures.v1.widget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class SortableOneofKindPropertyTest {
    @Test
    fun `an unknown oneof name is rejected`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                SortableProperties.builder<Widget>(Widget.getDescriptor()).oneofKind("variantKind", "bogus", WidgetTable.variantKind)
            }

        assertTrue(exception.message!!.contains("bogus"))
    }

    @Test
    fun `the order_by name is exactly what the caller passed in, unlike a real field`() {
        val properties =
            SortableProperties.builder<Widget>(Widget.getDescriptor()).oneofKind("variantKind", "variant", WidgetTable.variantKind).build()

        assertEquals("variantKind", properties.byName("variantKind").name)
    }

    @Test
    fun `valueOf reads the set variant's own field name, upper-cased`() {
        val property =
            SortableProperties
                .builder<Widget>(Widget.getDescriptor())
                .oneofKind("variantKind", "variant", WidgetTable.variantKind)
                .build()
                .byName("variantKind")

        assertEquals("PART_VARIANT", property.valueOf(widget { partVariant = part { label = "x" } }))
        assertEquals("DETAIL_VARIANT", property.valueOf(widget { detailVariant = detail { value = "y" } }))
    }

    @Test
    fun `valueOf-then-decode round-trips through decode's lower-casing`() {
        val property =
            SortableProperties
                .builder<Widget>(Widget.getDescriptor())
                .oneofKind("variantKind", "variant", WidgetTable.variantKind)
                .build()
                .byName("variantKind")
        val value = widget { partVariant = part { label = "x" } }

        assertEquals("part_variant", property.decode(property.valueOf(value).toString()))
    }

    @Test
    fun `decode of a null raw cursor value is null`() {
        val property =
            SortableProperties
                .builder<Widget>(Widget.getDescriptor())
                .oneofKind("variantKind", "variant", WidgetTable.variantKind)
                .build()
                .byName("variantKind")

        assertEquals(null, property.decode(null))
    }

    @Test
    fun `valueOf throws if no variant is set - unlike a required field, nothing else catches this`() {
        val property =
            SortableProperties
                .builder<Widget>(Widget.getDescriptor())
                .oneofKind("variantKind", "variant", WidgetTable.variantKind)
                .build()
                .byName("variantKind")

        val exception = assertThrows(IllegalArgumentException::class.java) { property.valueOf(widget { id = 1 }) }

        assertTrue(exception.message!!.contains("variantKind"))
    }
}
