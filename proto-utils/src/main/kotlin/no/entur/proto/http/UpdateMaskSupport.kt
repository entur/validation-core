package no.entur.proto.http

import com.google.api.FieldBehavior
import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.FieldMask
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.Message
import com.google.protobuf.fieldMask
import com.google.protobuf.util.FieldMaskUtil
import com.google.protobuf.util.FieldMaskUtil.MergeOptions
import com.google.protobuf.util.JsonFormat
import no.entur.proto.reflect.hasBehavior

/**
 * `update_mask` handling for `Update*` endpoints, per [AIP-134](https://google.aip.dev/134):
 *
 * - **absent, or `*`**: full replacement - every non-protected top-level field takes [patch]'s value,
 *   defaults included.
 * - **present but empty**: only the fields [patch] populated are applied; clearing a field to its
 *   default requires naming it.
 * - **field paths**: only those fields, default or not.
 *
 * `IDENTIFIER`/`OUTPUT_ONLY` are checked on every segment of a dotted path, so a protected
 * sub-message can't be reached around. A named `repeated` field replaces the whole list
 * (`FieldMaskUtil`'s default appends), and a path can't descend into one.
 */
object UpdateMaskSupport {
    /** AIP-134's `update_mask=*` full-replacement escape hatch. */
    const val FULL_REPLACEMENT = "*"

    /**
     * Parses the camelCase `updateMask` query parameter into snake_case proto paths. Absent or `*`
     * returns `null` (full replacement); blank returns an empty mask (partial update), so keep the two
     * apart. Unknown field names are only rejected later, by [applyUpdateMask].
     */
    fun parse(updateMask: String?): FieldMask? {
        if (updateMask == null || updateMask.trim() == "*") return null
        if (updateMask.isBlank()) return FieldMask.getDefaultInstance()
        val builder = FieldMask.newBuilder()
        try {
            JsonFormat.parser().merge("\"$updateMask\"", builder)
        } catch (e: InvalidProtocolBufferException) {
            throw IllegalArgumentException("update_mask is malformed: '$updateMask'", e)
        }
        return builder.build()
    }

    /**
     * The mask to apply: `null` becomes [FULL_REPLACEMENT], an empty [mask] becomes the fields [patch] populated, and
     * anything else is unchanged.
     */
    fun <T : Message> resolveEffectiveMask(
        patch: T,
        mask: FieldMask?,
    ): FieldMask =
        when {
            mask == null -> fieldMask { paths += FULL_REPLACEMENT }
            mask.pathsCount == 0 -> fieldMask { paths += patch.allFields.keys.map { it.name } }
            else -> mask
        }

    /**
     * Merges [patch] onto [current] for the fields in [mask], silently dropping protected paths and
     * rejecting unknown ones. Under [FULL_REPLACEMENT], message fields are replaced, not merged.
     */
    fun <T : Message> applyUpdateMask(
        current: T,
        patch: T,
        mask: FieldMask,
    ): T {
        val descriptor = current.descriptorForType
        val fullReplacement = mask.pathsList == listOf(FULL_REPLACEMENT)
        val candidatePaths = if (fullReplacement) descriptor.fields.map { it.name } else mask.pathsList
        val allowedPaths = candidatePaths.filter { isPathAllowed(descriptor, it) }
        val filteredMask = fieldMask { paths += allowedPaths }

        val mergeOptions = MergeOptions().setReplaceRepeatedFields(true).setReplaceMessageFields(fullReplacement)
        val builder = current.toBuilder()
        FieldMaskUtil.merge(filteredMask, patch, builder, mergeOptions)

        @Suppress("UNCHECKED_CAST")
        return builder.build() as T
    }

    /** [resolveEffectiveMask] followed by [applyUpdateMask]. */
    fun <T : Message> merge(
        current: T,
        patch: T,
        mask: FieldMask?,
    ): T = applyUpdateMask(current, patch, resolveEffectiveMask(patch, mask))

    /**
     * Whether [path] may be applied: `false` at its first protected segment.
     *
     * @throws IllegalArgumentException for an unknown segment, or one that descends into a non-singular-message field.
     */
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
