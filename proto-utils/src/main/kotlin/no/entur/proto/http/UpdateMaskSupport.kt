package no.entur.proto.http

import com.google.api.FieldBehavior
import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.FieldMask
import com.google.protobuf.Message
import com.google.protobuf.util.FieldMaskUtil
import com.google.protobuf.util.FieldMaskUtil.MergeOptions
import com.google.protobuf.util.JsonFormat
import no.entur.proto.reflect.hasBehavior

/**
 * Generic (message-type-agnostic) `update_mask`/`FieldMask` handling for `Update*` endpoints,
 * shared across every resource. Four distinct cases, per [AIP-134](https://google.aip.dev/134):
 *
 * - **`update_mask` absent from the request entirely** ([parse] returns `null`) - full
 *   replacement: every top-level, non-protected field of the resource becomes whatever [patch]
 *   carries, *including* fields [patch] left at their own default value. The REST-`PATCH`-shaped
 *   endpoint behaves like a `PUT`. Same outcome as the literal `*` below - see
 *   [resolveEffectiveMask].
 * - **`update_mask` present but empty** (the query param sent with no value, or a [FieldMask] with
 *   zero `paths`) - an ordinary partial update: only the fields [patch] actually *populated*
 *   (proto3 ["field presence"](https://protobuf.dev/programming-guides/field_presence/):
 *   [Message.getAllFields] only returns a scalar field if it's set to something other than its own
 *   default) are applied; everything else on the resource is left untouched. A field left at its
 *   default is indistinguishable from "not sent" and is therefore left alone. Clearing a field to its default
 *   requires *explicitly* naming it in the mask (the next case).
 * - **One or more real field paths named** - only those fields are applied, *regardless* of
 *   whether [patch] left them at a default value; naming a field is what makes an explicit default
 *   meaningful.
 * - **The literal mask value `*`** ([FULL_REPLACEMENT]) - full replacement, identically to the
 *   "absent entirely" case above; see [applyUpdateMask].
 *
 * Two more things worth being precise about:
 *
 * - **Nested/dotted paths are supported** (`FieldMaskUtil.merge` already walks them), so
 *   `IDENTIFIER`/`OUTPUT_ONLY` filtering below checks *every segment* of a path, not just the
 *   leaf - otherwise a protected sub-message's own fields could be reached around it.
 * - **A `repeated` field named in the mask replaces the whole list**, not appends to it -
 *   `FieldMaskUtil.merge`'s own default (`MergeOptions.replaceRepeatedFields = false`) is the
 *   opposite (append), which is never what a REST `PATCH` should do to a list, so [applyUpdateMask]
 *   overrides it. A `repeated` field also can't be drilled into (e.g. `items.some_field`) - proto
 *   `FieldMask` has no notion of indexing into a list, so a path attempting to descend past a
 *   `repeated` field is rejected (400) rather than silently doing something undefined.
 */
object UpdateMaskSupport {
    /** AIP-134's `update_mask=*` full-replacement escape hatch - see [applyUpdateMask]. */
    const val FULL_REPLACEMENT = "*"

    /**
     * Parses the `updateMask` query parameter - a comma-separated list of field paths in the same
     * camelCase JSON naming as request/response bodies (e.g. `xsdRule.xsdPath`), consistent with
     * this API's other query params (`pageSize`, `orderBy`). Reuses `FieldMask`'s well-known-type
     * JSON mapping (which [JsonFormat] already implements) to convert each camelCase segment back
     * to its real proto (snake_case) path, rather than hand-rolling that conversion.
     *
     * `null` - the param *absent from the request entirely* - is deliberately distinct from a
     * *present but blank* value (`updateMask=`, or all-whitespace), which returns an empty
     * [FieldMask] (zero `paths`) instead: [resolveEffectiveMask] resolves the two to opposite
     * outcomes (full replacement vs. an ordinary partial update - see the class doc), so collapsing
     * them here would lose that distinction. The literal value [FULL_REPLACEMENT] (`*`) has no case
     * to convert and passes through unchanged.
     */
    fun parse(raw: String?): FieldMask? {
        if (raw == null || raw.trim() == "*") return null
        if (raw.isBlank()) return FieldMask.getDefaultInstance()
        val builder = FieldMask.newBuilder()
        JsonFormat.parser().merge("\"$raw\"", builder)
        return builder.build()
    }

