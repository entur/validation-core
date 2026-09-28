package no.entur.exposed.paging

import no.entur.exposed.testfixtures.v1.LineItem
import no.entur.exposed.testfixtures.v1.Widget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit-level coverage for [SortableProperty]/[SortableProperties] themselves - as opposed to
 * [PagingSupportTest], which exercises the [PagingSupport] engine built on top of them.
 */
internal class SortablePropertyTest {
    @Test
    fun `a repeated field is rejected, not silently accepted as if it were singular`() {
        val partsField = Widget.getDescriptor().findFieldByName("parts")

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                SortableProperty.of<Widget, String>(partsField, WidgetTable.name)
            }

        assertTrue(exception.message!!.contains("repeated"))
    }

    @Test
    fun `build snapshots ids, so a later id() call on a retained builder doesn't change an already-built instance`() {
        val builder = SortableProperties.builder<LineItem>(LineItem.getDescriptor()).id("order_id", LineItemTable.orderId)
        val first = builder.build()
        assertEquals(listOf("orderId"), first.ids.map { it.descriptor.jsonName })

        builder.id("line_number", LineItemTable.lineNumber)

        assertEquals(listOf("orderId"), first.ids.map { it.descriptor.jsonName })
    }
}
