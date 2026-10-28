package no.entur.exposed.paging

import com.google.protobuf.ByteString
import com.google.protobuf.Descriptors.EnumValueDescriptor
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType
import com.google.protobuf.Message
import com.google.protobuf.Timestamp
import no.entur.proto.time.toOffsetDateTime
import org.jetbrains.exposed.v1.core.AutoIncColumnType
import org.jetbrains.exposed.v1.core.BasicBinaryColumnType
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.FloatColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.StringColumnType
import org.jetbrains.exposed.v1.core.datetime.OffsetDateTimeColumnType
import java.time.OffsetDateTime
import java.util.Base64

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
 * Where a nullable [SortableProperty]'s absent values sort, relative to its present ones -
 * independent of [SortField.ascending], same sense as SQL's own `NULLS FIRST`/`NULLS LAST`.
 */
enum class NullsOrder {
    FIRST,
    LAST,
}

/**
 * One `order_by`-able/keyset-scrollable field of a paginated resource, supplied once per property
 * by the repository that owns both the DB column and the proto message type - see [PagingSupport]
 * for the generic (per-resource-agnostic) engine this feeds.
 *
 * Built only via each subclass's own static factory. This class's own `init` block runs
 * [requirePathValid]/[requireColumnMatches] for every subclass alike.
 *
 * [path] is the chain of proto fields from the resource's own message type down to the actual
 * sortable field. Each entry's own name is the proto's own snake_case field name (e.g. `last_progress_received_at`),
 * but [path]'s last entry is this property's own [descriptor], and its [name] - the dotted
 * join of every entry's own [FieldDescriptor.getJsonName], not its snake_case name - becomes the
 * `order_by` name clients use (e.g. `statistics.lastProgressReceivedAt`, see
 * [PagingSupport.parseOrderBy]).
 *
 * [valueOf]/[decode] are also derived from [descriptor] rather than hand-written per property:
 * every [JavaType] a single DB column could plausibly store maps onto exactly one of `ORDER BY`'s
 * own comparable SQL types, so there's nothing left for a caller to customize - including
 * `google.protobuf.Timestamp` (the only [JavaType.MESSAGE] this supports, as the only sub-message
 * type with a single, universally comparable value) and [JavaType.BYTE_STRING]. Every other
 * [JavaType.MESSAGE] field is rejected outright - it isn't one comparable column.
 *
 * A column whose stored type isn't one of the above at all - e.g. a `string` proto field backed by
 * a native UUID column - isn't rejected outright either: [SortableEntityIdProperty.create]/
 * [SortableComparableProperty.create] both take an optional [decode] of their own (e.g.
 * `Uuid::parse`), which bypasses both this class's own type-compatibility check and its built-in
 * per-[JavaType] decoding for that one property. [valueOf] never needs the same escape hatch -
 * whatever [Message.getField] returns for it already round-trips through its own `toString()`.
 */
