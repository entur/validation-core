package no.entur.exposed.postgres

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.StringColumnType
import org.jetbrains.exposed.v1.core.Table

/**
 * A column of the Postgres enum type [typeName], holding one of [labels] (see [enumLabelsFor]).
 *
 * A [StringColumnType], not `customEnumeration`, because [no.entur.exposed.paging.SortableProperty]
 * only accepts strings for a proto `enum` field.
 *
 * r2dbc-postgresql binds a `String` as `VARCHAR`, which Postgres won't coerce to an enum, so
 * [parameterMarker] casts explicitly. Not an r2dbc `EnumCodec`: it looks the type's oid up on connect,
 * so a connection opened before Flyway created the type would never get it.
 */
class PostgresEnumColumnType(
    val typeName: String,
    val labels: Set<String>,
) : StringColumnType() {
    override fun sqlType(): String = typeName

    override fun parameterMarker(value: String?): String = "?::$typeName"

    override fun nonNullValueToString(value: String): String = "${super.nonNullValueToString(value)}::$typeName"

    /**
     * Rejects a value outside [labels] (e.g. `*_UNSPECIFIED`) before Postgres sees it.
     *
     * @throws IllegalArgumentException if [value] isn't one of [labels].
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
