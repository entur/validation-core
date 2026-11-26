package no.entur.exposed.paging

import no.entur.exposed.testfixtures.v1.LineItem
import no.entur.exposed.testfixtures.v1.Widget
import no.entur.exposed.testfixtures.v1.WidgetStatus
import org.jetbrains.exposed.v1.core.dao.id.CompositeIdTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import tools.jackson.databind.ObjectMapper

/** `test.proto`'s `Widget`, with the nested `stats.last_updated_at`/`stats.nested_status` flattened into columns like a real resource table. */
internal object WidgetTable : LongIdTable("widget") {
    val name = varchar("name", 255)
    val status = varchar("status", 32)
    val updatedAt = timestampWithTimeZone("updated_at")
    val statsLastUpdatedAt = timestampWithTimeZone("stats_last_updated_at")

    // Defaulted, unlike every other column here, so the Postgres tests' existing seed data (which
    // doesn't set it) still inserts - a real enum column has no such default and every insert sets it.
    val statsNestedStatus = varchar("stats_nested_status", 32).default(WidgetStatus.WIDGET_STATUS_UNSPECIFIED.name)
    val rawId = binary("raw_id")
    val active = bool("active").nullable()

    // Defaulted, same reason as statsNestedStatus above - stores the set oneof variant's own proto
    // field name (e.g. "part_variant"), the way OneofSerialization does for a real resource.
    val variantKind = varchar("variant_kind", 32).default("part_variant")
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
        .oneofKind("variantKind", "variant", WidgetTable.variantKind)
        .build()

internal val WIDGET_FILTERABLE_PROPERTIES: FilterableProperties<Widget> =
    FilterableProperties
        .builder<Widget>(Widget.getDescriptor())
        .field("status", WidgetTable.status)
        .field("stats.nested_status", WidgetTable.statsNestedStatus)
        .oneofKind("variantKind", "variant", WidgetTable.variantKind)
        .stringField("name", WidgetTable.name)
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
