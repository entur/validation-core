package no.entur.exposed.paging

import com.google.protobuf.ByteString
import no.entur.exposed.testfixtures.v1.LineItem
import no.entur.exposed.testfixtures.v1.Widget
import no.entur.exposed.testfixtures.v1.widget
import no.entur.exposed.testfixtures.v1.widgetStats
import no.entur.proto.time.toProtoTimestamp
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

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
                SortableComparableProperty.create<Widget, String>(listOf(partsField), WidgetTable.name)
            }

        assertTrue(exception.message!!.contains("repeated"))
    }

    @Test
    fun `a non-Timestamp message field is rejected, not silently accepted as a leaf`() {
        val detailField =
            Widget
                .getDescriptor()
                .findFieldByName("parts")
                .messageType
                .findFieldByName("detail")

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                SortableComparableProperty.create<Widget, String>(listOf(detailField), WidgetTable.name)
            }

        assertTrue(exception.message!!.contains("MESSAGE"))
    }

    @Test
    fun `nesting through a field that isn't a message is rejected`() {
        val builder = SortableProperties.builder<Widget>(Widget.getDescriptor())

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                builder.field("name.whatever", WidgetTable.name)
            }

        assertTrue(exception.message!!.contains("name"))
    }

    @Test
    fun `nesting through a Timestamp field is rejected - it has no field of its own`() {
        val builder = SortableProperties.builder<Widget>(Widget.getDescriptor())

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                builder.field("updated_at.seconds", WidgetTable.statsLastUpdatedAt)
            }

        assertTrue(exception.message!!.contains("Timestamp"))
    }

    @Test
    fun `a top-level Timestamp field's order_by name is just its own json name`() {
        val properties =
            SortableProperties
                .builder<Widget>(Widget.getDescriptor())
                .field("updated_at", WidgetTable.updatedAt)
                .build()

        assertEquals("updatedAt", properties.byName("updatedAt").name)
    }

    @Test
    fun `a nested Timestamp field's order_by name is the dotted path to it`() {
        val properties =
            SortableProperties
                .builder<Widget>(Widget.getDescriptor())
                .field("stats.last_updated_at", WidgetTable.statsLastUpdatedAt)
                .build()

        assertEquals("stats.lastUpdatedAt", properties.byName("stats.lastUpdatedAt").name)
    }

    @Test
    fun `valueOf reads a nested Timestamp field through its parent sub-message`() {
        val instant = OffsetDateTime.parse("2026-01-01T12:00:00Z")
        val property =
            SortableProperties
                .builder<Widget>(Widget.getDescriptor())
                .field("stats.last_updated_at", WidgetTable.statsLastUpdatedAt)
                .build()
                .byName("stats.lastUpdatedAt")
        val value =
            widget {
                id = 1
                stats = widgetStats { lastUpdatedAt = instant.toProtoTimestamp() }
            }

        // Not instant itself - valueOf's contract is only that its toString() round-trips through
        // decode(), not that it returns the typed column value: a Timestamp field's codec already
        // renders it as that stable string directly, same as a bytes field's (see BytesProperty's
        // own coverage below) - [PagingSupport.nextCursor] calls toString() on it regardless.
        assertEquals(instant.toString(), property.valueOf(value))
    }

    @Test
    fun `decode of a Timestamp field parses the same ISO-8601 string valueOf produces`() {
        val property =
            SortableProperties
                .builder<Widget>(Widget.getDescriptor())
                .field("updated_at", WidgetTable.updatedAt)
                .build()
                .byName("updatedAt")
        val instant = OffsetDateTime.parse("2026-01-01T12:00:00Z")
        val value = widget { updatedAt = instant.toProtoTimestamp() }

        assertEquals(instant, property.decode(property.valueOf(value).toString()))
    }

    @Test
    fun `decode of a malformed Timestamp field raises IllegalArgumentException, not DateTimeParseException`() {
        val property =
            SortableProperties
                .builder<Widget>(Widget.getDescriptor())
                .field("updated_at", WidgetTable.updatedAt)
                .build()
                .byName("updatedAt")

        val exception = assertThrows(IllegalArgumentException::class.java) { property.decode("not-a-timestamp") }

        assertTrue(exception.message!!.contains("updatedAt"))
    }

    @Test
    fun `a bytes field backed by a non-binary column is rejected`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                SortableProperties
                    .builder<Widget>(Widget.getDescriptor())
                    .field("raw_id", WidgetTable.name)
            }

        assertTrue(exception.message!!.contains("BYTE_STRING"))
    }

    @Test
    fun `valueOf-then-decode round-trips a bytes field's value as a ByteArray, via base64`() {
        val property =
            SortableProperties
                .builder<Widget>(Widget.getDescriptor())
                .field("raw_id", WidgetTable.rawId)
                .build()
                .byName("rawId")
        val bytes = byteArrayOf(1, 2, 3, -1, 0)
        val value = widget { rawId = ByteString.copyFrom(bytes) }

        val cursorValue = property.valueOf(value)
        assertTrue(cursorValue is String, "a ByteArray's own toString() isn't round-trip-safe, so valueOf must already be a string")
        assertArrayEquals(bytes, property.decode(cursorValue.toString()) as ByteArray)
    }

    @Test
    fun `a column type mismatch is rejected, unless the caller supplies its own decode`() {
        // "name" (STRING) against WidgetTable.id (a Long id column) - a real mismatch, standing in
        // for the motivating case (a `string` proto field backed by a native UUID column).
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                SortableProperties.builder<Widget>(Widget.getDescriptor()).field("name", WidgetTable.id)
            }
        assertTrue(exception.message!!.contains("STRING"))

        val property =
            SortableProperties
                .builder<Widget>(Widget.getDescriptor())
                .field("name", WidgetTable.id, decode = { it.toLong() })
                .build()
                .byName("name")

        assertEquals(
            "hello",
            property.valueOf(widget { name = "hello" }),
            "valueOf never needs its own decode - see SortableProperty's KDoc",
        )
        assertEquals(42L, property.decode("42"))
    }

    @Test
    fun `ofNullable rejects a field that isn't declared optional - its absence can't be tracked at all`() {
        val statusField = Widget.getDescriptor().findFieldByName("status")

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                // status isn't optional - WidgetTable.active (a nullable column of some comparable
                // type) only needs to satisfy the compiler here, the mismatch itself is never reached.
                SortableNullableProperty.create<Widget, Boolean>(listOf(statusField), WidgetTable.active, NullsOrder.LAST)
            }

        assertTrue(exception.message!!.contains("optional"))
    }

    @Test
    fun `valueOf is null for an absent optional field, the actual value for a present one`() {
        val property =
            SortableProperties
                .builder<Widget>(Widget.getDescriptor())
                .fieldNullsLast("active", WidgetTable.active)
                .build()
                .byName("active")

        assertEquals(null, property.valueOf(widget { id = 1 }))
        assertEquals(true, property.valueOf(widget { active = true }))
    }

    @Test
    fun `decode of a null raw cursor value is null, regardless of the property`() {
        val property =
            SortableProperties
                .builder<Widget>(Widget.getDescriptor())
                .fieldNullsLast("active", WidgetTable.active)
                .build()
                .byName("active")

        assertEquals(null, property.decode(null))
        assertEquals(true, property.decode("true"))
    }

    @Test
    fun `build snapshots ids, so a later id() call on a retained builder doesn't change an already-built instance`() {
        val builder = SortableProperties.builder<LineItem>(LineItem.getDescriptor()).id("order_id", LineItemTable.orderId)
        val first = builder.build()
        assertEquals(listOf("orderId"), first.ids.map { it.name })

        builder.id("line_number", LineItemTable.lineNumber)

        assertEquals(listOf("orderId"), first.ids.map { it.name })
    }
}
