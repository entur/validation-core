package no.entur.exposed.paging

import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import no.entur.exposed.testfixtures.v1.WidgetStatus
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.deleteAll
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

/** The parsed [PagingSupport.parseFilter] condition's correctness can only be checked by running it. */
@Testcontainers
internal class FilterParserPostgresTest {
    /**
     * Every combination of `status`/`stats.nestedStatus` present, so AND/OR/precedence all have real rows to tell
     * apart. `variantKind` alternates `part_variant`/`detail_variant`, independent of the other two, so a
     * [FilterableOneofKindProperty] restriction composes against a real-enum one too.
     */
    private val seeded =
        listOf(
            Triple("active-active", WidgetStatus.WIDGET_STATUS_ACTIVE, WidgetStatus.WIDGET_STATUS_ACTIVE),
            Triple("active-inactive", WidgetStatus.WIDGET_STATUS_ACTIVE, WidgetStatus.WIDGET_STATUS_INACTIVE),
            Triple("inactive-active", WidgetStatus.WIDGET_STATUS_INACTIVE, WidgetStatus.WIDGET_STATUS_ACTIVE),
            Triple("inactive-inactive", WidgetStatus.WIDGET_STATUS_INACTIVE, WidgetStatus.WIDGET_STATUS_INACTIVE),
        )

    @BeforeEach
    fun seed() =
        runTest {
            suspendTransaction(db = database) {
                WidgetTable.deleteAll()
                seeded.forEachIndexed { i, (name, status, nestedStatus) ->
                    WidgetTable.insert {
                        it[WidgetTable.name] = name
                        it[WidgetTable.status] = status.name
                        it[statsNestedStatus] = nestedStatus.name
                        it[variantKind] = if (i % 2 == 0) "part_variant" else "detail_variant"
                        it[updatedAt] = BASE_TIMESTAMP
                        it[statsLastUpdatedAt] = BASE_TIMESTAMP
                        it[rawId] = byteArrayOf(0)
                    }
                }
            }
        }

    @Test
    fun `a single equality restriction matches only the rows with that value`() =
        runTest {
            val names = namesMatching("status = WIDGET_STATUS_ACTIVE")

            assertEquals(setOf("active-active", "active-inactive"), names)
        }

    @Test
    fun `AND matches only rows satisfying both restrictions`() =
        runTest {
            val names = namesMatching("status = WIDGET_STATUS_ACTIVE AND stats.nestedStatus = WIDGET_STATUS_INACTIVE")

            assertEquals(setOf("active-inactive"), names)
        }

    @Test
    fun `OR matches rows satisfying either restriction`() =
        runTest {
            val names = namesMatching("status = WIDGET_STATUS_INACTIVE OR stats.nestedStatus = WIDGET_STATUS_INACTIVE")

            assertEquals(setOf("inactive-active", "inactive-inactive", "active-inactive"), names)
        }

    @Test
    fun `explicit parentheses group an OR before it's ANDed with the rest`() =
        runTest {
            val names =
                namesMatching(
                    "status = WIDGET_STATUS_ACTIVE AND (stats.nestedStatus = WIDGET_STATUS_ACTIVE OR stats.nestedStatus = WIDGET_STATUS_INACTIVE)",
                )

            assertEquals(setOf("active-active", "active-inactive"), names)
        }

    /**
     * Per https://google.aip.dev/160's own "Logical Operators" section: "the `OR` operator has
     * higher precedence than `AND`, unlike what is found in most programming languages" - and its
     * own worked example, "The expression `a AND b OR c` evaluates: `a AND (b OR c)`." This and the
     * next test apply that with `AND` appearing first, then `OR` first, in the raw filter string.
     */
    @Test
    fun `AND before OR in the input - without parentheses, OR still binds tighter, per AIP-160`() =
        runTest {
            // "status = ACTIVE AND stats.nestedStatus = ACTIVE OR stats.nestedStatus = INACTIVE" must mean
            // "status = ACTIVE AND (stats.nestedStatus = ACTIVE OR stats.nestedStatus = INACTIVE)", matching
            // active-active and active-inactive - not "(status = ACTIVE AND stats.nestedStatus = ACTIVE) OR
            // stats.nestedStatus = INACTIVE", which would also match inactive-inactive.
            val names =
                namesMatching(
                    "status = WIDGET_STATUS_ACTIVE AND stats.nestedStatus = WIDGET_STATUS_ACTIVE OR stats.nestedStatus = WIDGET_STATUS_INACTIVE",
                )

            assertEquals(setOf("active-active", "active-inactive"), names)
        }

    @Test
    fun `OR before AND in the input - without parentheses, OR still binds tighter, per AIP-160`() =
        runTest {
            // "status = INACTIVE OR status = ACTIVE AND stats.nestedStatus = ACTIVE" must mean
            // "(status = INACTIVE OR status = ACTIVE) AND stats.nestedStatus = ACTIVE", matching only
            // active-active and inactive-active - not "status = INACTIVE OR (status = ACTIVE AND
            // stats.nestedStatus = ACTIVE)", which would also match inactive-inactive.
            val names =
                namesMatching(
                    "status = WIDGET_STATUS_INACTIVE OR status = WIDGET_STATUS_ACTIVE AND stats.nestedStatus = WIDGET_STATUS_ACTIVE",
                )

            assertEquals(setOf("active-active", "inactive-active"), names)
        }

    @Test
    fun `a oneof-kind restriction matches only rows with that variant set`() =
        runTest {
            val names = namesMatching("variantKind = PART_VARIANT")

            assertEquals(setOf("active-active", "inactive-active"), names)
        }

    @Test
    fun `a oneof-kind restriction composes with a real-enum one via AND`() =
        runTest {
            val names = namesMatching("variantKind = PART_VARIANT AND status = WIDGET_STATUS_INACTIVE")

            assertEquals(setOf("inactive-active"), names)
        }

    private suspend fun namesMatching(filter: String): Set<String> =
        suspendTransaction(db = database) {
            WidgetTable
                .selectAll()
                .where { PagingSupport.parseFilter(filter, WIDGET_FILTERABLE_PROPERTIES) }
                .toList()
                .map { it[WidgetTable.name] }
                .toSet()
        }

    companion object {
        private val BASE_TIMESTAMP = java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z")

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
            runBlocking { suspendTransaction(db = database) { SchemaUtils.create(WidgetTable) } }
        }
    }
}
