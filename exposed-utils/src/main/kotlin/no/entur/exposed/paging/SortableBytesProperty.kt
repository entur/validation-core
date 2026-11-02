package no.entur.exposed.paging

import com.google.protobuf.Descriptors
import com.google.protobuf.Message
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.GreaterOp
import org.jetbrains.exposed.v1.core.LessOp
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.wrap

/**
 * [SortableProperty] for a `bytes` field. [ByteArray] isn't [Comparable], so this builds [LessOp]/[GreaterOp]
 * directly; Postgres orders `bytea` byte-by-byte.
 */
internal class SortableBytesProperty<T : Message>(
    path: List<Descriptors.FieldDescriptor>,
    private val typedColumn: Column<ByteArray>,
) : SortableProperty<T>(path, typedColumn) {
    @Suppress("UNCHECKED_CAST")
    private fun safeCast(value: Any?): ByteArray = requireNotNull(value) { "'$name' isn't nullable" } as ByteArray

    override fun lessCondition(value: Any?): Op<Boolean> = LessOp(typedColumn, typedColumn.wrap(safeCast(value)))

    override fun greaterCondition(value: Any?): Op<Boolean> = GreaterOp(typedColumn, typedColumn.wrap(safeCast(value)))

    override fun equalCondition(value: Any?): Op<Boolean> = typedColumn eq safeCast(value)

    companion object {
        fun <T : Message> create(
            path: List<Descriptors.FieldDescriptor>,
            column: Column<ByteArray>,
        ): SortableProperty<T> = SortableBytesProperty(path, column)
    }
}
