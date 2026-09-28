package no.entur.proto.reflect

import com.google.api.FieldBehavior
import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Timestamp
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Memoizes a nullable per-message-[Descriptor] resolution - reflection over a message type's field
 * behavior annotations is the same answer every time for a given [Descriptor], so it's worth
 * resolving once rather than on every call. `null` is cached too (as [Optional.empty]), since a
 * message simply not declaring the field(s) in question is a legitimate, stable answer.
 */
class DescriptorCache<T : Any>(
    private val resolve: (Descriptor) -> T?,
) {
    private val cache = ConcurrentHashMap<Descriptor, Optional<T>>()

    operator fun get(descriptor: Descriptor): T? = cache.computeIfAbsent(descriptor) { Optional.ofNullable(resolve(it)) }.orElse(null)
}

/**
 * The `IDENTIFIER`/`OUTPUT_ONLY updated_at` field pair a resource message declares. Resolved once per [Descriptor]
 * and cached in [forDescriptor].
 */
data class ResourceFields(
    val idField: FieldDescriptor,
    val updatedAtField: FieldDescriptor,
) {
    companion object {
        /** The `IDENTIFIER` field types this can resolve: a `string`, or anything that fits in a [Long]. */
        private val ID_JAVA_TYPES = setOf(FieldDescriptor.JavaType.STRING, FieldDescriptor.JavaType.INT, FieldDescriptor.JavaType.LONG)

        private val cache = DescriptorCache(::resolve)

        fun forDescriptor(descriptor: Descriptor): ResourceFields? = cache[descriptor]

        /**
         * `null` if [descriptor] doesn't declare exactly one `IDENTIFIER` field and exactly one
         * `OUTPUT_ONLY` field. Once a message *does* declare both, though, they're validated here,
         * once per [descriptor], rather than on every [forDescriptor] call: an `IDENTIFIER` that's
         * neither numeric nor a `string`, an `OUTPUT_ONLY` field not named `updated_at`, or one that
         * isn't `google.protobuf.Timestamp`, is a schema bug, not a legitimately ineligible message -
         * so it throws instead of silently returning `null`.
         */
        private fun resolve(descriptor: Descriptor): ResourceFields? {
            val idField = descriptor.fieldsWithBehavior(FieldBehavior.IDENTIFIER).singleOrNull() ?: return null
            check(idField.javaType in ID_JAVA_TYPES) {
                "${idField.fullName} is declared IDENTIFIER but is a ${idField.javaType}, not a String or numeric type"
            }
            val updatedAtField = descriptor.findFieldByName("updated_at") ?: return null
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
