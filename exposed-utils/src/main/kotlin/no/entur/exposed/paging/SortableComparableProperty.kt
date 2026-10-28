package no.entur.exposed.paging

import com.google.protobuf.Descriptors
import com.google.protobuf.Message
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less

/**
 * [SortableProperty] for a plain (non-`EntityID`) column of any comparable [V] - a `varchar` column backed by a
 * `string`/`enum` proto field, a plain (non-id/FK) numeric column, or an
 * [org.jetbrains.exposed.v1.core.datetime.OffsetDateTimeColumnType] column backed by a
 * `google.protobuf.Timestamp` field, for example. [decode] is [create]'s own optional parameter,
 * see [SortableProperty]'s KDoc.
 */
internal class SortableComparableProperty<T : Message, V : Comparable<V>>(
    path: List<Descriptors.FieldDescriptor>,
    private val typedColumn: Column<V>,
    decode: ((String) -> V)? = null,
) : SortableProperty<T>(path, typedColumn, decode) {
    @Suppress("UNCHECKED_CAST")
    private fun safeCast(value: Any?): V = requireNotNull(value) { "'$name' isn't nullable" } as V

    override fun lessCondition(value: Any?): Op<Boolean> = typedColumn less safeCast(value)

    override fun greaterCondition(value: Any?): Op<Boolean> = typedColumn greater safeCast(value)

    override fun equalCondition(value: Any?): Op<Boolean> = typedColumn eq safeCast(value)

    companion object {
        fun <T : Message, V : Comparable<V>> create(
            path: List<Descriptors.FieldDescriptor>,
            column: Column<V>,
            decode: ((String) -> V)? = null,
        ): SortableProperty<T> = SortableComparableProperty(path, column, decode)
    }
}
