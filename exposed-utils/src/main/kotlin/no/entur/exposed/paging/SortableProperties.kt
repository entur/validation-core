package no.entur.exposed.paging

import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import java.util.Collections

/**
 * The `order_by`-able properties of one paginated resource, for [PagingSupport]:
 * ```
 * SortableProperties.builder<ValidationRule>(ValidationRule.getDescriptor())
 *     .id("id", ValidationRuleTable.id)
 *     .field("area", ValidationRuleTable.areaId)
 *     .fieldNullsLast("nullable", ValidationRuleTable.nullable)
 *     .build()
 * ```
 */
class SortableProperties<T : Message> internal constructor(
    /** The resource's id properties, appended as the tie-break by [PagingSupport.parseOrderBy]. */
    val ids: List<SortableProperty<T>>,
    private val fields: List<SortableProperty<T>>,
) {
    operator fun contains(name: String): Boolean = (ids.asSequence() + fields).any { it.name == name }

    fun byName(name: String): SortableProperty<T> =
        requireNotNull((ids.asSequence() + fields).find { it.name == name }) { "Unsupported order_by field: $name" }

    /**
     * `name` is the proto's snake_case field name, not the JSON name, optionally a dotted path into
     * singular sub-messages (`"statistics.last_progress_received_at"`).
     */
    class Builder<T : Message> internal constructor(
        private val descriptor: Descriptor,
    ) {
        private val ids = mutableListOf<SortableProperty<T>>()
        private val fields = mutableListOf<SortableProperty<T>>()

        /**
         * A column that is (part of) this resource's id. [decode] is for a column type the field type
         * doesn't imply, e.g. a `string` field backed by a native UUID column (`Uuid::parse`).
         */
        fun <V : Comparable<V>> id(
            name: String,
            column: Column<EntityID<V>>,
            decode: ((String) -> V)? = null,
        ): Builder<T> = apply { ids += SortableEntityIdProperty.create(pathNamed(name), column, decode) }

        /** An `EntityID` column that isn't part of this resource's id, e.g. a foreign key. */
        @JvmName("fieldEntityId")
        fun <V : Comparable<V>> field(
            name: String,
            column: Column<EntityID<V>>,
            decode: ((String) -> V)? = null,
        ): Builder<T> = apply { fields += SortableEntityIdProperty.create(pathNamed(name), column, decode) }

        fun <V : Comparable<V>> field(
            name: String,
            column: Column<V>,
            decode: ((String) -> V)? = null,
        ): Builder<T> = apply { fields += SortableComparableProperty.create(pathNamed(name), column, decode) }

        @JvmName("fieldBytes")
        fun field(
            name: String,
            column: Column<ByteArray>,
        ): Builder<T> = apply { fields += SortableBytesProperty.create(pathNamed(name), column) }

        /** An `optional` field whose absent values sort first in both directions. */
        fun <V : Comparable<V>> fieldNullsFirst(
            name: String,
            column: Column<V?>,
        ): Builder<T> = apply { fields += SortableNullableProperty.create(pathNamed(name), column, NullsOrder.FIRST) }

        /** An `optional` field whose absent values sort last in both directions. */
        fun <V : Comparable<V>> fieldNullsLast(
            name: String,
            column: Column<V?>,
        ): Builder<T> = apply { fields += SortableNullableProperty.create(pathNamed(name), column, NullsOrder.LAST) }

        fun build(): SortableProperties<T> =
            SortableProperties(
                Collections.unmodifiableList(ids.toList()),
                Collections.unmodifiableList(fields.toList()),
            )

        private fun pathNamed(name: String): List<FieldDescriptor> = pathNamed(descriptor, name.split("."), name)

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
        fun <T : Message> builder(descriptor: Descriptor): Builder<T> = Builder(descriptor)
    }
}
