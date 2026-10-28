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
 * [SortableProperty] for a nullable, plain (non-`EntityID`), [Comparable]-valued column,
 * backing an `optional` proto field. [lessCondition]/[greaterCondition] fold
 * [nullsOrder] in directly (see [afterCondition]): whichever of the two represents "the next
 * row in this direction" also has to say where that leaves an absent value, since plain SQL
 * `<`/`>` against a `NULL` column is always `UNKNOWN` rather than *not* matching the way "nothing sorts past the end"
 * would want.
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
     * The shared shape behind [lessCondition]/[greaterCondition]: [value] null means the cursor
     * itself was already resting on an absent value, so either every present row still lies ahead ([NullsOrder.FIRST])
     * or none do ([NullsOrder.LAST] - only a tie on a later `order_by` field could place anything "after" it).
     * [value] non-null runs [nonNullCondition] as normal, additionally matching every absent
     * row too when [nullsOrder] is [NullsOrder.LAST]. [NullsOrder.FIRST] needs no such addition, since the
     * absent block already lies entirely behind a present row by then.
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
