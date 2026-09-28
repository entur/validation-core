package no.entur.exposed.paging

import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import java.util.Collections

/**
 * The `order_by`-able property list for one paginated resource - what every [PagingSupport]
 * function takes. Build one via [builder]/[Builder], e.g.:
 * ```
 * SortableProperties.builder<ValidationRule>(ValidationRule.getDescriptor())
 *     .id("id", ValidationRuleTable.id)
 *     .field("area", ValidationRuleTable.areaId)
 *     .build()
 * ```
 */
class SortableProperties<T : Message> internal constructor(
    /** Every property that's (part of) the resource's own id, in declaration order - see [PagingSupport.parseOrderBy]'s tie-break. */
    val ids: List<SortableProperty<T>>,
    /** The rest of the properties that this resource can be sorted by **/
    private val fields: List<SortableProperty<T>>,
) {
    /** Whether [name] (an `order_by` name - a property's own [SortableProperty.descriptor] JSON name) is a known property. */
    operator fun contains(name: String): Boolean = (ids.asSequence() + fields).any { it.descriptor.jsonName == name }

    /** The property named [name] - see [contains]. */
    fun byName(name: String): SortableProperty<T> =
        requireNotNull((ids.asSequence() + fields).find { it.descriptor.jsonName == name }) { "Unsupported order_by field: $name" }

    /**
     * A small fluent builder over [SortableProperty] - see its own KDoc for what each entry means,
     * and [SortableProperty.ofEntityId]/[SortableProperty.of] for the factories each method here
     * delegates to. Declares a resource's whole [SortableProperties] in one place, one field at a
     * time, instead of constructing each [SortableProperty] (and looking up its [FieldDescriptor]
     * by name) by hand.
     *
     * [id] and [field] are otherwise identical - both accept the same column shapes - the only
     * difference is which list they append to: [id] for a column that's (part of) *this*
     * resource's own id, [field] for one that isn't (e.g. an `EntityID`-shaped FK reference to a
     * *different* resource, still needing the `EntityID`-aware overload but not part of this
     * resource's own identity).
     */
    class Builder<T : Message> internal constructor(
        private val descriptor: Descriptor,
    ) {
        private val ids = mutableListOf<SortableProperty<T>>()
        private val fields = mutableListOf<SortableProperty<T>>()

        /** An id/FK-reference column, part of this resource's own id. */
        fun <V : Comparable<V>> id(
            name: String,
            column: Column<EntityID<V>>,
        ): Builder<T> = apply { ids += SortableProperty.ofEntityId(fieldNamed(name), column) }

        /**
         * A FK-reference column, *not* part of this resource's own id. `@JvmName` because `Column<EntityID<V>>`
         * and `Column<V>` both erase to plain `Column` - without it, the two clash as the same JVM
         * method signature despite being distinct overloads in Kotlin source.
         */
        @JvmName("fieldEntityId")
        fun <V : Comparable<V>> field(
            name: String,
            column: Column<EntityID<V>>,
        ): Builder<T> = apply { fields += SortableProperty.ofEntityId(fieldNamed(name), column) }

        /** A plain (non-`EntityID`) column. */
        fun <V : Comparable<V>> field(
            name: String,
            column: Column<V>,
        ): Builder<T> = apply { fields += SortableProperty.of(fieldNamed(name), column) }

        /**
         * The finished, immutable [SortableProperties].
         */
        fun build(): SortableProperties<T> =
            SortableProperties(
                Collections.unmodifiableList(ids.toList()),
                Collections.unmodifiableList(fields.toList()),
            )

        private fun fieldNamed(name: String): FieldDescriptor =
            requireNotNull(descriptor.findFieldByName(name)) { "Unknown proto field '$name' on ${descriptor.fullName}" }
    }

    companion object {
        /** Starts building the [SortableProperties] for a resource whose proto message has [descriptor]. */
        fun <T : Message> builder(descriptor: Descriptor): Builder<T> = Builder(descriptor)
    }
}
