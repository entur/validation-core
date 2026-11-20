package no.entur.exposed.paging

import com.google.protobuf.Descriptors.OneofDescriptor
import com.google.protobuf.Message
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.StringColumnType
import org.jetbrains.exposed.v1.core.eq

/**
 * A [Filterable] for a `oneof`'s case. [equalCondition] upper/lowercases at the boundary the same way
 * [SortableOneofKindProperty.valueOf]/[SortableOneofKindProperty.decode] do, so `XSD_RULE` reads like a
 * real enum value even though [column] stores the oneof variant's own lowercase proto field name.
 */
class FilterableOneofKindProperty<T : Message> internal constructor(
    override val name: String,
    private val column: Column<String>,
    oneof: OneofDescriptor,
) : Filterable<T> {
    /** The oneof's own variant field names, upper-cased - the only ones [equalCondition] accepts. */
    private val allowedValues: Set<String> = oneof.fields.map { it.name.uppercase() }.toSet()

    init {
        require(column.columnType is StringColumnType) {
            "Column type ${column.columnType::class.simpleName} can't back oneof-kind property '$name'"
        }
    }

    override fun equalCondition(value: String): Op<Boolean> {
        require(value in allowedValues) { "Unsupported value '$value' for filter field '$name': expected one of $allowedValues" }
        return column eq value.lowercase()
    }
}
