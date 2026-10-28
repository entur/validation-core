package no.entur.exposed.paging

import com.google.protobuf.Message
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.or
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.util.Base64

/**
 * Generic (message/table-agnostic) `order_by`/keyset-cursor engine for AIP-132 list endpoints,
 * shared across every paginated resource: an opaque `cursor` query parameter plus `pageSize`, an
 * `items`/`cursor` response envelope, and an `orderBy` query parameter (a comma-separated list of
 * fields, each optionally suffixed with " desc", defaulting to the resource's own id) - see
 * [SortableProperty] for what each paginated resource supplies to use it.
 */
object PagingSupport {
    /**
     * Turns an AIP-132 `order_by` string (a comma-separated list of fields, each optionally
     * suffixed with " desc") into a [SortField] list, restricted to [sortableProperties] (each
     * looked up by its own [SortableProperty.name] - see [SortableProperties.byName]), always
     * ending in a stable tie-break on every [SortableProperties.ids] property not already named,
     * appended in the order [sortableProperties] itself declares them - a resource with a
     * composite id thus gets every one of its id fields appended, not just a single hardcoded
     * `"id"`. That total ordering is also what keyset scrolling itself requires to resume correctly.
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

    /**
     * The standard keyset/seek-method predicate for scrolling past [after]: a row-wise comparison
     * unrolled into a disjunction of conjunctions, e.g. for two keys
     * `(k1 cmp v1) OR (k1 = v1 AND k2 cmp v2)`, so that ties on an earlier key still resolve by
     * comparing the next one. A value in [after] is null only for a nullable property (see
     * [SortableNullableProperty.create]) resuming from a row whose own value for it was absent -
     * [SortableProperty.greaterCondition]/[SortableProperty.lessCondition]/[SortableProperty.equalCondition]
     * already know what that means for *that* property alone; this function itself stays oblivious
     * to nullability.
     */
    fun <T : Message> keysetCondition(
        sortableProperties: SortableProperties<T>,
        sort: List<SortField>,
        after: Map<String, Any?>,
    ): Op<Boolean> {
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
        return requireNotNull(disjunction) { "sort must not be empty" }
    }

    /**
     * The opaque cursor pointing just past the last item of [page], or null if it has no next
     * page: one value per [Page.sort] field, in that same order. Consistent paging therefore relies on
     * the client resending the same `order_by` on every page of one listing. A field's own value is
     * itself null only when it's a nullable property (see [SortableNullableProperty.create]) and the
     * last item's own value for it is absent.
     */
    fun <T : Message> nextCursor(
        sortableProperties: SortableProperties<T>,
        page: Page<T>,
        objectMapper: ObjectMapper,
    ): String? {
        if (!page.hasNext) return null
        val last = page.content.last()
        val values = page.sort.map { sortableProperties.byName(it.property).valueOf(last)?.toString() }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(values))
    }

    /**
     * Decodes an opaque cursor back into the keyset it was minted from, zipping its positional
     * values against [sort] (see [nextCursor]) - a cursor minted from a different `order_by` than
     * [sort] decodes into nonsense rather than failing loudly. A cursor is client-supplied and opaque,
     * so any failure to decode it - bad Base64, malformed JSON, a value count that
     * doesn't match [sort], or a value that doesn't parse as its property's type - is equally
     * "not a valid cursor" and reported the same way. A null value is only ever valid for a nullable
     * property (see [SortableNullableProperty.create]) - [SortableProperty.decode] itself passes a null
     * straight through regardless, so a cursor claiming a null value for a non-nullable property
     * decodes without complaint here, only to fail once [SortableProperty.lessCondition]/
     * [SortableProperty.greaterCondition]/[SortableProperty.equalCondition] actually try to use it.
     */
    fun <T : Message> decodeCursor(
        sortableProperties: SortableProperties<T>,
        cursor: String?,
        sort: List<SortField>,
        objectMapper: ObjectMapper,
    ): Map<String, Any?>? {
        if (cursor == null) return null
        return try {
            val raw: List<String?> =
                objectMapper.readValue(
                    Base64.getUrlDecoder().decode(cursor),
                    object : TypeReference<List<String?>>() {},
                )
            require(raw.size == sort.size) { "Invalid cursor" }
            sort.zip(raw).associate { (field, value) ->
                field.property to sortableProperties.byName(field.property).decode(value)
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid cursor", e)
        }
    }
}
