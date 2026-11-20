package no.entur.exposed.paging

import com.google.protobuf.Descriptors.OneofDescriptor
import com.google.protobuf.Message
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.StringColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less

/**
 * An [Orderable] for a `oneof`'s case - not a real [com.google.protobuf.Descriptors.FieldDescriptor]. [column] holds
 * the set variant's own proto field name (e.g. `"xsd_rule"`); [valueOf]/[decode] upper/lowercase it at the boundary so
 * it reads like a real enum value (`"XSD_RULE"`) to callers.
 */
class SortableOneofKindProperty<T : Message> internal constructor(
    override val name: String,
    override val column: Column<String>,
    private val oneof: OneofDescriptor,
) : Orderable<T> {
    init {
        require(column.columnType is StringColumnType) {
            "Column type ${column.columnType::class.simpleName} can't back oneof-kind property '$name'"
        }
    }

    override fun valueOf(value: T): Any =
        requireNotNull((value as Message).getOneofFieldDescriptor(oneof)) {
            "'$name' has no field set for oneof '${oneof.name}'"
        }.name.uppercase()

    override fun decode(raw: String?): Any? = raw?.lowercase()

    override fun lessCondition(value: Any?): Op<Boolean> = column less (value as String)

    override fun greaterCondition(value: Any?): Op<Boolean> = column greater (value as String)

    override fun equalCondition(value: Any?): Op<Boolean> = column eq (value as String)

    override fun sortOrder(ascending: Boolean): SortOrder = if (ascending) SortOrder.ASC else SortOrder.DESC
}
