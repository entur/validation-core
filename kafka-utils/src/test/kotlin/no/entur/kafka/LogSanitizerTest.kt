package no.entur.kafka

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class LogSanitizerTest {
    private val lineSeparator = Char(0x2028)
    private val paragraphSeparator = Char(0x2029)

    @Test
    fun `replaces a newline and other ISO control characters`() {
        assertEquals("a_b_c", sanitizeForLog("a\nb\tc"))
    }

    @Test
    fun `replaces the line and paragraph separators`() {
        assertEquals("a_b_c", sanitizeForLog("a$lineSeparator" + "b$paragraphSeparator" + "c"))
    }

    @Test
    fun `returns the same instance when nothing needs replacing`() {
        val value = "perfectly safe value"
        assertSame(value, sanitizeForLog(value))
    }
}
