package no.entur.exposed.paging

import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.eq

/**
 * [FilterableProperty] for a singular string column: [equalCondition] is a plain, case-sensitive
 * equality check against [column].
 */
class FilterableStringProperty<T : Message> internal constructor(
    path: List<FieldDescriptor>,
    /** The column to filter by equality. */
    private val column: Column<String>,
) : FilterableProperty<T>(path) {
    init {
        require(descriptor.javaType == FieldDescriptor.JavaType.STRING) {
            "'$name' is not a string field, so it can't be filtered on as one"
        }
    }

    /** `column = value`, the `=` comparator in a `filter` expression's restriction. */
    override fun equalCondition(value: String): Op<Boolean> = column eq value
}
