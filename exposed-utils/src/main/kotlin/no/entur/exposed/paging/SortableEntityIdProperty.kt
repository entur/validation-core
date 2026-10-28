package no.entur.exposed.paging

import com.google.protobuf.Descriptors
import com.google.protobuf.Message
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less

/** [SortableProperty] for an id/FK-reference column - [decode] is [create]'s own optional parameter, see [SortableProperty]'s KDoc. */
internal class SortableEntityIdProperty<T : Message, V : Comparable<V>>(
    path: List<Descriptors.FieldDescriptor>,
    private val typedColumn: Column<EntityID<V>>,
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
            column: Column<EntityID<V>>,
            decode: ((String) -> V)? = null,
        ): SortableProperty<T> = SortableEntityIdProperty(path, column, decode)
    }
}
