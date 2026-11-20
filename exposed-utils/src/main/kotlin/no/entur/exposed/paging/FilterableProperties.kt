package no.entur.exposed.paging

import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message
import org.jetbrains.exposed.v1.core.Column

/**
 * The `filter`-able properties of one paginated resource, for [PagingSupport.parseFilter]:
 * ```
 * FilterableProperties.builder<ValidationRule>(ValidationRule.getDescriptor())
 *     .field("default_severity", ValidationRuleTable.defaultSeverity)
 *     .field("stage", ValidationRuleTable.stage)
 *     .oneofKind("ruleKind", "rule", ValidationRuleTable.ruleKind)
 *     .build()
 * ```
 * The last line is for a `oneof`'s own case, e.g. which variant of `ValidationRule`'s `rule` oneof
 * (`xsd_rule`/`xpath_rule`/`sql_rule`) is set - see [Builder.oneofKind].
 *
 * Restricted to enum-column equality (see [FilterableEnumProperty]) - the subset of
 * https://google.aip.dev/160 this library supports: `=`, `AND`, `OR`, and parenthesized grouping.
 */
class FilterableProperties<T : Message> internal constructor(
    private val fields: List<Filterable<T>>,
) {
    /** Whether [name] is a known `filter` property name. */
    operator fun contains(name: String): Boolean = fields.any { it.name == name }

    /** The property with the `filter` name [name]. */
    fun byName(name: String): Filterable<T> = requireNotNull(fields.find { it.name == name }) { "Unsupported filter field: $name" }

    /**
     * Fluent builder for [FilterableProperties].
     *
     * `name` is the proto's snake_case field name, not the JSON name, optionally a dotted path into singular
     * sub-messages (`"statistics.last_progress_received_at"`) - matching [SortableProperties.Builder]'s convention.
     */
    class Builder<T : Message> internal constructor(
        private val descriptor: Descriptor,
    ) {
        private val fields = mutableListOf<Filterable<T>>()

        /** An enum-typed field's column, equality-filterable against its proto enum's own declared values. */
        fun field(
            name: String,
            column: Column<String>,
        ): Builder<T> = apply { fields += FilterableEnumProperty(pathNamed(name), column) }

        /**
         * A `oneof`'s own case, stored as [column] - see [SortableProperties.Builder.oneofKind]'s KDoc,
         * which this mirrors exactly (same [name]/[oneofFieldName] meaning).
         */
        fun oneofKind(
            name: String,
            oneofFieldName: String,
            column: Column<String>,
        ): Builder<T> =
            apply {
                val oneof =
                    requireNotNull(descriptor.oneofs.find { it.name == oneofFieldName }) {
                        "Unknown oneof '$oneofFieldName' on ${descriptor.fullName}"
                    }
                fields += FilterableOneofKindProperty(name, column, oneof)
            }

        /** The finished, immutable [FilterableProperties]. */
        fun build(): FilterableProperties<T> = FilterableProperties(fields.toList())

        /** The proto fields along [name]'s dotted path, from the resource's message down to the leaf. */
        private fun pathNamed(name: String): List<FieldDescriptor> = pathNamed(descriptor, name.split("."), name)

        /** The recursive step behind [pathNamed]'s other overload. */
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
        /** Starts building the [FilterableProperties] for messages described by [descriptor]. */
        fun <T : Message> builder(descriptor: Descriptor): Builder<T> = Builder(descriptor)
    }
}
