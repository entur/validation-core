package no.entur.exposed.postgres

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.StringColumnType
import org.jetbrains.exposed.v1.core.Table

/**
 * A column of the Postgres enum type [typeName] (`CREATE TYPE ... AS ENUM`), holding one of [labels] -
 * typically a proto enum's constant names, with its zero value and `UNRECOGNIZED` catch-all stripped
 * out by the caller before being passed in here - see [enumLabelsFor].
 *
 * Deliberately a [StringColumnType] - a `Column<String>` of the enum constant's name - rather than an
 * Exposed `customEnumeration`: [no.entur.exposed.paging.SortableProperty] only accepts a
 * [StringColumnType] for a proto `enum` field, and keyset-compares it against the raw cursor string.
 *
 * r2dbc-postgresql binds a `String` parameter as `VARCHAR`, which Postgres won't implicitly coerce to
 * an enum type (`column "stage" is of type validation_stage but expression is of type character
 * varying`), so every parameter is cast explicitly via [parameterMarker] instead of registering an
 * r2dbc-postgresql `EnumCodec` - that would need each pooled connection to look the type's oid up
 * on connect, and a connection opened before Flyway created the type would never get the codec.
 * Reading needs nothing: r2dbc-postgresql decodes any column requested as `String` from its text.
 */
class PostgresEnumColumnType(
    val typeName: String,
    val labels: Set<String>,
) : StringColumnType() {
    override fun sqlType(): String = typeName

    override fun parameterMarker(value: String?): String = "?::$typeName"

    override fun nonNullValueToString(value: String): String = "${super.nonNullValueToString(value)}::$typeName"

    /**
     * Fails an insert/update with a value outside [labels] (e.g. a proto enum's `*_UNSPECIFIED` or
     * `UNRECOGNIZED`) as an [IllegalArgumentException] up front, instead of letting Postgres reject
     * it with an `invalid_text_representation` error once it reaches the database.
     */
    override fun validateValueBeforeUpdate(value: String?) {
        require(value == null || value in labels) {
            "'$value' is not a valid $typeName - must be one of $labels"
        }
    }
}

/** Registers a [PostgresEnumColumnType] column named [name]. */
fun Table.postgresEnum(
    name: String,
    typeName: String,
    labels: Set<String>,
): Column<String> = registerColumn(name, PostgresEnumColumnType(typeName, labels))
