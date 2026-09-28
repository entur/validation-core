package no.entur.proto.http

import com.google.protobuf.FieldMask
import com.google.protobuf.Message
import com.google.protobuf.Timestamp
import no.entur.proto.testfixtures.v1.WidgetStatus
import no.entur.proto.testfixtures.v1.detail
import no.entur.proto.testfixtures.v1.part
import no.entur.proto.testfixtures.v1.widget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit coverage for [UpdateMaskSupport], exercised against the `widget.proto` test fixture types,
 * which already have the camelCase/multi-segment/`IDENTIFIER`/`OUTPUT_ONLY`/`repeated` shapes needed
 * to cover the class's documented behavior - including, per the class doc, that `update_mask`
 * *absent from the request entirely* and *present but empty* are two different things (full
 * replacement vs. an ordinary partial update), not one "no mask" case.
 */
internal class UpdateMaskSupportTest {
    // parseUpdateMask

    @Test
    fun `parseUpdateMask returns null when the param is absent entirely`() {
        assertNull(UpdateMaskSupport.parse(null))
    }

    @Test
    fun `parseUpdateMask returns null for the literal full-replacement value`() {
        assertNull(UpdateMaskSupport.parse(UpdateMaskSupport.FULL_REPLACEMENT))
    }

    @Test
    fun `parseUpdateMask returns an empty (non-null) mask for a present but blank value`() {
        assertEquals(0, UpdateMaskSupport.parse("")!!.pathsCount)
        assertEquals(0, UpdateMaskSupport.parse("   ")!!.pathsCount)
    }

    private fun assertParse(
        expected: List<String>,
        raw: String,
    ) {
        assertEquals(expected, UpdateMaskSupport.parse(raw)!!.pathsList)
    }

    @Test
    fun `parseUpdateMask converts a single camelCase segment to snake_case`() {
        assertParse(listOf("updated_at"), "updatedAt")
    }

    @Test
    fun `parseUpdateMask converts every segment of a dotted camelCase path`() {
        assertParse(listOf("detail.generated_note"), "detail.generatedNote")
    }

    @Test
    fun `parseUpdateMask splits a comma-separated list into one path per entry`() {
        assertParse(listOf("name", "status", "detail.generated_note"), "name,status,detail.generatedNote")
    }

    // resolveEffectiveMask

    fun assertEffectiveMask(
        message: Message,
        fieldMask: FieldMask?,
        vararg mask: String,
    ) {
        assertEquals(
            mask.toSet(),
            UpdateMaskSupport.resolveEffectiveMask(message, fieldMask).pathsList.toSet(),
        )
    }

    @Test
    fun `resolveEffectiveMask resolves an absent (null) mask to full replacement`() {
        assertEffectiveMask(widget { name = "d" }, null, UpdateMaskSupport.FULL_REPLACEMENT)
    }

    @Test
    fun `resolveEffectiveMask resolves a present-but-empty mask to every populated field of patch, not full replacement`() {
        assertEffectiveMask(
            widget {
                name = "d"
                status = WidgetStatus.WIDGET_STATUS_ACTIVE
                parts += part { label = "p" }
                // id and updated_at left unset (proto3 default) - must not appear below.
                id = 0
            },
            FieldMask.getDefaultInstance(),
            "name",
            "status",
            "parts",
        )
    }

    @Test
    fun `resolveEffectiveMask returns a mask naming real paths unchanged, ignoring patch entirely`() {
        val mask = FieldMask.newBuilder().addPaths("status").build()
        assertEquals(mask, UpdateMaskSupport.resolveEffectiveMask(widget { name = "d" }, mask))
    }

    @Test
    fun `resolveEffectiveMask returns an explicit full-replacement mask unchanged`() {
        val mask = FieldMask.newBuilder().addPaths(UpdateMaskSupport.FULL_REPLACEMENT).build()
        assertEquals(mask, UpdateMaskSupport.resolveEffectiveMask(widget { name = "d" }, mask))
    }

    // applyUpdateMask - scalar fields

