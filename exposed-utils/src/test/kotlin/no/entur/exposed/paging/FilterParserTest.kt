package no.entur.exposed.paging

import org.jetbrains.exposed.v1.core.Op
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The parsed condition's correctness can only be checked by running it; see [FilterParserPostgresTest]. */
internal class FilterParserTest {
    @Test
    fun `a blank or null filter matches every row`() {
        assertEquals(Op.TRUE, PagingSupport.parseFilter(null, WIDGET_FILTERABLE_PROPERTIES))
        assertEquals(Op.TRUE, PagingSupport.parseFilter("  ", WIDGET_FILTERABLE_PROPERTIES))
    }

    @Test
    fun `an unsupported field is rejected`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                PagingSupport.parseFilter("bogus = WIDGET_STATUS_ACTIVE", WIDGET_FILTERABLE_PROPERTIES)
            }

        assertTrue(exception.message!!.contains("bogus"))
    }

    @Test
    fun `an unsupported enum value is rejected`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                PagingSupport.parseFilter("status = NOT_A_STATUS", WIDGET_FILTERABLE_PROPERTIES)
            }

        assertTrue(exception.message!!.contains("NOT_A_STATUS"))
    }

    @Test
    fun `a missing comparator is rejected`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                PagingSupport.parseFilter("status", WIDGET_FILTERABLE_PROPERTIES)
            }

        assertTrue(exception.message!!.contains("Invalid filter"))
    }

    @Test
    fun `an unmatched opening parenthesis is rejected`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                PagingSupport.parseFilter("(status = WIDGET_STATUS_ACTIVE", WIDGET_FILTERABLE_PROPERTIES)
            }

        assertTrue(exception.message!!.contains("Invalid filter"))
    }

    @Test
    fun `trailing input after a complete expression is rejected`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                PagingSupport.parseFilter("status = WIDGET_STATUS_ACTIVE status = WIDGET_STATUS_ACTIVE", WIDGET_FILTERABLE_PROPERTIES)
            }

        assertTrue(exception.message!!.contains("Invalid filter"))
    }

    @Test
    fun `a quoted string value is accepted, same as a bare one - no exception either way`() {
        PagingSupport.parseFilter("status = \"WIDGET_STATUS_ACTIVE\"", WIDGET_FILTERABLE_PROPERTIES)
    }

    @Test
    fun `a string field accepts any value, unlike an enum field's fixed set`() {
        // No declared-values check to fail here, unlike "an unsupported enum value is rejected"
        // above - any identifier or quoted string is a well-formed restriction on a string field.
        PagingSupport.parseFilter("name = anything_at_all", WIDGET_FILTERABLE_PROPERTIES)
        PagingSupport.parseFilter("name = \"anything at all\"", WIDGET_FILTERABLE_PROPERTIES)
    }
}
