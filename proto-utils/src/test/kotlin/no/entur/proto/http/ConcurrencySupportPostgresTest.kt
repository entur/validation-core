package no.entur.proto.http

import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * `ConcurrencySupportTest` using a PostgreSQL database to check ETag consistency with JVM.
 */
@Testcontainers
internal class ConcurrencySupportPostgresTest {
    @Test
    fun `the SQL hash expression matches the JVM computation for a persisted row`() =
        runTest {
            val id = "label-1"
            val updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)

            suspendTransaction(db = database) {
                LabelTable.insert {
                    it[LabelTable.id] = id
                    it[LabelTable.updatedAt] = updatedAt.atOffset(ZoneOffset.UTC)
                }
            }

            val hashColumn = ConcurrencySupport.etagSqlExpression(LabelTable.id, LabelTable.updatedAt)
            val sqlComputed =
                suspendTransaction(db = database) {
                    LabelTable.select(hashColumn).where { LabelTable.id eq id }.single()[hashColumn]
                }

            assertEquals(ConcurrencySupport.etag(id, updatedAt), sqlComputed)
        }

    @Test
    fun `the SQL hash expression matches the JVM computation for a persisted row with a numeric id`() =
        runTest {
            val id = 42L
            val updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)

            suspendTransaction(db = database) {
                WidgetTable.insert {
                    it[WidgetTable.id] = id
                    it[WidgetTable.updatedAt] = updatedAt.atOffset(ZoneOffset.UTC)
                }
            }

            val hashColumn = ConcurrencySupport.etagSqlExpression(WidgetTable.id, WidgetTable.updatedAt)
            val sqlComputed =
                suspendTransaction(db = database) {
                    WidgetTable.select(hashColumn).where { WidgetTable.id eq id }.single()[hashColumn]
                }

            assertEquals(ConcurrencySupport.etag(id, updatedAt), sqlComputed)
        }

    /** A minimal `{ id, updated_at }` table - the SQL-side counterpart to `test.proto`'s `Label` message. */
    private object LabelTable : Table("label") {
        val id = varchar("id", 64)
        val updatedAt = timestampWithTimeZone("updated_at")
        override val primaryKey = PrimaryKey(id)
    }

    /** Same as [LabelTable], but with a numeric id - the SQL-side counterpart to `test.proto`'s `Widget` message. */
    private object WidgetTable : Table("widget") {
        val id = long("id")
        val updatedAt = timestampWithTimeZone("updated_at")
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
            runBlocking { suspendTransaction(db = database) { SchemaUtils.create(LabelTable, WidgetTable) } }
        }
    }
}
