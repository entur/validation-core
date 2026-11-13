package no.entur.gcs

import com.google.cloud.storage.BlobId

/** A [BlobId] and its contents as a [ByteArray]. */
class GcsFile(
    val blobId: BlobId,
    val bytes: ByteArray,
)
