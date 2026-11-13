package no.entur.gcs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GcsUrisTest {
    @Test
    fun `splits a bucket and a nested object name`() {
        val blobId = parseGcsUri("gs://my-bucket/codespace=RUT/index.json")

        assertEquals("my-bucket", blobId.bucket)
        assertEquals("codespace=RUT/index.json", blobId.name)
        assertNull(blobId.generation)
    }

    @Test
    fun `reads the generation after a hash`() {
        val blobId = parseGcsUri("gs://bucket/netex/dataset.zip#12345")

        assertEquals("bucket", blobId.bucket)
        assertEquals("netex/dataset.zip", blobId.name)
        assertEquals(12345L, blobId.generation)
    }

    @Test
    fun `round-trips back to the original link`() {
        val original = "gs://bucket/netex/dataset.zip"

        assertEquals(original, parseGcsUri(original).toGsUtilUri())
    }

    @Test
    fun `round-trips the generation through toGsUtilUriWithGeneration`() {
        val original = "gs://bucket/netex/dataset.zip#12345"

        assertEquals(original, parseGcsUri(original).toGsUtilUriWithGeneration())
    }

    @Test
    fun `rejects a uri with no gs scheme`() {
        assertThrows<InvalidGcsUriException> { parseGcsUri("https://my-bucket/object") }
    }

    @Test
    fun `rejects a uri with no bucket`() {
        assertThrows<InvalidGcsUriException> { parseGcsUri("gs:///object") }
    }

    @Test
    fun `rejects a uri with no object name`() {
        assertThrows<InvalidGcsUriException> { parseGcsUri("gs://my-bucket") }
        assertThrows<InvalidGcsUriException> { parseGcsUri("gs://my-bucket/") }
    }

    @Test
    fun `rejects an empty link`() {
        assertThrows<InvalidGcsUriException> { parseGcsUri("") }
    }

    @Test
    fun `still extends IllegalArgumentException for callers that only catch that`() {
        assertThrows<IllegalArgumentException> { parseGcsUri("not a uri") }
    }
}
