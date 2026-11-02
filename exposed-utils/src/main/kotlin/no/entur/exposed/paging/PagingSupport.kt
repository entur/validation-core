package no.entur.exposed.paging

import com.google.protobuf.Message
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.or
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.util.Base64

/** Resource-agnostic `order_by` and keyset-cursor paging for AIP-132 list endpoints. */
object PagingSupport {
    /**
     * Parses `order_by` (`a, b desc`) and appends every [SortableProperties.ids] property not already
     * named, since keyset scrolling needs a total order to resume correctly.
     */
    fun <T : Message> parseOrderBy(
        orderBy: String?,
        sortableProperties: SortableProperties<T>,
    ): List<SortField> {
        val orders = LinkedHashMap<String, SortField>()
        orderBy
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach { field ->
                val descending = field.endsWith(" desc")
                val name = if (descending) field.removeSuffix(" desc") else field
                require(name in sortableProperties) { "Unsupported order_by field: $field" }
                orders[name] = SortField(name, ascending = !descending)
            }
        sortableProperties.ids.forEach { orders.putIfAbsent(it.name, SortField(it.name)) }
        return orders.values.toList()
    }

    /** The keyset predicate for rows after [after]: `(k1 > v1) OR (k1 = v1 AND k2 > v2) ...`. */
    fun <T : Message> keysetCondition(
        sortableProperties: SortableProperties<T>,
        sort: List<SortField>,
        after: Map<String, Any?>,
    ): Op<Boolean> {
        val (disjunction, _) = foldKeyset(sortableProperties, sort, after)
        return requireNotNull(disjunction) { "sort must not be empty" }
    }

    /** [keysetCondition] for a resource whose id can't be a [SortableProperty]: [idColumn] is the ascending tie-break. */
    fun <T : Message, ID : Comparable<ID>> keysetCondition(
        sortableProperties: SortableProperties<T>,
        sort: List<SortField>,
        after: Map<String, Any?>,
        idColumn: Column<EntityID<ID>>,
        idAfter: ID,
    ): Op<Boolean> {
        val (disjunction, equalitiesSoFar) = foldKeyset(sortableProperties, sort, after)
        val idClause = (equalitiesSoFar + (idColumn greater idAfter)).reduce { a, b -> a and b }
        return disjunction?.let { it or idClause } ?: idClause
    }

    private fun <T : Message> foldKeyset(
        sortableProperties: SortableProperties<T>,
        sort: List<SortField>,
        after: Map<String, Any?>,
    ): Pair<Op<Boolean>?, List<Op<Boolean>>> {
        var disjunction: Op<Boolean>? = null
        val equalitiesSoFar = mutableListOf<Op<Boolean>>()
        for (field in sort) {
            val property = sortableProperties.byName(field.property)
            val value = after.getValue(field.property)
            val clause =
                (
                    equalitiesSoFar +
                        if (field.ascending) {
                            property.greaterCondition(value)
                        } else {
                            property.lessCondition(value)
                        }
                ).reduce { a, b -> a and b }
            disjunction = disjunction?.let { it or clause } ?: clause
            equalitiesSoFar += property.equalCondition(value)
        }
        return disjunction to equalitiesSoFar
    }

    /**
     * The cursor past [page]'s last item, or null on the last page. It holds values only, not the
     * sort, so the client must resend the same `order_by` on every page.
     */
    fun <T : Message> nextCursor(
        sortableProperties: SortableProperties<T>,
        page: Page<T>,
        objectMapper: ObjectMapper,
    ): String? {
        if (!page.hasNext) return null
        val values = page.sort.map { sortableProperties.byName(it.property).valueOf(page.content.last())?.toString() }
        return encodeCursor(values, objectMapper)
    }

    /** [nextCursor] with the last row's [id] appended, for the `idColumn` [keysetCondition]. */
    fun <T : Message> nextCursor(
        sortableProperties: SortableProperties<T>,
        page: Page<T>,
        id: String,
        objectMapper: ObjectMapper,
    ): String? {
        if (!page.hasNext) return null
        val values = page.sort.map { sortableProperties.byName(it.property).valueOf(page.content.last())?.toString() } + id
        return encodeCursor(values, objectMapper)
    }

    private fun encodeCursor(
        values: List<String?>,
        objectMapper: ObjectMapper,
    ): String = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(values))

    /**
     * Throws [IllegalArgumentException] for a malformed cursor. A cursor minted under a different
     * `order_by` isn't detected, and a null for a non-nullable property only fails once the
     * keyset condition uses it.
     */
    fun <T : Message> decodeCursor(
        sortableProperties: SortableProperties<T>,
        cursor: String?,
        sort: List<SortField>,
        objectMapper: ObjectMapper,
    ): Map<String, Any?>? {
        val raw = decodeRawCursor(cursor, objectMapper) ?: return null
        require(raw.size == sort.size) { "Invalid cursor" }
        return decodePrimaryValues(sortableProperties, sort, raw)
    }

    /** [decodeCursor] for the `idColumn` [keysetCondition], parsing the appended id with [parseId]. */
    fun <T : Message, I> decodeCursor(
        sortableProperties: SortableProperties<T>,
        cursor: String?,
        sort: List<SortField>,
        parseId: (String) -> I,
        objectMapper: ObjectMapper,
    ): Pair<Map<String, Any?>, I>? {
        val raw = decodeRawCursor(cursor, objectMapper) ?: return null
        require(raw.size == sort.size + 1) { "Invalid cursor" }
        val primary = decodePrimaryValues(sortableProperties, sort, raw.dropLast(1))
        return primary to parseId(requireNotNull(raw.last()) { "Invalid cursor" })
    }

    private fun <T : Message> decodePrimaryValues(
        sortableProperties: SortableProperties<T>,
        sort: List<SortField>,
        raw: List<String?>,
    ): Map<String, Any?> =
        sort.zip(raw).associate { (field, value) ->
            field.property to sortableProperties.byName(field.property).decode(value)
        }

    private fun decodeRawCursor(
        cursor: String?,
        objectMapper: ObjectMapper,
    ): List<String?>? {
        if (cursor == null) return null
        return try {
            objectMapper.readValue(Base64.getUrlDecoder().decode(cursor), object : TypeReference<List<String?>>() {})
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid cursor", e)
        }
    }
}
