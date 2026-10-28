package no.entur.exposed.paging

import no.entur.exposed.testfixtures.v1.WidgetStatus
import no.entur.exposed.testfixtures.v1.widget
import no.entur.exposed.testfixtures.v1.widgetStats
import no.entur.proto.time.toProtoTimestamp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * Unit-level coverage for [PagingSupport] - no DB, no Spring context, since none of the logic
 * tested here (`order_by` parsing, cursor encode/decode) depends on persistence. The keyset
 * predicate [PagingSupport.keysetCondition] itself builds is checked against a real Postgres in
 * [PagingSupportPostgresTest], since what matters there is the rows it actually selects, not its
 * in-memory shape.
 */
internal class PagingSupportTest {
    @Test
    fun `no order_by falls back to just the id, ascending`() {
        val sort = PagingSupport.parseOrderBy(null, WIDGET_SORTABLE_PROPERTIES)

        assertEquals(listOf(SortField("id", ascending = true)), sort)
    }

    @Test
    fun `a blank order_by falls back the same as an absent one`() {
        val sort = PagingSupport.parseOrderBy("  , ", WIDGET_SORTABLE_PROPERTIES)

        assertEquals(listOf(SortField("id", ascending = true)), sort)
    }

    @Test
    fun `an explicit field is kept, in the direction requested, with the id appended as a tie-break`() {
        val sort = PagingSupport.parseOrderBy("name desc", WIDGET_SORTABLE_PROPERTIES)

        assertEquals(listOf(SortField("name", ascending = false), SortField("id", ascending = true)), sort)
    }

    @Test
    fun `several explicit fields keep their own request order`() {
        val sort = PagingSupport.parseOrderBy("status desc, name", WIDGET_SORTABLE_PROPERTIES)

        assertEquals(
            listOf(SortField("status", ascending = false), SortField("name", ascending = true), SortField("id", ascending = true)),
            sort,
        )
    }

    @Test
    fun `explicitly ordering by the id is not duplicated by the tie-break`() {
        val sort = PagingSupport.parseOrderBy("id desc", WIDGET_SORTABLE_PROPERTIES)

        assertEquals(listOf(SortField("id", ascending = false)), sort)
    }

    @Test
    fun `a later repeat of the same field overrides its earlier direction, without moving it`() {
        val sort = PagingSupport.parseOrderBy("name desc, status, name", WIDGET_SORTABLE_PROPERTIES)

        assertEquals(
            listOf(SortField("name", ascending = true), SortField("status", ascending = true), SortField("id", ascending = true)),
            sort,
        )
    }

    @Test
    fun `an unsupported order_by field is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PagingSupport.parseOrderBy("bogus", WIDGET_SORTABLE_PROPERTIES)
        }
    }

    @Test
    fun `every id property is appended, in the resource's own declared order, for a composite id`() {
        val sort = PagingSupport.parseOrderBy(null, LINE_ITEM_SORTABLE_PROPERTIES)

