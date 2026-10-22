package no.entur.exposed.postgres

import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * [PostgresEnumColumnType] against a real Postgres: checks that [PostgresEnumColumnType.parameterMarker]'s
 * explicit `::typeName` cast actually lets r2dbc-postgresql's `VARCHAR`-bound parameter satisfy a
 * genuine Postgres enum column (rather than failing with `column ... is of type ... but expression
 * is of type character varying`), and that the stored value reads back unchanged.
 * [PostgresEnumColumnTypeTest] covers everything else without a DB.
 */
@Testcontainers
internal class PostgresEnumColumnTypePostgresTest {
    @Test
    fun `a value cast via parameterMarker inserts into and reads back from a genuine Postgres enum column`() =
        runTest {
            suspendTransaction(db = database) {
                StatusTable.insert {
                    it[id] = 1
                    it[status] = "ALPHA"
                }
            }

            val stored =
                suspendTransaction(db = database) {
                    StatusTable.select(StatusTable.status).where { StatusTable.id eq 1 }.single()[StatusTable.status]
                }

            assertEquals("ALPHA", stored)
        }

    @Test
    fun `an insert with a value outside labels fails before reaching the database`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                runTest {
                    suspendTransaction(db = database) {
                        StatusTable.insert {
                            it[id] = 2
                            it[status] = "NOT_A_LABEL"
                        }
                    }
                }
            }

        assertEquals("'NOT_A_LABEL' is not a valid my_status - must be one of [ALPHA, BRAVO]", exception.message)
    }

    /** A table with one genuine Postgres enum column, created via raw SQL like a Flyway migration would. */
    private object StatusTable : Table("status_table") {
        val id = integer("id")
        val status = postgresEnum("status", "my_status", setOf("ALPHA", "BRAVO"))
        override val primaryKey = PrimaryKey(id)
    }

    companion object {
        @Container
        @JvmField
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")

        private lateinit var database: R2dbcDatabase

        @BeforeAll
        @JvmStatic
        fun setUp() {
            database =
                R2dbcDatabase.connect(
                    connectionFactory =
                        ConnectionFactories.get(
                            ConnectionFactoryOptions
                                .builder()
                                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                                .option(ConnectionFactoryOptions.HOST, postgres.host)
                                .option(ConnectionFactoryOptions.PORT, postgres.getMappedPort(5432))
                                .option(ConnectionFactoryOptions.DATABASE, postgres.databaseName)
                                .option(ConnectionFactoryOptions.USER, postgres.username)
                                .option(ConnectionFactoryOptions.PASSWORD, postgres.password)
                                .build(),
                        ),
                    databaseConfig = R2dbcDatabaseConfig { explicitDialect = PostgreSQLDialect() },
                )
            runBlocking {
                suspendTransaction(db = database) {
                    exec("CREATE TYPE my_status AS ENUM ('ALPHA', 'BRAVO')")
                    exec("CREATE TABLE status_table (id INTEGER PRIMARY KEY, status my_status NOT NULL)")
                }
            }
        }
    }
}
