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
import java.time.OffsetDateTime

/** The keyset predicate's correctness can only be checked by running it; [PagingSupportTest] covers the rest. */
@Testcontainers
internal class PagingSupportPostgresTest {
    /** Duplicates on `status` and `name`, so a multi-field sort has real ties to break. */
    private val seeded =
        listOf(
            "bravo" to WidgetStatus.WIDGET_STATUS_ACTIVE,
            "alpha" to WidgetStatus.WIDGET_STATUS_ACTIVE,
            "alpha" to WidgetStatus.WIDGET_STATUS_ACTIVE,
            "delta" to WidgetStatus.WIDGET_STATUS_INACTIVE,
            "charlie" to WidgetStatus.WIDGET_STATUS_INACTIVE,
            "echo" to WidgetStatus.WIDGET_STATUS_UNSPECIFIED,
        )

    private val baseTimestamp = OffsetDateTime.parse("2026-01-01T00:00:00Z")

    /** Two each of `true`, `false` and absent, by [seeded] index. */
    private val activeValues = listOf(true, false, null, null, true, false)

    /** [NullsOrder] is fixed per [SortableProperties], so testing `FIRST` needs a second instance. */
    private val widgetNullsFirstProperties: SortableProperties<Widget> =
        SortableProperties
            .builder<Widget>(Widget.getDescriptor())
            .id("id", WidgetTable.id)
            .fieldNullsFirst("active", WidgetTable.active)
            .build()

    /** No `.id(...)`, for a resource without an id field: exercises the hand-supplied id tie-break overloads. */
    private val widgetNoIdProperties: SortableProperties<Widget> =
        SortableProperties
            .builder<Widget>(Widget.getDescriptor())
            .field("status", WidgetTable.status)
            .field("name", WidgetTable.name)
            .build()

    /** Rows sharing an `orderId`, so only the composite id's second component breaks the tie. */
    private val seededLineItems =
        listOf(
            100L to 2,
            100L to 1,
            200L to 1,
            100L to 3,
        )

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

    /** The appended `id asc` tie-break doesn't flip with the named fields, so `id desc` must be named too. */
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

    /** Mirrors Zagar's sort on the nested `ValidationJob.statistics.last_progress_received_at`. */
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

    /** Ids keep incrementing across tests, so tests index into this instead of hardcoding them. */
    private suspend fun insertionOrder(): List<Long> =
        findAll(PagingSupport.parseOrderBy(null, WIDGET_SORTABLE_PROPERTIES), pageSize = seeded.size, after = null).content.map { it.id }

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
                // pageSize 1 splits the absent group across pages, which is what exercises equalCondition(null).
                val page = findAll(ascending, pageSize = 1, after = after)
                ascendingVisited += page.content.map { it.id }
                ascendingCursor = PagingSupport.nextCursor(WIDGET_SORTABLE_PROPERTIES, page, OBJECT_MAPPER)
            } while (ascendingCursor != null)

            val descendingVisited = mutableListOf<Long>()
            var descendingCursor: String? = null
            do {
                val after = PagingSupport.decodeCursor(WIDGET_SORTABLE_PROPERTIES, descendingCursor, descending, OBJECT_MAPPER)
                val page = findAll(descending, pageSize = 1, after = after)
                descendingVisited += page.content.map { it.id }
                descendingCursor = PagingSupport.nextCursor(WIDGET_SORTABLE_PROPERTIES, page, OBJECT_MAPPER)
            } while (descendingCursor != null)

            assertEquals(falseIds + trueIds + absentIds, ascendingVisited)
            assertEquals(trueIds + falseIds + absentIds, descendingVisited)
        }

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
                val page = findAll(ascending, pageSize = 1, after = after, sortableProperties = widgetNullsFirstProperties)
                ascendingVisited += page.content.map { it.id }
                ascendingCursor = PagingSupport.nextCursor(widgetNullsFirstProperties, page, OBJECT_MAPPER)
            } while (ascendingCursor != null)

            val descendingVisited = mutableListOf<Long>()
            var descendingCursor: String? = null
            do {
                val after = PagingSupport.decodeCursor(widgetNullsFirstProperties, descendingCursor, descending, OBJECT_MAPPER)
                val page = findAll(descending, pageSize = 1, after = after, sortableProperties = widgetNullsFirstProperties)
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

    @Test
    fun `keyset-scrolling two fields with a hand-supplied id tie-break visits every row exactly once, in id order within a tie`() =
        runTest {
            seed()
            val sort = PagingSupport.parseOrderBy("status desc, name", widgetNoIdProperties)

            val visited = mutableListOf<Long>()
            var cursor: String? = null
            do {
                val after = PagingSupport.decodeCursor(widgetNoIdProperties, cursor, sort, parseId = { it.toLong() }, OBJECT_MAPPER)
                val page = findAllNoId(sort, pageSize = 2, after = after)
                visited += page.content.map { it.id }
                cursor =
                    page.content.lastOrNull()?.let {
                        PagingSupport.nextCursor(widgetNoIdProperties, page, it.id.toString(), OBJECT_MAPPER)
                    }
            } while (cursor != null)

            // WIDGET_SORTABLE_PROPERTIES' automatic id tie-break matches findAllNoId's hand-supplied one.
            val expectedOrder = PagingSupport.parseOrderBy("status desc, name", WIDGET_SORTABLE_PROPERTIES)
            val expected = findAll(expectedOrder, pageSize = seeded.size, after = null).content.map { it.id }
            assertEquals(expected, visited)
            assertEquals(seeded.size, visited.toSet().size)
        }

    /** [findAll], with the caller rather than [SortableProperties] owning the id tie-break. */
    private suspend fun findAllNoId(
        sort: List<SortField>,
        pageSize: Int,
        after: Pair<Map<String, Any?>, Long>?,
    ): Page<Widget> =
        suspendTransaction(db = database, readOnly = true) {
            val condition: Op<Boolean> =
                after?.let { (primary, idAfter) ->
                    PagingSupport.keysetCondition(widgetNoIdProperties, sort, primary, WidgetTable.id, idAfter)
                } ?: Op.TRUE
            var query = WidgetTable.selectAll().where { condition }
            sort.forEach { field ->
                val property = widgetNoIdProperties.byName(field.property)
                query = query.orderBy(property.column, property.sortOrder(field.ascending))
            }
            query = query.orderBy(WidgetTable.id, SortOrder.ASC)
            val rows = query.limit(pageSize + 1).toList()
            val hasNext = rows.size > pageSize
            Page(rows.take(pageSize).map { it.toWidget() }, hasNext, sort)
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

    /** `statsLastUpdatedAt` ties each pair of rows and `rawId` ties rows 1 and 2, so those sorts have ties to break. */
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