        assertEquals(listOf(SortField("orderId", ascending = true), SortField("lineNumber", ascending = true)), sort)
    }

    @Test
    fun `a composite id's fields not already named in order_by are appended after it, in declared order`() {
        val sort = PagingSupport.parseOrderBy("description", LINE_ITEM_SORTABLE_PROPERTIES)

        assertEquals(
            listOf(
                SortField("description", ascending = true),
                SortField("orderId", ascending = true),
                SortField("lineNumber", ascending = true),
            ),
            sort,
        )
    }

    @Test
    fun `a composite id field already named in order_by is not duplicated by the tie-break`() {
        val sort = PagingSupport.parseOrderBy("orderId desc", LINE_ITEM_SORTABLE_PROPERTIES)

        assertEquals(listOf(SortField("orderId", ascending = false), SortField("lineNumber", ascending = true)), sort)
    }

    @Test
    fun `nextCursor is null when there is no next page`() {
        val page =
            Page(
                content =
                    listOf(
                        widget {
                            id = 1
                            name = "a"
                        },
                    ),
                hasNext = false,
                sort = listOf(SortField("id")),
            )

        assertNull(PagingSupport.nextCursor(WIDGET_SORTABLE_PROPERTIES, page, OBJECT_MAPPER))
    }

    @Test
    fun `nextCursor then decodeCursor round-trips every sort field's value, correctly typed`() {
        val last =
            widget {
                id = 42L
                name = "widget-42"
                status = WidgetStatus.WIDGET_STATUS_ACTIVE
            }
        val sort = listOf(SortField("status"), SortField("name", ascending = false), SortField("id"))
        val page = Page(content = listOf(last), hasNext = true, sort = sort)

        val cursor = requireNotNull(PagingSupport.nextCursor(WIDGET_SORTABLE_PROPERTIES, page, OBJECT_MAPPER))
        val decoded = PagingSupport.decodeCursor(WIDGET_SORTABLE_PROPERTIES, cursor, sort, OBJECT_MAPPER)

        assertEquals(mapOf("status" to "WIDGET_STATUS_ACTIVE", "name" to "widget-42", "id" to 42L), decoded)
    }

    @Test
    fun `decodeCursor of a null cursor is null`() {
        assertNull(PagingSupport.decodeCursor(WIDGET_SORTABLE_PROPERTIES, null, listOf(SortField("id")), OBJECT_MAPPER))
    }

    @Test
    fun `decodeCursor rejects a cursor whose value count doesn't match the requested sort`() {
        val mintedForTwoFields =
            requireNotNull(
                PagingSupport.nextCursor(
                    WIDGET_SORTABLE_PROPERTIES,
                    Page(
                        listOf(
                            widget {
                                id = 1
                                name = "a"
                            },
                        ),
                        hasNext = true,
                        sort = listOf(SortField("id"), SortField("name")),
                    ),
                    OBJECT_MAPPER,
                ),
            )

        assertThrows(IllegalArgumentException::class.java) {
            PagingSupport.decodeCursor(WIDGET_SORTABLE_PROPERTIES, mintedForTwoFields, listOf(SortField("id")), OBJECT_MAPPER)
        }
    }

    @Test
    fun `decodeCursor rejects a cursor that isn't valid base64url-encoded JSON`() {
        assertThrows(IllegalArgumentException::class.java) {
            PagingSupport.decodeCursor(WIDGET_SORTABLE_PROPERTIES, "not a cursor", listOf(SortField("id")), OBJECT_MAPPER)
        }
    }

    @Test
    fun `a dotted order_by name resolves a nested field, same as a top-level one`() {
        val sort = PagingSupport.parseOrderBy("stats.lastUpdatedAt desc", WIDGET_SORTABLE_PROPERTIES)

        assertEquals(listOf(SortField("stats.lastUpdatedAt", ascending = false), SortField("id", ascending = true)), sort)
    }

    @Test
    fun `nextCursor then decodeCursor round-trips a Timestamp field's value as an OffsetDateTime, not a String`() {
        val instant = OffsetDateTime.parse("2026-01-01T12:00:00Z")
        val last =
            widget {
                id = 1
                updatedAt = instant.toProtoTimestamp()
                stats = widgetStats { lastUpdatedAt = instant.toProtoTimestamp() }
            }
        val sort = listOf(SortField("updatedAt"), SortField("stats.lastUpdatedAt"), SortField("id"))
        val page = Page(content = listOf(last), hasNext = true, sort = sort)

        val cursor = requireNotNull(PagingSupport.nextCursor(WIDGET_SORTABLE_PROPERTIES, page, OBJECT_MAPPER))
        val decoded = PagingSupport.decodeCursor(WIDGET_SORTABLE_PROPERTIES, cursor, sort, OBJECT_MAPPER)

        assertEquals(mapOf("updatedAt" to instant, "stats.lastUpdatedAt" to instant, "id" to 1L), decoded)
    }

    @Test
    fun `nextCursor then decodeCursor round-trips a nullable field's absent value as null, not a literal null string`() {
        val last = widget { id = 1 }
        val sort = listOf(SortField("active"), SortField("id"))
        val page = Page(content = listOf(last), hasNext = true, sort = sort)

        val cursor = requireNotNull(PagingSupport.nextCursor(WIDGET_SORTABLE_PROPERTIES, page, OBJECT_MAPPER))
        val decoded = PagingSupport.decodeCursor(WIDGET_SORTABLE_PROPERTIES, cursor, sort, OBJECT_MAPPER)

        assertEquals(mapOf<String, Any?>("active" to null, "id" to 1L), decoded)
    }

    @Test
    fun `nextCursor then decodeCursor round-trips a nullable field's present value normally`() {
        val last =
            widget {
                id = 1
                active = true
            }
        val sort = listOf(SortField("active"), SortField("id"))
        val page = Page(content = listOf(last), hasNext = true, sort = sort)

        val cursor = requireNotNull(PagingSupport.nextCursor(WIDGET_SORTABLE_PROPERTIES, page, OBJECT_MAPPER))
        val decoded = PagingSupport.decodeCursor(WIDGET_SORTABLE_PROPERTIES, cursor, sort, OBJECT_MAPPER)

        assertEquals(mapOf<String, Any?>("active" to true, "id" to 1L), decoded)
    }

    @Test
    fun `decodeCursor rejects a value that doesn't parse as its property's type`() {
        val notANumber =
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                OBJECT_MAPPER.writeValueAsBytes(listOf("not-a-long")),
            )

        assertThrows(IllegalArgumentException::class.java) {
            PagingSupport.decodeCursor(WIDGET_SORTABLE_PROPERTIES, notANumber, listOf(SortField("id")), OBJECT_MAPPER)
        }
    }

    @Test
    fun `nextCursor then decodeCursor round-trips the sort fields' values alongside a hand-supplied id tie-break`() {
        val last =
            widget {
                id = 1
                name = "widget-42"
            }
        val sort = listOf(SortField("name"))
        val page = Page(content = listOf(last), hasNext = true, sort = sort)

        val cursor = requireNotNull(PagingSupport.nextCursor(WIDGET_SORTABLE_PROPERTIES, page, "7", OBJECT_MAPPER))
        val decoded = PagingSupport.decodeCursor(WIDGET_SORTABLE_PROPERTIES, cursor, sort, parseId = { it.toLong() }, OBJECT_MAPPER)

        assertEquals(mapOf<String, Any?>("name" to "widget-42") to 7L, decoded)
    }

    @Test
    fun `nextCursor then decodeCursor round-trips a nullable field's absent value as null, alongside the id tie-break`() {
        val last = widget { id = 1 }
        val sort = listOf(SortField("active"))
        val page = Page(content = listOf(last), hasNext = true, sort = sort)

        val cursor = requireNotNull(PagingSupport.nextCursor(WIDGET_SORTABLE_PROPERTIES, page, "7", OBJECT_MAPPER))
        val decoded = PagingSupport.decodeCursor(WIDGET_SORTABLE_PROPERTIES, cursor, sort, parseId = { it.toLong() }, OBJECT_MAPPER)

        assertEquals(mapOf<String, Any?>("active" to null) to 7L, decoded)
    }

    @Test
    fun `decodeCursor with a parseId is null for a null cursor`() {
        val decoded =
            PagingSupport.decodeCursor(
                WIDGET_SORTABLE_PROPERTIES,
                null,
                listOf(SortField("name")),
                parseId = { it.toLong() },
                OBJECT_MAPPER,
            )

        assertEquals(null, decoded)
    }

    @Test
    fun `decodeCursor with a parseId rejects a cursor minted without the id tie-break appended`() {
        val sort = listOf(SortField("name"))
        val mintedWithoutAnId =
            requireNotNull(
                PagingSupport.nextCursor(
                    WIDGET_SORTABLE_PROPERTIES,
                    Page(
                        listOf(
                            widget {
                                id = 1
                                name = "a"
                            },
                        ),
                        hasNext = true,
                        sort = sort,
                    ),
                    OBJECT_MAPPER,
                ),
            )

        assertThrows(IllegalArgumentException::class.java) {
            PagingSupport.decodeCursor(WIDGET_SORTABLE_PROPERTIES, mintedWithoutAnId, sort, parseId = { it.toLong() }, OBJECT_MAPPER)
        }
    }
}