    @Test
    fun `applyUpdateMask merges only the field named in the mask, leaving other current fields untouched`() {
        val result =
            UpdateMaskSupport.applyUpdateMask(
                widget {
                    id = 1
                    name = "old"
                    status = WidgetStatus.WIDGET_STATUS_ACTIVE
                },
                widget { name = "new" },
                FieldMask.newBuilder().addPaths("name").build(),
            )

        assertEquals(
            widget {
                id = 1
                name = "new"
                status = WidgetStatus.WIDGET_STATUS_ACTIVE
            },
            result,
        )
    }

    @Test
    fun `applyUpdateMask silently drops an IDENTIFIER path instead of applying it`() {
        val result =
            UpdateMaskSupport.applyUpdateMask(
                widget { id = 1 },
                widget { id = 999 },
                FieldMask.newBuilder().addPaths("id").build(),
            )

        assertEquals(1, result.id)
    }

    @Test
    fun `applyUpdateMask silently drops an OUTPUT_ONLY path instead of applying it`() {
        val originalTimestamp = Timestamp.newBuilder().setSeconds(1_000).build()
        val result =
            UpdateMaskSupport.applyUpdateMask(
                widget { updatedAt = originalTimestamp },
                widget { updatedAt = Timestamp.newBuilder().setSeconds(2_000).build() },
                FieldMask.newBuilder().addPaths("updated_at").build(),
            )

        assertEquals(originalTimestamp, result.updatedAt)
    }

