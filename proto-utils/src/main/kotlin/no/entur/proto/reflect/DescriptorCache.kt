package no.entur.proto.reflect

import com.google.api.FieldBehavior
import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Timestamp
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/** Memoizes a per-[Descriptor] lookup, including a `null` result. */
class DescriptorCache<T : Any>(
    private val resolve: (Descriptor) -> T?,
) {
    private val cache = ConcurrentHashMap<Descriptor, Optional<T>>()

    operator fun get(descriptor: Descriptor): T? = cache.computeIfAbsent(descriptor) { Optional.ofNullable(resolve(it)) }.orElse(null)
}

/** The `IDENTIFIER` and `OUTPUT_ONLY updated_at` fields a resource message declares. */
data class ResourceFields(
    val idField: FieldDescriptor,
    val updatedAtField: FieldDescriptor,
) {
    companion object {
        private val ID_JAVA_TYPES =
            setOf(FieldDescriptor.JavaType.STRING, FieldDescriptor.JavaType.INT, FieldDescriptor.JavaType.LONG)

        private val cache = DescriptorCache(::resolve)

        fun forDescriptor(descriptor: Descriptor): ResourceFields? = cache[descriptor]

        /**
         * `null` unless [descriptor] declares both fields.
         *
         * @throws IllegalStateException if either is declared with the wrong type or behavior.
         */
        private fun resolve(descriptor: Descriptor): ResourceFields? {
            val idField = descriptor.fieldsWithBehavior(FieldBehavior.IDENTIFIER).singleOrNull() ?: return null
            val updatedAtField = descriptor.findFieldByName("updated_at") ?: return null
            check(idField.javaType in ID_JAVA_TYPES) {
                "${idField.fullName} is declared IDENTIFIER but is a ${idField.javaType}, not a String or numeric type"
            }
            check(updatedAtField.hasBehavior(FieldBehavior.OUTPUT_ONLY)) {
                "${updatedAtField.fullName} field must have OUTPUT_ONLY behavior"
            }
            check(
                updatedAtField.type == FieldDescriptor.Type.MESSAGE &&
                    updatedAtField.messageType.fullName == Timestamp.getDescriptor().fullName,
            ) {
                "${updatedAtField.fullName} is declared OUTPUT_ONLY but is a ${updatedAtField.type}, not google.protobuf.Timestamp"
            }

            return ResourceFields(idField, updatedAtField)
        }
    }
}
