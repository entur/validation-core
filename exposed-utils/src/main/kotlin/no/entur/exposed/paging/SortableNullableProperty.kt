package no.entur.exposed.paging

import com.google.protobuf.Descriptors
import com.google.protobuf.Message
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or

/**
 * [SortableProperty] for a nullable column backing an `optional` field. SQL `<`/`>` against `NULL`
 * is `UNKNOWN`, so the conditions handle absent values explicitly per [nullsOrder].
 */
internal class SortableNullableProperty<T : Message, V : Comparable<V>>(
    path: List<Descriptors.FieldDescriptor>,
    private val typedColumn: Column<V?>,
    override val nullsOrder: NullsOrder,
) : SortableProperty<T>(path, typedColumn) {
    @Suppress("UNCHECKED_CAST")
    private fun safeCast(value: Any): V = value as V

    override fun lessCondition(value: Any?): Op<Boolean> = afterCondition(value) { typedColumn less safeCast(it) }

    override fun greaterCondition(value: Any?): Op<Boolean> = afterCondition(value) { typedColumn greater safeCast(it) }

    override fun equalCondition(value: Any?): Op<Boolean> = if (value == null) typedColumn.isNull() else typedColumn eq safeCast(value)

    /**
     * A null [value] means the cursor is inside the absent block: every present row lies ahead with
     * [NullsOrder.FIRST], none with [NullsOrder.LAST] (later `order_by` fields break the tie).
     */
    private inline fun afterCondition(
        value: Any?,
        nonNullCondition: (Any) -> Op<Boolean>,
    ): Op<Boolean> =
        when (nullsOrder) {
            NullsOrder.LAST -> if (value != null) nonNullCondition(value) or typedColumn.isNull() else Op.FALSE
            NullsOrder.FIRST -> if (value != null) nonNullCondition(value) else typedColumn.isNotNull()
        }

    companion object {
        fun <T : Message, V : Comparable<V>> create(
            path: List<Descriptors.FieldDescriptor>,
            column: Column<V?>,
            nullsOrder: NullsOrder,
        ): SortableProperty<T> {
            val descriptor = path.last()
            require(descriptor.hasPresence()) {
                "'${descriptor.jsonName}' can't track an absent value - it isn't an `optional` field"
            }
            return SortableNullableProperty(path, column, nullsOrder)
        }
    }
}
