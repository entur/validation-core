package no.entur.proto.paging

import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import no.entur.proto.testfixtures.v1.LineItem
import no.entur.proto.testfixtures.v1.Widget
import no.entur.proto.testfixtures.v1.WidgetStatus
import no.entur.proto.testfixtures.v1.lineItem
import no.entur.proto.testfixtures.v1.widget
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
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

/**
 * [PagingSupport.keysetCondition] against a real Postgres: [PagingSupportTest] covers everything
 * else in [PagingSupport] without a DB, but the keyset predicate's own correctness - does it
 * actually select the right "next window" of rows, with ties on an earlier sort key broken by the
 * next one - can only be checked by actually running it. [WidgetTable] covers a single-column id;
 * [LineItemTable] covers a genuine composite one.
 */
@Testcontainers
internal class PagingSupportPostgresTest {
    /**
     * Seeds rows with deliberate duplicates on `status` (and, within a `status`, on `name`), so a
     * two/three-field sort has real ties to break - a single-column sort could never expose a bug
     * in [PagingSupport.keysetCondition]'s multi-key disjunction.
     */
    private val seeded =
        listOf(
            "bravo" to WidgetStatus.WIDGET_STATUS_ACTIVE,
            "alpha" to WidgetStatus.WIDGET_STATUS_ACTIVE,
            "alpha" to WidgetStatus.WIDGET_STATUS_ACTIVE,
            "delta" to WidgetStatus.WIDGET_STATUS_INACTIVE,
            "charlie" to WidgetStatus.WIDGET_STATUS_INACTIVE,
            "echo" to WidgetStatus.WIDGET_STATUS_UNSPECIFIED,
        )

    /**
     * Composite-id rows sharing an `orderId` with different `lineNumber`s - a real tie that only
     * the composite id's *second* component can break, unlike [seeded]'s deliberately duplicated
     * `status`/`name` above.
     */
    private val seededLineItems =
        listOf(
            100L to 2,
            100L to 1,
            200L to 1,
            100L to 3,
        )

    /** Each test starts from empty tables - [WidgetTable]'s ids are auto-incrementing, so a leftover row from a previous test would also shift the ids this test asserts on. */
    @BeforeEach
    fun clearTables() =
        runTest {
            suspendTransaction(db = database) {
                WidgetTable.deleteAll()
                LineItemTable.deleteAll()
            }
        }

    @Test
    fun `keyset-scrolling one small page at a time visits every row exactly once, in the requested order`() =
        runTest {
            seed()
            val order = PagingSupport.parseOrderBy("status desc, name", WIDGET_SORTABLE_PROPERTIES)

            val visited = mutableListOf<Long>()
            var cursor: String? = null
            do {
                val after = PagingSupport.decodeCursor(WIDGET_SORTABLE_PROPERTIES, cursor, order, OBJECT_MAPPER)
                val page = findAll(order, pageSize = 2, after = after)
                visited += page.content.map { it.id }
                cursor = PagingSupport.nextCursor(WIDGET_SORTABLE_PROPERTIES, page, OBJECT_MAPPER)
            } while (cursor != null)

            val expected = findAll(order, pageSize = seeded.size, after = null).content.map { it.id }
            assertEquals(expected, visited)
            assertEquals(seeded.size, visited.toSet().size)
        }

    /**
     * `id` is *always* included in `order` (see [PagingSupport.parseOrderBy]'s tie-break), so
     * reversing every field the client actually named - but not `id` too - only reverses each
     * group of ties as a whole, not the id-broken order *within* a tied group (`alpha`/`WIDGET_STATUS_ACTIVE`
     * appears as id 2 then 3 either way, since only the explicitly-named fields flipped
     * direction). Naming `id desc` explicitly makes the client's own sort a true, unambiguous
     * total order, so reversing it really does reverse the whole walk.
     */
    @Test
    fun `reversing every field's direction, id included, reverses the whole walk`() =
        runTest {
            seed()
            val ascending = PagingSupport.parseOrderBy("status,name,id", WIDGET_SORTABLE_PROPERTIES)
            val descending = PagingSupport.parseOrderBy("status desc,name desc,id desc", WIDGET_SORTABLE_PROPERTIES)

            val forward = findAll(ascending, pageSize = seeded.size, after = null).content.map { it.id }
            val backward = findAll(descending, pageSize = seeded.size, after = null).content.map { it.id }

            assertEquals(forward, backward.reversed())
        }

