package no.entur.exposed.paging

import no.entur.exposed.testfixtures.v1.LineItem
import no.entur.exposed.testfixtures.v1.Widget
import org.jetbrains.exposed.v1.core.dao.id.CompositeIdTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import tools.jackson.databind.ObjectMapper

/** `test.proto`'s `Widget`, with the nested `stats.last_updated_at` flattened into a column like a real resource table. */
internal object WidgetTable : LongIdTable("widget") {
    val name = varchar("name", 255)
    val status = varchar("status", 32)
    val updatedAt = timestampWithTimeZone("updated_at")
    val statsLastUpdatedAt = timestampWithTimeZone("stats_last_updated_at")
    val rawId = binary("raw_id")
    val active = bool("active").nullable()
}

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

/** `test.proto`'s `LineItem`: the composite-id case. */
internal object LineItemTable : CompositeIdTable("line_item") {
    val orderId = long("order_id").entityId()
    val lineNumber = integer("line_number").entityId()
    override val primaryKey = PrimaryKey(orderId, lineNumber)
    val description = varchar("description", 255)
}

internal val LINE_ITEM_SORTABLE_PROPERTIES: SortableProperties<LineItem> =
    SortableProperties
        .builder<LineItem>(LineItem.getDescriptor())
        .id("order_id", LineItemTable.orderId)
        .id("line_number", LineItemTable.lineNumber)
        .field("description", LineItemTable.description)
        .build()

internal val OBJECT_MAPPER = ObjectMapper()
