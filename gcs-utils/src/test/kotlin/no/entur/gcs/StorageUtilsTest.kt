package no.entur.gcs

import com.google.api.gax.paging.Page
import com.google.cloud.storage.Blob
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.Storage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

internal class StorageUtilsTest {
    private val bucket = "bucket"
    private val objectName = "codespace=RUT/index.json"
    private val identity = BlobId.of(bucket, objectName)

    private val storage = mock<Storage>()

    private fun stubBlob(
        generation: Long,
        bytes: ByteArray = byteArrayOf(1, 2, 3),
    ) {
        val generationBlobId = BlobId.of(bucket, objectName, generation)
        val blob =
            mock<Blob> {
                on { this.generation } doReturn generation
                on { blobId } doReturn generationBlobId
            }
        whenever(storage.get(identity)).thenReturn(blob)
        whenever(storage.readAllBytes(generationBlobId)).thenReturn(bytes)
    }

    @Test
    fun `readIfChanged returns content the first time a generation is seen`() {
        stubBlob(generation = 1L, bytes = byteArrayOf(9))

        val file = storage.readIfChanged(identity)

        assertEquals(1L, file?.blobId?.generation)
        assertEquals(listOf<Byte>(9), file?.bytes?.toList())
    }

    @Test
    fun `readIfChanged returns null once its generation has been confirmed`() {
        stubBlob(generation = 1L)

        assertNull(storage.readIfChanged(BlobId.of(bucket, objectName, 1L)))
    }

    @Test
    fun `a later generation is reported again even after an earlier one was confirmed`() {
        stubBlob(generation = 2L)

        val file = storage.readIfChanged(BlobId.of(bucket, objectName, 1L))

        assertEquals(2L, file?.blobId?.generation)
    }

    @Test
    fun `readIfChanged returns null for a blob that does not exist`() {
        whenever(storage.get(identity)).thenReturn(null)

        assertNull(storage.readIfChanged(identity))
    }

    @Test
    fun `newest returns the blob's current generation`() {
        stubBlob(generation = 1L)

        assertEquals(1L, storage.newest(identity)?.generation)
    }

    @Test
    fun `newest returns null for a blob that does not exist`() {
        whenever(storage.get(identity)).thenReturn(null)

        assertNull(storage.newest(identity))
    }

    @Test
    fun `listGenerations returns the whole history, oldest first, as GCS's own versioned listing already orders it`() {
        stubListing(blobsOfGenerations(1L, 2L, 3L))

        assertEquals(listOf(1L, 2L, 3L), storage.listGenerations(bucket, objectName))
    }

    @Test
    fun `listGenerations ignores another object sharing the same prefix`() {
        stubListing(blobsOfGenerations(1L, 2L) + mock<Blob> { on { name } doReturn "$objectName.bak" })

        assertEquals(listOf(1L, 2L), storage.listGenerations(bucket, objectName))
    }

    private fun blobsOfGenerations(vararg generations: Long): List<Blob> =
        generations.map { generation ->
            mock<Blob> {
                on { name } doReturn objectName
                on { this.generation } doReturn generation
            }
        }

    private fun stubListing(blobs: List<Blob>) {
        val page = mock<Page<Blob>>()
        whenever(page.iterateAll()).thenReturn(blobs)
        whenever(
            storage.list(eq(bucket), any<Storage.BlobListOption>(), any<Storage.BlobListOption>()),
        ).thenReturn(page)
    }
}