    @Test
    fun `keyset-scrolling a composite-id table visits every row exactly once, in id order`() =
        runTest {
            seedLineItems()
            val order = PagingSupport.parseOrderBy(null, LINE_ITEM_SORTABLE_PROPERTIES)

            val visited = mutableListOf<Pair<Long, Int>>()
            var cursor: String? = null
            do {
                val after = PagingSupport.decodeCursor(LINE_ITEM_SORTABLE_PROPERTIES, cursor, order, OBJECT_MAPPER)
                val page = findAllLineItems(order, pageSize = 2, after = after)
                visited += page.content.map { it.orderId to it.lineNumber }
                cursor = PagingSupport.nextCursor(LINE_ITEM_SORTABLE_PROPERTIES, page, OBJECT_MAPPER)
            } while (cursor != null)

            assertEquals(listOf(100L to 1, 100L to 2, 100L to 3, 200L to 1), visited)
        }

    private suspend fun findAll(
        sort: List<SortField>,
        pageSize: Int,
        after: Map<String, Any>?,
    ): Page<Widget> =
        suspendTransaction(db = database, readOnly = true) {
            val condition: Op<Boolean> = after?.let { PagingSupport.keysetCondition(WIDGET_SORTABLE_PROPERTIES, sort, it) } ?: Op.TRUE
            var query = WidgetTable.selectAll().where { condition }
            sort.forEach { field ->
                val column = WIDGET_SORTABLE_PROPERTIES.byName(field.property).column
                query = query.orderBy(column, if (field.ascending) SortOrder.ASC else SortOrder.DESC)
            }
            val rows = query.limit(pageSize + 1).toList()
            val hasNext = rows.size > pageSize
            Page(rows.take(pageSize).map { it.toWidget() }, hasNext, sort)
        }

    private fun ResultRow.toWidget(): Widget =
        widget {
            id = this@toWidget[WidgetTable.id].value
            name = this@toWidget[WidgetTable.name]
            status = WidgetStatus.valueOf(this@toWidget[WidgetTable.status])
        }

    private suspend fun seed() {
        suspendTransaction(db = database) {
            seeded.forEach { (name, status) ->
                WidgetTable.insert {
                    it[WidgetTable.name] = name
                    it[WidgetTable.status] = status.name
                }
            }
        }
    }

    private suspend fun findAllLineItems(
        sort: List<SortField>,
        pageSize: Int,
        after: Map<String, Any>?,
    ): Page<LineItem> =
        suspendTransaction(db = database, readOnly = true) {
            val condition: Op<Boolean> =
                after?.let { PagingSupport.keysetCondition(LINE_ITEM_SORTABLE_PROPERTIES, sort, it) } ?: Op.TRUE
            var query = LineItemTable.selectAll().where { condition }
            sort.forEach { field ->
                val column = LINE_ITEM_SORTABLE_PROPERTIES.byName(field.property).column
                query = query.orderBy(column, if (field.ascending) SortOrder.ASC else SortOrder.DESC)
            }
            val rows = query.limit(pageSize + 1).toList()
            val hasNext = rows.size > pageSize
            Page(rows.take(pageSize).map { it.toLineItem() }, hasNext, sort)
        }

    private fun ResultRow.toLineItem(): LineItem =
        lineItem {
            orderId = this@toLineItem[LineItemTable.orderId].value
            lineNumber = this@toLineItem[LineItemTable.lineNumber].value
            description = this@toLineItem[LineItemTable.description]
        }

    private suspend fun seedLineItems() {
        suspendTransaction(db = database) {
            seededLineItems.forEachIndexed { index, (orderId, lineNumber) ->
                LineItemTable.insert {
                    it[LineItemTable.orderId] = orderId
                    it[LineItemTable.lineNumber] = lineNumber
                    it[description] = "item-$index"
                }
            }
        }
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
            runBlocking { suspendTransaction(db = database) { SchemaUtils.create(WidgetTable, LineItemTable) } }
        }
    }
}