sealed class SortableProperty<T : Message>(
    /** The chain of proto fields **/
    val path: List<FieldDescriptor>,
    /** The column to `ORDER BY`. */
    val column: Column<*>,
    /** A caller-supplied override of [decode]'s own built-in per-[JavaType] dispatch - see this class's own KDoc. */
    private val decoder: ((String) -> Any)? = null,
) {
    /** This property's own (leaf) field. */
    val descriptor: FieldDescriptor get() = path.last()

    /** The `order_by` name clients use for this property. */
    val name: String = path.joinToString(".") { it.jsonName }

    /** Non-null only for a property built via [SortableNullableProperty.create] - see [NullsOrder]. */
    open val nullsOrder: NullsOrder? = null

    init {
        requirePathValid(path)
        if (decoder == null) requireColumnMatches(descriptor, column.columnType)
    }

    /**
     * Reads this property's value off a domain object, for minting the next page's cursor - `null`
     * only for a [nullsOrder] property whose field is absent (see [Message.hasField]).
     */
    fun valueOf(value: T): Any? {
        val leaf = path.subList(0, path.lastIndex).fold(value as Message) { message, field -> message.getField(field) as Message }
        if (nullsOrder != null && !leaf.hasField(descriptor)) return null
        return when (descriptor.javaType) {
            JavaType.ENUM -> (leaf.getField(descriptor) as EnumValueDescriptor).name
            JavaType.MESSAGE -> (leaf.getField(descriptor) as Timestamp).toOffsetDateTime().toString()
            JavaType.BYTE_STRING -> Base64.getEncoder().encodeToString((leaf.getField(descriptor) as ByteString).toByteArray())
            else -> leaf.getField(descriptor)
        }
    }

    /**
     * The inverse of [valueOf]'s implicit `toString()` - reconstructs a decoded cursor value from
     * the raw string a client sent back, `null` (only ever passed for a [nullsOrder] property) back
     * to `null`. A caller-supplied [decoder] takes over entirely when there is one - see this
     * class's own KDoc.
     */
    fun decode(raw: String?): Any? {
        if (raw == null) return null
        decoder?.let { return it(raw) }
        return when (descriptor.javaType) {
            JavaType.INT -> raw.toInt()
            JavaType.LONG -> raw.toLong()
            JavaType.FLOAT -> raw.toFloat()
            JavaType.DOUBLE -> raw.toDouble()
            JavaType.BOOLEAN -> raw.toBooleanStrict()
            JavaType.STRING, JavaType.ENUM -> raw
            JavaType.MESSAGE -> OffsetDateTime.parse(raw)
            JavaType.BYTE_STRING -> Base64.getDecoder().decode(raw)
        }
    }

    /** `column < value` - see [PagingSupport.keysetCondition]. `value` is only ever null for a [nullsOrder] property. */
    abstract fun lessCondition(value: Any?): Op<Boolean>

    /** `column > value` - see [PagingSupport.keysetCondition]. `value` is only ever null for a [nullsOrder] property. */
    abstract fun greaterCondition(value: Any?): Op<Boolean>

    /** `column = value` - the "ties so far" conjunction in [PagingSupport.keysetCondition]. `value` is only ever null for a [nullsOrder] property. */
    abstract fun equalCondition(value: Any?): Op<Boolean>

    /**
     * The `ORDER BY` direction for [ascending], folding in [nullsOrder] where this property has one
     * - plain [SortOrder.ASC]/[SortOrder.DESC] otherwise. Needed because Postgres's (and the SQL
     * standard's) own default placement - `NULLS LAST` for `ASC`, `NULLS FIRST` for `DESC` - is
     * *per-direction*, whereas [NullsOrder] is a property of the field itself: whichever one a
     * caller picked for it, reversing [ascending] should reverse the non-null values' own order
     * without also flipping where the absent ones land.
     */
    fun sortOrder(ascending: Boolean): SortOrder =
        when (nullsOrder) {
            null -> if (ascending) SortOrder.ASC else SortOrder.DESC
            NullsOrder.FIRST -> if (ascending) SortOrder.ASC_NULLS_FIRST else SortOrder.DESC_NULLS_FIRST
            NullsOrder.LAST -> if (ascending) SortOrder.ASC_NULLS_LAST else SortOrder.DESC_NULLS_LAST
        }

    companion object {
        private val TIMESTAMP_FULL_NAME = Timestamp.getDescriptor().fullName

        /**
         * Strips off the wrapper [IColumnType]s Exposed puts around the actual SQL scalar type - an
         * id column's [EntityIDColumnType] (see [SortableEntityIdProperty.create]) and/or an autoincrementing
         * column's [AutoIncColumnType] (e.g. an `IdTable`'s own `id`) - so [requireColumnMatches] compares
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
         * A one-time sanity check that [path] can actually back a single sortable column at all: every
         * entry but the last must be a singular (non-`Timestamp`) message field to nest through.
         */
        private fun requirePathValid(path: List<FieldDescriptor>) {
            require(path.isNotEmpty()) { "A sortable property needs at least one field" }
            path.dropLast(1).forEach { step ->
                require(!step.isRepeated && step.javaType == JavaType.MESSAGE) {
                    "'${step.jsonName}' must be a singular message field to nest '${path.last().jsonName}' through"
                }
                require(step.messageType.fullName != TIMESTAMP_FULL_NAME) {
                    "'${step.jsonName}' is a Timestamp - it has no field of its own to nest through"
                }
            }
        }

        /**
         * The other half of [requirePathValid]: the leaf field itself must be singular and must actually resolve to
         * one comparable SQL column - every [JavaType] maps onto exactly one SQL scalar type except [JavaType.MESSAGE],
         * accepted only for a `google.protobuf.Timestamp` field backed by an [OffsetDateTimeColumnType] column. Skipped
         * entirely for a property with its own caller-supplied `decode` - see [SortableProperty]'s own KDoc.
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
                    JavaType.MESSAGE -> descriptor.messageType.fullName == TIMESTAMP_FULL_NAME && scalarType is OffsetDateTimeColumnType<*>
                    JavaType.BYTE_STRING -> scalarType is BasicBinaryColumnType
                }
            require(compatible) {
                // simpleName, not .toString() (= sqlType(), which needs a transaction/dialect this early).
                "Column type ${scalarType::class.simpleName} can't back ${descriptor.javaType} field '${descriptor.jsonName}'"
            }
        }
    }
}
