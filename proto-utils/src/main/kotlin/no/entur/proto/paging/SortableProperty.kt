package no.entur.proto.paging

import com.google.protobuf.Descriptors.EnumValueDescriptor
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType
import com.google.protobuf.Message
import org.jetbrains.exposed.v1.core.AutoIncColumnType
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.FloatColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.StringColumnType
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less

/** One field a paginated query sorts and keyset-scrolls by - see [SortableProperty]. */
data class SortField(
    val property: String,
    val ascending: Boolean = true,
)

/** A keyset-scrolled page of results, plus the [sort] it was fetched with (needed to mint the next cursor). */
data class Page<T>(
    val content: List<T>,
    val hasNext: Boolean,
    val sort: List<SortField>,
)

/**
 * A [Page]'s content together with its already-minted opaque next-page cursor (see
 * [PagingSupport.nextCursor]), null if there isn't one - what a repository's own `findAll` returns
 * to its caller, once [Page.sort] itself has served its only purpose (minting that cursor) and has
 * nothing left to offer a caller outside the repository.
 */
data class CursorPage<T>(
    val content: List<T>,
    val cursor: String?,
)

/**
 * One `order_by`-able/keyset-scrollable field of a paginated resource, supplied once per property
 * by the repository that owns both the DB column and the proto message type - see [PagingSupport]
 * for the generic (per-resource-agnostic) engine this feeds.
 *
 * Built only via [ofEntityId]/[of] below, which return one of the two `private` subclasses -
 * [EntityIdProperty] for a `Column<EntityID<V>>`, [PlainProperty] for a plain `Column<V>`. Exposed
 * resolves `less`/`greater`/`eq` to a different overload for each of those two column shapes, so
 * there's no single implementation that works for both. This class's own `init` block runs [requireColumnMatches]
 * for both subclasses alike.
 *
 * [descriptor] is the corresponding proto field: its [FieldDescriptor.getJsonName] becomes the
 * `order_by` name clients use - see [PagingSupport.parseOrderBy] - so it can't drift from the
 * resource's own schema the way a hand-maintained string constant could. [valueOf]/[decode] are
 * also derived from it rather than hand-written per property: every [JavaType] a single DB column
 * could plausibly store (everything except [JavaType.MESSAGE], which isn't one column, and
 * [JavaType.BYTE_STRING], which isn't [Comparable]) maps onto exactly one of `ORDER BY`'s own
 * comparable SQL types, so there's nothing left for a caller to customize.
 */
