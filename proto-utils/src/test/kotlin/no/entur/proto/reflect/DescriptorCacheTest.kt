package no.entur.proto.reflect

import com.google.protobuf.Descriptors
import no.entur.proto.testfixtures.v1.MalformedWidget
import no.entur.proto.testfixtures.v1.Note
import no.entur.proto.testfixtures.v1.Widget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Direct unit coverage for [DescriptorCache]'s own memoization, and [ResourceFields.forDescriptor] built on top of it. */
internal class DescriptorCacheTest {
    @Test
    fun `get() returns the same cached instance on repeated calls, without re-invoking resolve`() {
        var invocations = 0
        val cache =
            DescriptorCache<Any> {
                invocations++
                Any()
            }

        val first = cache[Widget.getDescriptor()]
        val second = cache[Widget.getDescriptor()]

        assertSame(first, second)
        assertEquals(1, invocations)
    }

    @Test
    fun `a null resolution is cached too, rather than re-resolved on every call`() {
        var invocations = 0
        val cache =
            DescriptorCache<Any> {
                invocations++
                null
            }

        assertNull(cache[Widget.getDescriptor()])
        assertNull(cache[Widget.getDescriptor()])
        assertEquals(1, invocations)
    }

    @Test
    fun `distinct descriptors are cached independently`() {
        val resolved = mutableListOf<Descriptors.Descriptor>()
        val cache =
            DescriptorCache<Descriptors.Descriptor> {
                resolved += it
                it
            }

        cache[Widget.getDescriptor()]
        cache[Note.getDescriptor()]
        cache[Widget.getDescriptor()]

        assertEquals(listOf(Widget.getDescriptor(), Note.getDescriptor()), resolved)
    }

    @Test
    fun `ResourceFields forDescriptor returns the id and updated_at fields for a resource-shaped message`() {
        val fields = ResourceFields.forDescriptor(Widget.getDescriptor())

        assertEquals(Widget.getDescriptor().findFieldByName("id"), fields!!.idField)
        assertEquals(Widget.getDescriptor().findFieldByName("updated_at"), fields.updatedAtField)
    }

    @Test
    fun `ResourceFields forDescriptor returns null for a message with neither an IDENTIFIER nor OUTPUT_ONLY field`() {
        assertNull(ResourceFields.forDescriptor(Note.getDescriptor()))
    }

    @Test
    fun `ResourceFields forDescriptor throws for a schema with an updated_at field that isn't OUTPUT_ONLY`() {
        assertThrows(IllegalStateException::class.java) {
            ResourceFields.forDescriptor(MalformedWidget.getDescriptor())
        }
    }
}
