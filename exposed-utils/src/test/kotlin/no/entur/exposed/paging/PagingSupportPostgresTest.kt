package no.entur.exposed.paging

import com.google.protobuf.ByteString
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import no.entur.exposed.testfixtures.v1.LineItem
import no.entur.exposed.testfixtures.v1.Widget
import no.entur.exposed.testfixtures.v1.WidgetStatus
import no.entur.exposed.testfixtures.v1.lineItem
import no.entur.exposed.testfixtures.v1.widget
import no.entur.exposed.testfixtures.v1.widgetStats
import no.entur.proto.time.toProtoTimestamp
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
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
import java.time.OffsetDateTime

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

    /** A fixed base instant [seed] builds every widget's `updated_at`/`stats.last_updated_at` from. */
    private val baseTimestamp = OffsetDateTime.parse("2026-01-01T00:00:00Z")

    /** [seed]'s own `active` value per [seeded] row, by index - two of each: `true`, `false`, absent. */
    private val activeValues = listOf(true, false, null, null, true, false)

    /**
     * A second [SortableProperties] over [WidgetTable], differing from [WIDGET_SORTABLE_PROPERTIES]
     * only in `active`'s own [NullsOrder] (`FIRST` here, `LAST` there) - [NullsOrder] is a property
     * of how *this* resource declares the field sortable at all, fixed once, not something a caller
     * picks per request (see [SortableNullableProperty.create]), so covering both needs two distinct
     * [SortableProperties] instances, not two calls against one.
     */
    private val widgetNullsFirstProperties: SortableProperties<Widget> =
        SortableProperties
            .builder<Widget>(Widget.getDescriptor())
            .id("id", WidgetTable.id)
            .fieldNullsFirst("active", WidgetTable.active)
            .build()

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

    /**
     * [seed]'s `statsLastUpdatedAt` deliberately ties every pair of rows - the real-world shape
     * zagar hand-rolled this exact predicate for, against a *nested* `Timestamp` field
     * (`ValidationJob.statistics.last_progress_received_at`) rather than [WidgetTable]'s top-level
     * `name`/`status`.
     */
    @Test
    fun `keyset-scrolling a nested Timestamp field with ties visits every row exactly once, in id order within a tie`() =
        runTest {
            seed()
            val order = PagingSupport.parseOrderBy("stats.lastUpdatedAt desc", WIDGET_SORTABLE_PROPERTIES)

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
     * Proves a `bytes` field is genuinely orderable at the SQL level, not just accepted by
     * [SortableProperty]'s own type-checking - [seed]'s `rawId` deliberately ties one pair of rows
     * (see its own KDoc), same shape as the nested-Timestamp test above.
     */
    @Test
    fun `keyset-scrolling a bytes field with a tie visits every row exactly once, in id order within the tie`() =
        runTest {
            seed()
            val order = PagingSupport.parseOrderBy("rawId", WIDGET_SORTABLE_PROPERTIES)

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
     * [WidgetTable.id] is auto-incrementing and never reset between tests in this suite (only the
     * rows are deleted - see [clearTables]), so no test can hardcode an absolute expected id. This
     * reads every seeded row back out in insertion order (ascending id, the default with no
     * `order_by` at all - [WIDGET_SORTABLE_PROPERTIES]'s only id) so a test can instead key its own
     * expectations off [activeValues]'s own indices, e.g. `insertionOrder[2]`/`[3]` for its absent rows.
     */
    private suspend fun insertionOrder(): List<Long> =
        findAll(PagingSupport.parseOrderBy(null, WIDGET_SORTABLE_PROPERTIES), pageSize = seeded.size, after = null).content.map { it.id }

    /**
     * `active` sorts `NullsOrder.LAST` in [WIDGET_SORTABLE_PROPERTIES] - absent values ([activeValues]'s
     * own `null` entries) come after every present one *regardless* of direction (`false` sorts
     * before `true` either way, but which present group comes first still reverses between
     * ascending and descending, same as any other field would).
     */
    @Test
    fun `keyset-scrolling a nullable field with NullsOrder LAST places absent values after present ones, in either direction`() =
        runTest {
            seed()
            val ids = insertionOrder()
            val falseIds = listOf(ids[1], ids[5])
            val trueIds = listOf(ids[0], ids[4])
            val absentIds = listOf(ids[2], ids[3])
            val ascending = PagingSupport.parseOrderBy("active", WIDGET_SORTABLE_PROPERTIES)
            val descending = PagingSupport.parseOrderBy("active desc", WIDGET_SORTABLE_PROPERTIES)

            val ascendingVisited = mutableListOf<Long>()
            var ascendingCursor: String? = null
            do {
                val after = PagingSupport.decodeCursor(WIDGET_SORTABLE_PROPERTIES, ascendingCursor, ascending, OBJECT_MAPPER)
                val page = findAll(ascending, pageSize = 2, after = after)
                ascendingVisited += page.content.map { it.id }
                ascendingCursor = PagingSupport.nextCursor(WIDGET_SORTABLE_PROPERTIES, page, OBJECT_MAPPER)
            } while (ascendingCursor != null)

            val descendingVisited = mutableListOf<Long>()
            var descendingCursor: String? = null
            do {
                val after = PagingSupport.decodeCursor(WIDGET_SORTABLE_PROPERTIES, descendingCursor, descending, OBJECT_MAPPER)
                val page = findAll(descending, pageSize = 2, after = after)
                descendingVisited += page.content.map { it.id }
                descendingCursor = PagingSupport.nextCursor(WIDGET_SORTABLE_PROPERTIES, page, OBJECT_MAPPER)
            } while (descendingCursor != null)

            assertEquals(falseIds + trueIds + absentIds, ascendingVisited)
            assertEquals(trueIds + falseIds + absentIds, descendingVisited)
        }

    /** The [widgetNullsFirstProperties] counterpart to the `NullsOrder.LAST` test above - same seed, absent values first instead. */
    @Test
    fun `keyset-scrolling a nullable field with NullsOrder FIRST places absent values before present ones, in either direction`() =
        runTest {
            seed()
            val ids = insertionOrder()
            val falseIds = listOf(ids[1], ids[5])
            val trueIds = listOf(ids[0], ids[4])
            val absentIds = listOf(ids[2], ids[3])
            val ascending = PagingSupport.parseOrderBy("active", widgetNullsFirstProperties)
            val descending = PagingSupport.parseOrderBy("active desc", widgetNullsFirstProperties)

            val ascendingVisited = mutableListOf<Long>()
            var ascendingCursor: String? = null
            do {
                val after = PagingSupport.decodeCursor(widgetNullsFirstProperties, ascendingCursor, ascending, OBJECT_MAPPER)
                val page = findAll(ascending, pageSize = 2, after = after, sortableProperties = widgetNullsFirstProperties)
                ascendingVisited += page.content.map { it.id }
                ascendingCursor = PagingSupport.nextCursor(widgetNullsFirstProperties, page, OBJECT_MAPPER)
            } while (ascendingCursor != null)

            val descendingVisited = mutableListOf<Long>()
            var descendingCursor: String? = null
            do {
                val after = PagingSupport.decodeCursor(widgetNullsFirstProperties, descendingCursor, descending, OBJECT_MAPPER)
                val page = findAll(descending, pageSize = 2, after = after, sortableProperties = widgetNullsFirstProperties)
                descendingVisited += page.content.map { it.id }
                descendingCursor = PagingSupport.nextCursor(widgetNullsFirstProperties, page, OBJECT_MAPPER)
            } while (descendingCursor != null)

            assertEquals(absentIds + falseIds + trueIds, ascendingVisited)
            assertEquals(absentIds + trueIds + falseIds, descendingVisited)
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
        after: Map<String, Any?>?,
        sortableProperties: SortableProperties<Widget> = WIDGET_SORTABLE_PROPERTIES,
    ): Page<Widget> =
        suspendTransaction(db = database, readOnly = true) {
            val condition: Op<Boolean> = after?.let { PagingSupport.keysetCondition(sortableProperties, sort, it) } ?: Op.TRUE
            var query = WidgetTable.selectAll().where { condition }
            sort.forEach { field ->
                val property = sortableProperties.byName(field.property)
                query = query.orderBy(property.column, property.sortOrder(field.ascending))
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
            updatedAt = this@toWidget[WidgetTable.updatedAt].toProtoTimestamp()
            stats = widgetStats { lastUpdatedAt = this@toWidget[WidgetTable.statsLastUpdatedAt].toProtoTimestamp() }
            rawId = ByteString.copyFrom(this@toWidget[WidgetTable.rawId])
            this@toWidget[WidgetTable.active]?.let { active = it }
        }

    /**
     * `updatedAt` gets a distinct value per row; `statsLastUpdatedAt` gets one shared by every
     * *pair* of rows - deliberate ties for the nested-Timestamp keyset test below. `rawId` gets a
     * distinct single byte per row, ties on every *odd* row instead (`0, 1, 1, 3, 4, 5` for 6 rows)
     * - deliberate ties for the bytes keyset test below. `active` gets [activeValues] by index -
     * two rows tied on an absent value, for the nullable-field keyset tests below.
     */
    private suspend fun seed() {
        suspendTransaction(db = database) {
            seeded.forEachIndexed { index, (name, status) ->
                WidgetTable.insert {
                    it[WidgetTable.name] = name
                    it[WidgetTable.status] = status.name
                    it[updatedAt] = baseTimestamp.plusSeconds(index.toLong())
                    it[statsLastUpdatedAt] = baseTimestamp.plusSeconds((index / 2).toLong())
                    it[rawId] = byteArrayOf((if (index == 2) 1 else index).toByte())
                    it[active] = activeValues[index]
                }
            }
        }
    }

    private suspend fun findAllLineItems(
        sort: List<SortField>,
        pageSize: Int,
        after: Map<String, Any?>?,
    ): Page<LineItem> =
        suspendTransaction(db = database, readOnly = true) {
            val condition: Op<Boolean> =
                after?.let { PagingSupport.keysetCondition(LINE_ITEM_SORTABLE_PROPERTIES, sort, it) } ?: Op.TRUE
            var query = LineItemTable.selectAll().where { condition }
            sort.forEach { field ->
                val property = LINE_ITEM_SORTABLE_PROPERTIES.byName(field.property)
                query = query.orderBy(property.column, property.sortOrder(field.ascending))
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
