package no.entur.exposed.paging

import no.entur.exposed.testfixtures.v1.LineItem
import no.entur.exposed.testfixtures.v1.Widget
import org.jetbrains.exposed.v1.core.dao.id.CompositeIdTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import tools.jackson.databind.ObjectMapper

/**
 * A minimal `{ id, name, status }` table - the SQL-side counterpart to `test.proto`'s `Widget`
 * message - used only to exercise [PagingSupport]/[SortableProperty]. Constructing it (unlike
 * running a query against it) needs no live DB - see [PagingSupportTest] - only
 * [PagingSupportPostgresTest] actually connects to one.
 */
internal object WidgetTable : LongIdTable("widget") {
    val name = varchar("name", 255)
    val status = varchar("status", 32)
}

/** `id` is the (single) id; `name`/`status` are plain properties. */
internal val WIDGET_SORTABLE_PROPERTIES: SortableProperties<Widget> =
    SortableProperties
        .builder<Widget>(Widget.getDescriptor())
        .id("id", WidgetTable.id)
        .field("name", WidgetTable.name)
        .field("status", WidgetTable.status)
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
