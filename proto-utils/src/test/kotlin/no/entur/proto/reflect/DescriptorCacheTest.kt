package no.entur.proto.reflect

import com.google.api.FieldBehavior
import com.google.api.FieldBehaviorProto
import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors
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
            ResourceFields.forDescriptor(malformedDescriptor)
        }
    }

    companion object {
        /** A throwaway descriptor with an `IDENTIFIER id` but a plain (non-`OUTPUT_ONLY`) `updated_at` field - violates [ResourceFields]'s documented invariant. */
        private val malformedDescriptor: Descriptors.Descriptor by lazy {
            fun fieldBehaviorOptions(behavior: FieldBehavior): DescriptorProtos.FieldOptions =
                DescriptorProtos.FieldOptions
                    .newBuilder()
                    .addExtension(FieldBehaviorProto.fieldBehavior, behavior)
                    .build()

            val malformed =
                DescriptorProtos.DescriptorProto
                    .newBuilder()
                    .setName("Malformed")
                    .addField(
                        DescriptorProtos.FieldDescriptorProto
                            .newBuilder()
                            .setName("id")
                            .setNumber(1)
                            .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                            .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT64)
                            .setOptions(fieldBehaviorOptions(FieldBehavior.IDENTIFIER)),
                    ).addField(
                        // Named "updated_at" but missing the OUTPUT_ONLY annotation entirely - a schema bug.
                        DescriptorProtos.FieldDescriptorProto
                            .newBuilder()
                            .setName("updated_at")
                            .setNumber(2)
                            .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                            .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING),
                    )

            val fileProto =
                DescriptorProtos.FileDescriptorProto
                    .newBuilder()
                    .setName("descriptor_cache_test.proto")
                    .setSyntax("proto3")
                    .setPackage("no.entur.proto.reflect.test")
                    .addDependency(FieldBehaviorProto.getDescriptor().file.name)
                    .addMessageType(malformed)
                    .build()

            Descriptors.FileDescriptor
                .buildFrom(fileProto, arrayOf(FieldBehaviorProto.getDescriptor().file))
                .messageTypes
                .single()
        }
    }
}
