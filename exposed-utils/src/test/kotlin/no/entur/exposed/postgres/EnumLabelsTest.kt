package no.entur.exposed.postgres

import no.entur.exposed.testfixtures.v1.WidgetStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class EnumLabelsTest {
    @Test
    fun `enumLabelsFor excludes only the zero-valued UNSPECIFIED entry`() {
        assertEquals(setOf("WIDGET_STATUS_ACTIVE", "WIDGET_STATUS_INACTIVE"), enumLabelsFor(WidgetStatus.getDescriptor()))
    }
}
