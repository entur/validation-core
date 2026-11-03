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
import java.time.format.DateTimeParseException
import java.util.Base64

/** One field a paginated query sorts and keyset-scrolls by. */
data class SortField(
    val property: String,
    val ascending: Boolean = true,
)

/** A keyset-scrolled page of results, plus the [sort] needed to mint the next cursor. */
data class Page<T>(
    val content: List<T>,
    val hasNext: Boolean,
    val sort: List<SortField>,
)

/** A [Page]'s content with its opaque next-page cursor ([PagingSupport.nextCursor]), null on the last page. */
data class CursorPage<T>(
    val content: List<T>,
    val cursor: String?,
)

/** Where a nullable [SortableProperty]'s absent values sort, independent of [SortField.ascending]. */
enum class NullsOrder {
    FIRST,
    LAST,
}

/**
 * One `order_by`-able, keyset-scrollable field of a paginated resource, fed to [PagingSupport].
 * Its [name] is the dotted JSON names of [path] (e.g. `statistics.lastProgressReceivedAt`).
 *
 * Cursor encoding/decoding is derived from the leaf field's [JavaType]; `google.protobuf.Timestamp`
 * is the only message type accepted, since no other has one comparable value. A column stored as
 * some other type (e.g. a `string` field backed by a native UUID column) needs a `decode` override
 * on the subclass factory, which also skips the column-type check.
 */
sealed class SortableProperty<T : Message>(
    /** The proto fields from the resource's message down to the sortable leaf field. */
    val path: List<FieldDescriptor>,
    /** The column to `ORDER BY`. */
    val column: Column<*>,
    /** Overrides [decode]'s built-in per-[JavaType] parsing when set. */
    private val decoder: ((String) -> Any)? = null,
) {
    /** This property's (leaf) field. */
    val descriptor: FieldDescriptor get() = path.last()

    /** The `order_by` name clients use for this property. */
    val name: String = path.joinToString(".") { it.jsonName }

    /** Non-null only for a property built via [SortableNullableProperty.create] - see [NullsOrder]. */
    open val nullsOrder: NullsOrder? = null

    init {
        requirePathValid(path)
        if (decoder == null) requireColumnMatches(descriptor, column.columnType)
    }

    /** This property's value on [value], for the next cursor; `null` only for an absent [nullsOrder] field. */
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
     * Parses a cursor value a client sent back: the inverse of [valueOf]'s `toString()`. [decoder] takes over when
     * set.
     *
     * @throws IllegalArgumentException if [raw] is malformed.
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
            JavaType.MESSAGE -> parseTimestamp(raw)
            JavaType.BYTE_STRING -> Base64.getDecoder().decode(raw)
        }
    }

    /**
     * [OffsetDateTime.parse], rewrapping its [DateTimeParseException] as an [IllegalArgumentException] like every
     * other [decode] failure.
     */
    private fun parseTimestamp(raw: String): OffsetDateTime =
        try {
            OffsetDateTime.parse(raw)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("Invalid value for '$name': '$raw' is not a valid Timestamp", e)
        }

    /** `column < value` (see [PagingSupport.keysetCondition]); `value` is null only for a [nullsOrder] property. */
    abstract fun lessCondition(value: Any?): Op<Boolean>

    /** `column > value` (see [PagingSupport.keysetCondition]); `value` is null only for a [nullsOrder] property. */
    abstract fun greaterCondition(value: Any?): Op<Boolean>

    /** `column = value`, the ties so far in [PagingSupport.keysetCondition]; `value` is null only for a [nullsOrder] property. */
    abstract fun equalCondition(value: Any?): Op<Boolean>

    /**
     * The `ORDER BY` direction for [ascending], with [nullsOrder] spelled out: SQL's default null placement flips with
     * the direction, but reversing [ascending] must not move the absent values.
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
         * Strips Exposed's [EntityIDColumnType]/[AutoIncColumnType] wrappers so [requireColumnMatches] sees the stored
         * type. Does not see through `.transform()` columns, so those fail [requireColumnMatches] for now.
         */
        private tailrec fun unwrapColumnType(columnType: IColumnType<*>): IColumnType<*> =
            when (columnType) {
                is EntityIDColumnType<*> -> unwrapColumnType(columnType.idColumn.columnType)
                is AutoIncColumnType<*> -> unwrapColumnType(columnType.delegate)
                else -> columnType
            }

        /**
         * Checks that [path] can back one sortable column: every entry but the last must be a singular,
         * non-`Timestamp` message field.
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
         * Checks that the leaf [descriptor] is singular and matches [columnType]'s SQL type. A
         * `google.protobuf.Timestamp` field needs an [OffsetDateTimeColumnType] column.
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
