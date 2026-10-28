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
 *     .fieldNullsLast("nullable", ValidationRuleTable.nullable)
 *     .build()
 * ```
 */
class SortableProperties<T : Message> internal constructor(
    /** Every property that's (part of) the resource's own id, in declaration order - see [PagingSupport.parseOrderBy]'s tie-break. */
    val ids: List<SortableProperty<T>>,
    /** The rest of the properties that this resource can be sorted by **/
    private val fields: List<SortableProperty<T>>,
) {
    /** Whether [name] (an `order_by` name - a property's own [SortableProperty.name]) is a known property. */
    operator fun contains(name: String): Boolean = (ids.asSequence() + fields).any { it.name == name }

    /** The property named [name] - see [contains]. */
    fun byName(name: String): SortableProperty<T> =
        requireNotNull((ids.asSequence() + fields).find { it.name == name }) { "Unsupported order_by field: $name" }

    /**
     * A fluent builder over [SortableProperty]. Declares a resource's whole [SortableProperties] in one place.
     *
     * [id] and [field] are otherwise identical - both accept the same column shapes - the only
     * difference is which list they append to: [id] for a column that's (part of) *this*
     * resource's own id, [field] for one that isn't (e.g. an `EntityID`-shaped FK reference to a
     * *different* resource, still needing the `EntityID`-aware overload but not part of this
     * resource's own identity).
     *
     * [name] takes the proto's own snake_case field name(s) - e.g. `"last_progress_received_at"`,
     * *not* `"lastProgressReceivedAt"`. It may be a dotted path (e.g. `"statistics.last_progress_received_at"`) to
     * sort by a field nested inside one of this resource's own singular sub-messages..
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
        ): Builder<T> = apply { ids += SortableEntityIdProperty.create(pathNamed(name), column) }

        /**
         * A FK-reference column, *not* part of this resource's own id.
         */
        @JvmName("fieldEntityId")
        fun <V : Comparable<V>> field(
            name: String,
            column: Column<EntityID<V>>,
        ): Builder<T> = apply { fields += SortableEntityIdProperty.create(pathNamed(name), column) }

        /** A plain (non-`EntityID`), [Comparable]-valued column. */
        fun <V : Comparable<V>> field(
            name: String,
            column: Column<V>,
        ): Builder<T> = apply { fields += SortableComparableProperty.create(pathNamed(name), column) }

        /**
         * A `bytes` field's column.
         */
        @JvmName("fieldBytes")
        fun field(
            name: String,
            column: Column<ByteArray>,
        ): Builder<T> = apply { fields += SortableBytesProperty.create(pathNamed(name), column) }

        /**
         * A nullable column backing an `optional` field where nulls sort first, independently of a later caller's own
         * ascending/descending choice - see [SortableNullableProperty.create]/[NullsOrder].
         */
        fun <V : Comparable<V>> fieldNullsFirst(
            name: String,
            column: Column<V?>,
        ): Builder<T> = apply { fields += SortableNullableProperty.create(pathNamed(name), column, NullsOrder.FIRST) }

        /**
         * A nullable column backing an `optional` field where nulls sort last, independent of a later caller's own
         * ascending/descending choice - see [SortableNullableProperty.create]/[NullsOrder].
         */
        fun <V : Comparable<V>> fieldNullsLast(
            name: String,
            column: Column<V?>,
        ): Builder<T> = apply { fields += SortableNullableProperty.create(pathNamed(name), column, NullsOrder.LAST) }

        /**
         * The finished, immutable [SortableProperties].
         */
        fun build(): SortableProperties<T> =
            SortableProperties(
                Collections.unmodifiableList(ids.toList()),
                Collections.unmodifiableList(fields.toList()),
            )

        /**
         * Resolves a dotted `name` (e.g. `"statistics.last_progress_received_at"`) into the chain of
         * proto fields it names, walking one singular message field at a time from [descriptor]. Every segment but the last must itself be a message field to descend into. A nesting mistake is
         * reported against the exact segment that caused it.
         */
        private fun pathNamed(name: String): List<FieldDescriptor> = pathNamed(descriptor, name.split("."), name)

        /** The recursive step behind [pathNamed]'s other overload. */
        private fun pathNamed(
            owner: Descriptor,
            remaining: List<String>,
            name: String,
        ): List<FieldDescriptor> {
            val segment = remaining.first()
            val field = requireNotNull(owner.findFieldByName(segment)) { "Unknown proto field '$segment' on ${owner.fullName}" }
            val rest = remaining.drop(1)
            if (rest.isEmpty()) return listOf(field)
            require(field.javaType == FieldDescriptor.JavaType.MESSAGE) {
                "'$segment' on ${owner.fullName} is not a message field, so '$name' can't nest through it"
            }
            return listOf(field) + pathNamed(field.messageType, rest, name)
        }
    }

    companion object {
        /** Starts building the [SortableProperties] for a resource whose proto message has [descriptor]. */
        fun <T : Message> builder(descriptor: Descriptor): Builder<T> = Builder(descriptor)
    }
}
