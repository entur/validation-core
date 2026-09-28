package no.entur.proto.http

import no.entur.proto.testfixtures.v1.note
import no.entur.proto.testfixtures.v1.widget
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Unit coverage for [IdentifierSupport], exercised against the `widget.proto` test fixture types. */
internal class IdentifierSupportTest {
    @Test
    fun `requireIdentifierMatchesPath allows a resource id equal to the path id`() {
        IdentifierSupport.requireIdentifierMatchesPath(widget { id = 5 }, pathId = 5)
    }

    @Test
    fun `requireIdentifierMatchesPath allows a zero (absent) resource id regardless of the path id`() {
        IdentifierSupport.requireIdentifierMatchesPath(widget { }, pathId = 5)
    }

    @Test
    fun `requireIdentifierMatchesPath rejects a resource id that differs from the path id`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                IdentifierSupport.requireIdentifierMatchesPath(widget { id = 7 }, pathId = 5)
            }
        assertTrue(exception.message!!.contains("7"))
        assertTrue(exception.message!!.contains("5"))
    }

    @Test
    fun `requireIdentifierMatchesPath is a no-op for a message with no IDENTIFIER field`() {
        IdentifierSupport.requireIdentifierMatchesPath(note { message = "m" }, pathId = 5)
    }
}
