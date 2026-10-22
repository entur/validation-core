package no.entur.exposed.postgres

import org.jetbrains.exposed.v1.core.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Unit-level coverage for [PostgresEnumColumnType]'s SQL-fragment generation and label validation -
 * no DB, no Spring context. [PostgresEnumColumnTypePostgresTest] covers the cast actually working
 * against a real Postgres enum type.
 */
internal class PostgresEnumColumnTypeTest {
    private val columnType = PostgresEnumColumnType("my_enum", setOf("ALPHA", "BRAVO"))

    @Test
    fun `sqlType is the Postgres enum type name`() {
        assertEquals("my_enum", columnType.sqlType())
    }

    @Test
    fun `parameterMarker casts the bind placeholder to the enum type`() {
        assertEquals("?::my_enum", columnType.parameterMarker("ALPHA"))
    }

    @Test
    fun `nonNullValueToString casts the literal to the enum type`() {
        assertEquals("'ALPHA'::my_enum", columnType.nonNullValueToString("ALPHA"))
    }

    @Test
    fun `validateValueBeforeUpdate accepts null and any declared label`() {
        columnType.validateValueBeforeUpdate(null)
        columnType.validateValueBeforeUpdate("ALPHA")
        columnType.validateValueBeforeUpdate("BRAVO")
    }

    @Test
    fun `validateValueBeforeUpdate rejects a value outside labels`() {
        val exception = assertThrows(IllegalArgumentException::class.java) { columnType.validateValueBeforeUpdate("CHARLIE") }
        assertEquals("'CHARLIE' is not a valid my_enum - must be one of [ALPHA, BRAVO]", exception.message)
    }

    @Test
    fun `postgresEnum registers a column of PostgresEnumColumnType on the table`() {
        val column = TestTable.status
        assertEquals("status", column.name)

        val registeredType = column.columnType as PostgresEnumColumnType
        assertEquals("my_enum", registeredType.typeName)
        assertEquals(setOf("ALPHA", "BRAVO"), registeredType.labels)
    }

    private object TestTable : Table("test_table") {
        val status = postgresEnum("status", "my_enum", setOf("ALPHA", "BRAVO"))
    }
}