    /**
     * The mask to actually apply: a `null` [mask] (`update_mask` absent from the request entirely)
     * becomes [FULL_REPLACEMENT] - full replacement is what an absent mask means here, same as the
     * literal `*` (see the class doc) - a present-but-empty [mask] becomes every field [patch]
     * actually populated, and anything else (including [FULL_REPLACEMENT] itself, or real field
     * paths) is returned unchanged.
     */
    fun <T : Message> resolveEffectiveMask(
        patch: T,
        mask: FieldMask?,
    ): FieldMask =
        when {
            mask == null -> FieldMask.newBuilder().addPaths(FULL_REPLACEMENT).build()
            mask.pathsCount == 0 -> FieldMask.newBuilder().addAllPaths(patch.allFields.keys.map { it.name }).build()
            else -> mask
        }

    /**
     * Merges [patch] onto [current] for exactly the fields named in [mask], after silently
     * dropping any path that names (at any depth) an `IDENTIFIER` or `OUTPUT_ONLY` field. An
     * unknown field name anywhere in a path is a client error (400).
     *
     * [mask] set to exactly [FULL_REPLACEMENT] (`*`) is handled separately: since that's not a
     * real field path, it's expanded here to every one of [current]'s own top-level field names -
     * and merged with `MergeOptions.replaceMessageFields = true` as well as `replaceRepeatedFields`, so a
     * message-typed field is replaced wholesale (or cleared if [patch] doesn't set it) instead of recursively merged.
     * `*` is the one case where an unpopulated field in [patch] is itself meaningful, matching AIP-134's
     * PUT-equivalent semantics for an API that otherwise only exposes `PATCH`.
     */
    fun <T : Message> applyUpdateMask(
        current: T,
        patch: T,
        mask: FieldMask,
    ): T {
        val descriptor = current.descriptorForType
        val fullReplacement = mask.pathsList == listOf(FULL_REPLACEMENT)
        val paths = if (fullReplacement) descriptor.fields.map { it.name } else mask.pathsList
        val allowedPaths = paths.filter { isPathAllowed(descriptor, it) }
        val filteredMask = FieldMask.newBuilder().addAllPaths(allowedPaths).build()

        val mergeOptions = MergeOptions().setReplaceRepeatedFields(true).setReplaceMessageFields(fullReplacement)
        val builder = current.toBuilder()
        FieldMaskUtil.merge(filteredMask, patch, builder, mergeOptions)

        @Suppress("UNCHECKED_CAST")
        return builder.build() as T
    }

    /** [resolveEffectiveMask] followed by [applyUpdateMask] - the usual way callers use both together. */
    fun <T : Message> merge(
        current: T,
        patch: T,
        mask: FieldMask?,
    ): T = applyUpdateMask(current, patch, resolveEffectiveMask(patch, mask))

    /** Walks [path]'s dot-separated segments against [rootDescriptor], rejecting an unknown one and stopping at a protected one. */
    private fun isPathAllowed(
        rootDescriptor: Descriptor,
        path: String,
    ): Boolean {
        var descriptor = rootDescriptor
        val segments = path.split(".")
        segments.forEachIndexed { index, segment ->
            val field: FieldDescriptor =
                descriptor.findFieldByName(segment)
                    ?: throw IllegalArgumentException(
                        "update_mask names an unknown field: '$path' (no '$segment' on ${descriptor.fullName})",
                    )
            if (field.hasBehavior(FieldBehavior.IDENTIFIER) || field.hasBehavior(FieldBehavior.OUTPUT_ONLY)) {
                return false
            }
            if (index < segments.lastIndex) {
                require(field.type == FieldDescriptor.Type.MESSAGE && !field.isRepeated) {
                    "update_mask path '$path' descends into '$segment', which is not a singular message field"
                }
                descriptor = field.messageType
            }
        }
        return true
    }
}
