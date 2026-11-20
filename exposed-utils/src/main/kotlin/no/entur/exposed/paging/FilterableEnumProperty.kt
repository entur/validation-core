package no.entur.exposed.paging

import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message
import no.entur.exposed.postgres.enumLabelsFor
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.eq

/**
 * [FilterableProperty] for a singular enum column: [equalCondition] checks the right-hand side against
 * the leaf field's own proto enum's declared values (via [enumLabelsFor]) - the same values
 * [no.entur.exposed.postgres.PostgresEnumColumnType] accepts.
 */
class FilterableEnumProperty<T : Message> internal constructor(
    path: List<FieldDescriptor>,
    /** The column to filter by equality. */
    private val column: Column<String>,
) : FilterableProperty<T>(path) {
    init {
        require(descriptor.javaType == FieldDescriptor.JavaType.ENUM) {
            "'$name' is not an enum field, so it can't be filtered on"
        }
    }

    /** The leaf field's proto enum's own declared values, the only ones [equalCondition] accepts. */
    private val allowedValues: Set<String> = enumLabelsFor(descriptor.enumType)

    /**
     * `column = value`, the `=` comparator in a `filter` expression's restriction.
     *
     * @throws IllegalArgumentException if [value] isn't one of this field's proto enum's declared values.
     */
    override fun equalCondition(value: String): Op<Boolean> {
        require(value in allowedValues) { "Unsupported value '$value' for filter field '$name': expected one of $allowedValues" }
        return column eq value
    }
}