sealed class SortableProperty<T : Message>(
    val descriptor: FieldDescriptor,
    /** The column to `ORDER BY`. */
    val column: Column<*>,
) {
    init {
        requireColumnMatches(descriptor, column.columnType)
    }

    /** Reads this property's value off a domain object, for minting the next page's cursor. */
    fun valueOf(value: T): Any =
        if (descriptor.javaType == JavaType.ENUM) {
            (value.getField(descriptor) as EnumValueDescriptor).name
        } else {
            value.getField(descriptor)
        }

    /** The inverse of [valueOf]'s implicit `toString()` - reconstructs a decoded cursor value from the raw string a client sent back. */
    fun decode(raw: String): Any =
        when (descriptor.javaType) {
            JavaType.INT -> raw.toInt()
            JavaType.LONG -> raw.toLong()
            JavaType.FLOAT -> raw.toFloat()
            JavaType.DOUBLE -> raw.toDouble()
            JavaType.BOOLEAN -> raw.toBooleanStrict()
            JavaType.STRING, JavaType.ENUM -> raw
            JavaType.BYTE_STRING, JavaType.MESSAGE -> error("'${descriptor.jsonName}' can't back a sortable column")
        }

    /** `column < value` - see [PagingSupport.keysetCondition]. */
    abstract fun lessCondition(value: Any): Op<Boolean>

    /** `column > value` - see [PagingSupport.keysetCondition]. */
    abstract fun greaterCondition(value: Any): Op<Boolean>

    /** `column = value` - the "ties so far" conjunction in [PagingSupport.keysetCondition]. */
    abstract fun equalCondition(value: Any): Op<Boolean>

    /** [SortableProperty] for an id/FK-reference column - see [ofEntityId]. */
    private class EntityIdProperty<T : Message, V : Comparable<V>>(
        descriptor: FieldDescriptor,
        private val typedColumn: Column<EntityID<V>>,
    ) : SortableProperty<T>(descriptor, typedColumn) {
        @Suppress("UNCHECKED_CAST")
        private fun safeCast(value: Any): V = value as V

        override fun lessCondition(value: Any): Op<Boolean> = typedColumn less safeCast(value)

        override fun greaterCondition(value: Any): Op<Boolean> = typedColumn greater safeCast(value)

        override fun equalCondition(value: Any): Op<Boolean> = typedColumn eq safeCast(value)
    }

    /** [SortableProperty] for a plain (non-`EntityID`) column - see [of]. */
    private class PlainProperty<T : Message, V : Comparable<V>>(
        descriptor: FieldDescriptor,
        private val typedColumn: Column<V>,
    ) : SortableProperty<T>(descriptor, typedColumn) {
        @Suppress("UNCHECKED_CAST")
        private fun safeCast(value: Any): V = value as V

        override fun lessCondition(value: Any): Op<Boolean> = typedColumn less safeCast(value)

        override fun greaterCondition(value: Any): Op<Boolean> = typedColumn greater safeCast(value)

        override fun equalCondition(value: Any): Op<Boolean> = typedColumn eq safeCast(value)
    }

    companion object {
        /**
         * For an id/FK-reference column (`Column<EntityID<V>>`) of any comparable [V]. Exposed binds a
         * value against this column shape by wrapping it in an actual `EntityID`.
         */
        fun <T : Message, V : Comparable<V>> ofEntityId(
            descriptor: FieldDescriptor,
            column: Column<EntityID<V>>,
        ): SortableProperty<T> = EntityIdProperty(descriptor, column)

        /**
         * For a plain (non-`EntityID`) column of any comparable [V] - a `varchar` column backed by a
         * `string`/`enum` proto field, or a plain (non-id/FK) numeric column, for example.
         */
        fun <T : Message, V : Comparable<V>> of(
            descriptor: FieldDescriptor,
            column: Column<V>,
        ): SortableProperty<T> = PlainProperty(descriptor, column)

        /**
         * Strips off the wrapper [IColumnType]s Exposed puts around the actual SQL scalar type - an
         * id column's [EntityIDColumnType] (see [ofEntityId]) and/or an autoincrementing column's
         * [AutoIncColumnType] (e.g. an `IdTable`'s own `id`) - so [requireColumnMatches] compares
         * against the real stored type rather than a wrapper. It does *not* see through a `.transform()`
         * column (`ColumnWithTransform`/`NullableColumnWithTransform`, e.g. a value-class natural key or
         * a converted `Instant`/`OffsetDateTime`) - a resource wanting to sort by one of those would need
         * this taught about it first, since today it'd just fail [requireColumnMatches] as a mismatch.
         */
        private tailrec fun unwrapColumnType(columnType: IColumnType<*>): IColumnType<*> =
            when (columnType) {
                is EntityIDColumnType<*> -> unwrapColumnType(columnType.idColumn.columnType)
                is AutoIncColumnType<*> -> unwrapColumnType(columnType.delegate)
                else -> columnType
            }

        /**
         * A one-time sanity check that [descriptor] can actually back a single sortable column at all -
         * it must be singular (a `repeated`/map field has no one value to compare) - and that its proto
         * type and [columnType]'s actual SQL type agree, so a mismatch (on either count) fails fast at
         * startup instead of miscomparing/miscasting silently the first time a client sorts or scrolls
         * by it. Runs from [SortableProperty]'s own `init` block, so it's checked no matter which
         * subclass is being constructed.
         */
        private fun requireColumnMatches(
            descriptor: FieldDescriptor,
            columnType: IColumnType<*>,
        ) {
            require(!descriptor.isRepeated) {
                "Field '${descriptor.jsonName}' is repeated, which can't back a single-valued sortable column"
            }
            val scalarType = unwrapColumnType(columnType)
            val compatible =
                when (descriptor.javaType) {
                    JavaType.INT -> scalarType is IntegerColumnType
                    JavaType.LONG -> scalarType is LongColumnType
                    JavaType.FLOAT -> scalarType is FloatColumnType
                    JavaType.DOUBLE -> scalarType is DoubleColumnType
                    JavaType.BOOLEAN -> scalarType is BooleanColumnType
                    JavaType.STRING, JavaType.ENUM -> scalarType is StringColumnType
                    JavaType.BYTE_STRING, JavaType.MESSAGE -> false
                }
            require(compatible) {
                "Column type $scalarType can't back ${descriptor.javaType} field '${descriptor.jsonName}'"
            }
        }
    }
}
