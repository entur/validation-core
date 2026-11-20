package no.entur.exposed.paging

import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message
import org.jetbrains.exposed.v1.core.Op

/**
 * The `filter`-able contract [PagingSupport.parseFilter], [FilterParser] and [FilterableProperties] need -
 * [FilterableProperty]'s public shape, widened to an interface so a property that isn't backed by a real
 * [FieldDescriptor] (e.g. [FilterableOneofKindProperty]) can implement it too.
 */
interface Filterable<T : Message> {
    /** The `filter` name clients use for this property. */
    val name: String

    /**
     * `column = value`, the `=` comparator in a `filter` expression's restriction.
     *
     * @throws IllegalArgumentException if [value] isn't one of this field's allowed values.
     */
    fun equalCondition(value: String): Op<Boolean>
}

/**
 * One `filter`-able field of a paginated resource, fed to [PagingSupport.parseFilter]. Its [name] is
 * the dotted JSON names of [path], matching [SortableProperty]'s convention.
 *
 * Sealed, so a future leaf-type filterable column can be added as a new subclass without touching
 * [FilterableProperties]/[FilterParser]. ([FilterableEnumProperty] is the only one for now.)
 */
sealed class FilterableProperty<T : Message>(
    /** The proto fields from the resource's message down to the filterable leaf field. */
    val path: List<FieldDescriptor>,
) : Filterable<T> {
    /** This property's (leaf) field. */
    protected val descriptor: FieldDescriptor get() = path.last()

    /** The `filter` name clients use for this property. */
    override val name: String = path.joinToString(".") { it.jsonName }

    init {
        require(path.dropLast(1).all { !it.isRepeated && it.javaType == FieldDescriptor.JavaType.MESSAGE }) {
            "'$name' nests through a field that isn't a singular message field"
        }
        require(!descriptor.isRepeated) {
            "Field '${descriptor.jsonName}' is repeated, which can't back a single-valued filterable column"
        }
    }
}
