package no.entur.exposed.paging

import no.entur.exposed.testfixtures.v1.LineItem
import no.entur.exposed.testfixtures.v1.Widget
import org.jetbrains.exposed.v1.core.dao.id.CompositeIdTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import tools.jackson.databind.ObjectMapper

/**
 * The SQL-side counterpart to `test.proto`'s `Widget` message - used only to exercise
 * [PagingSupport]/[SortableProperty]. Constructing it (unlike running a query against it) needs no
 * live DB - see [PagingSupportTest] - only [PagingSupportPostgresTest] actually connects to one.
 * [updatedAt]/[statsLastUpdatedAt] denormalize `Widget.updated_at` (top-level) and
 * `Widget.stats.last_updated_at` (nested) onto this one table, same as a real resource's own
 * table would - see [WIDGET_SORTABLE_PROPERTIES]. [rawId] backs `Widget.raw_id`, a `bytes` field;
 * [active] backs `Widget.active`, an `optional bool` field.
 */
internal object WidgetTable : LongIdTable("widget") {
    val name = varchar("name", 255)
    val status = varchar("status", 32)
    val updatedAt = timestampWithTimeZone("updated_at")
    val statsLastUpdatedAt = timestampWithTimeZone("stats_last_updated_at")
    val rawId = binary("raw_id")
    val active = bool("active").nullable()
}

/**
 * `id` is the (single) id; `name`/`status` are plain properties; `updated_at` is a top-level
 * `Timestamp` field, `stats.last_updated_at` a nested one; `raw_id` is a `bytes` field; `active` is
 * a nullable field, sorting its absent values last - see [WidgetTable].
 */
internal val WIDGET_SORTABLE_PROPERTIES: SortableProperties<Widget> =
    SortableProperties
        .builder<Widget>(Widget.getDescriptor())
        .id("id", WidgetTable.id)
        .field("name", WidgetTable.name)
        .field("status", WidgetTable.status)
        .field("updated_at", WidgetTable.updatedAt)
        .field("stats.last_updated_at", WidgetTable.statsLastUpdatedAt)
        .field("raw_id", WidgetTable.rawId)
        .fieldNullsLast("active", WidgetTable.active)
        .build()

/**
 * A genuinely *composite*-keyed table - the SQL-side counterpart to `test.proto`'s `LineItem`
 * message - unlike [WidgetTable]/every other table here, which each have a single-column id.
 * `orderId`/`lineNumber` are each real `Column<EntityID<*>>` id-component columns (`.entityId()`
 * self-registers them via [CompositeIdTable.addIdColumn]), not a stand-in for one.
 */
internal object LineItemTable : CompositeIdTable("line_item") {
    val orderId = long("order_id").entityId()
    val lineNumber = integer("line_number").entityId()
    override val primaryKey = PrimaryKey(orderId, lineNumber)
    val description = varchar("description", 255)
}

/** Both `orderId` and `lineNumber` are (part of) the id; `description` is a plain property. */
internal val LINE_ITEM_SORTABLE_PROPERTIES: SortableProperties<LineItem> =
    SortableProperties
        .builder<LineItem>(LineItem.getDescriptor())
        .id("order_id", LineItemTable.orderId)
        .id("line_number", LineItemTable.lineNumber)
        .field("description", LineItemTable.description)
        .build()

internal val OBJECT_MAPPER = ObjectMapper()