    @Test
    fun `applyUpdateMask rejects an unknown field name with an IllegalArgumentException`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                UpdateMaskSupport.applyUpdateMask(
                    widget { name = "d" },
                    widget { name = "d" },
                    FieldMask.newBuilder().addPaths("bogusField").build(),
                )
            }
        assertTrue(exception.message!!.contains("bogusField"))
    }

    // applyUpdateMask - sub-message masks (full and partial)

    @Test
    fun `a sub-message path merges only that one nested field, leaving its siblings alone`() {
        val result =
            UpdateMaskSupport.applyUpdateMask(
                part {
                    detail =
                        detail {
                            generatedNote = "original note"
                            value = "old"
                        }
                },
                part {
                    detail =
                        detail {
                            // Both fields set on the patch, but the mask only names detail.value below -
                            // generated_note must be ignored even though the patch does carry a value for it.
                            generatedNote = "patched note"
                            value = "new"
                        }
                },
                FieldMask.newBuilder().addPaths("detail.value").build(),
            )
        assertEquals(
            detail {
                generatedNote = "original note"
                value = "new"
            },
            result.detail,
        )
    }

    @Test
    fun `naming the whole sub-message field merges every field the patch populated on it`() {
        val result =
            UpdateMaskSupport.applyUpdateMask(
                part {
                    detail =
                        detail {
                            generatedNote = "original note"
                            value = "old"
                        }
                },
                part {
                    detail =
                        detail {
                            generatedNote = "patched note"
                            value = "new"
                        }
                },
                FieldMask.newBuilder().addPaths("detail").build(),
            )
        assertEquals(
            detail {
                generatedNote = "patched note"
                value = "new"
            },
            result.detail,
        )
    }

    @Test
    fun `a path reaching a protected field through a sub-message is dropped at that segment, not applied around it`() {
        val result =
            UpdateMaskSupport.applyUpdateMask(
                part { detail = detail { value = "old" } },
                part {
                    detail =
                        detail {
                            // generated_note is OUTPUT_ONLY on Detail, so this path must never reach it.
                            generatedNote = "smuggled in via update_mask"
                            value = "old"
                        }
                },
                FieldMask.newBuilder().addPaths("detail.generated_note").build(),
            )
        assertEquals("", result.detail.generatedNote)
    }

    // applyUpdateMask - repeated fields

    @Test
    fun `a repeated field named in the mask replaces the whole list rather than appending to it`() {
        val result =
            UpdateMaskSupport.applyUpdateMask(
                widget {
                    parts += part { id = 10 }
                    parts += part { id = 11 }
                },
                widget { parts += part { id = 12 } },
                FieldMask.newBuilder().addPaths("parts").build(),
            )

        assertEquals(listOf(12L), result.partsList.map { it.id })
    }

    @Test
    fun `descending past a repeated field in the path is rejected rather than silently ignored`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                UpdateMaskSupport.applyUpdateMask(
                    widget { parts += part { id = 1 } },
                    widget { parts += part { id = 1 } },
                    FieldMask.newBuilder().addPaths("parts.id").build(),
                )
            }
        assertTrue(exception.message!!.contains("parts"))
    }

    // applyUpdateMask - full replacement ("*", and an absent mask which resolves to it)

    @Test
    fun `full replacement resets an unpopulated scalar field to its default, unlike any other mask`() {
        val result =
            UpdateMaskSupport.applyUpdateMask(
                widget {
                    status = WidgetStatus.WIDGET_STATUS_ACTIVE
                    name = "old"
                },
                widget { status = WidgetStatus.WIDGET_STATUS_INACTIVE },
                FieldMask.newBuilder().addPaths(UpdateMaskSupport.FULL_REPLACEMENT).build(),
            )

        assertEquals(widget { status = WidgetStatus.WIDGET_STATUS_INACTIVE }, result)
    }

    @Test
    fun `full replacement still drops IDENTIFIER and OUTPUT_ONLY fields, preserving current's values`() {
        val originalTimestamp = Timestamp.newBuilder().setSeconds(1_000).build()
        val result =
            UpdateMaskSupport.applyUpdateMask(
                widget {
                    id = 1
                    updatedAt = originalTimestamp
                },
                widget {
                    id = 999
                    updatedAt = Timestamp.newBuilder().setSeconds(2_000).build()
                },
                FieldMask.newBuilder().addPaths(UpdateMaskSupport.FULL_REPLACEMENT).build(),
            )

        assertEquals(
            widget {
                id = 1
                updatedAt = originalTimestamp
            },
            result,
        )
    }

    @Test
    fun `full replacement replaces a sub-message wholesale, resetting its unpopulated fields too`() {
        val result =
            UpdateMaskSupport.applyUpdateMask(
                part {
                    label = "l"
                    detail =
                        detail {
                            generatedNote = "g"
                            value = "old"
                        }
                },
                part {
                    label = "l"
                    detail = detail { value = "new" }
                },
                FieldMask.newBuilder().addPaths(UpdateMaskSupport.FULL_REPLACEMENT).build(),
            )
        assertEquals(detail { value = "new" }, result.detail)
    }

    @Test
    fun `full replacement clears a sub-message entirely when patch doesn't set it at all`() {
        val result =
            UpdateMaskSupport.applyUpdateMask(
                part {
                    label = "l"
                    detail = detail { value = "v" }
                },
                part { label = "renamed" },
                FieldMask.newBuilder().addPaths(UpdateMaskSupport.FULL_REPLACEMENT).build(),
            )

        assertFalse(result.hasDetail())
    }

    @Test
    fun `full replacement clears a repeated field entirely when patch doesn't set it`() {
        val result =
            UpdateMaskSupport.applyUpdateMask(
                widget { parts += part { id = 1 } },
                widget { name = "renamed" },
                FieldMask.newBuilder().addPaths(UpdateMaskSupport.FULL_REPLACEMENT).build(),
            )

        assertEquals(widget { name = "renamed" }, result)
    }

    @Test
    fun `an absent mask, once resolved, behaves identically to the literal full-replacement value`() {
        val result =
            UpdateMaskSupport.applyUpdateMask(
                widget {
                    status = WidgetStatus.WIDGET_STATUS_ACTIVE
                    name = "old"
                },
                widget { status = WidgetStatus.WIDGET_STATUS_INACTIVE },
                UpdateMaskSupport.resolveEffectiveMask(widget { status = WidgetStatus.WIDGET_STATUS_INACTIVE }, null),
            )

        assertEquals(widget { status = WidgetStatus.WIDGET_STATUS_INACTIVE }, result)
    }

    @Test
    fun `the full-replacement value combined with other paths is not treated as full replacement and is rejected as an unknown field`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                UpdateMaskSupport.applyUpdateMask(
                    widget { name = "d" },
                    widget { name = "d" },
                    FieldMask
                        .newBuilder()
                        .addPaths(UpdateMaskSupport.FULL_REPLACEMENT)
                        .addPaths("status")
                        .build(),
                )
            }
        assertTrue(exception.message!!.contains("*"))
    }
}
