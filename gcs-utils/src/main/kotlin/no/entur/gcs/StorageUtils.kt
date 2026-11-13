package no.entur.gcs

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.Storage
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("no.entur.gcs.GcsPolling")

/**
 * [blob]'s current content, or null if its generation hasn't changed or the object no longer exists.
 * Pass a [blob] with no generation set to get the newest content.
 */
fun Storage.readIfChanged(blob: BlobId): GcsFile? {
    val current = newest(blob) ?: return null
    if (current.generation == blob.generation) {
        logger.debug("{} is unchanged since the last poll (generation {})", blob.toGsUtilUri(), current.generation)
        return null
    }
    return GcsFile(blob.withGeneration(current.generation), readAllBytes(current))
}

/**
 * [blob]'s newest generation, or null if it does not exist. ([blob]'s generation itself is ignored.)
 */
fun Storage.newest(blob: BlobId): BlobId? =
    get(blob.withoutGeneration())?.blobId ?: run {
        logger.warn("{} does not exist", blob.toGsUtilUri())
        null
    }

/**
 * [objectName]'s whole overwrite history under [bucket], oldest first.
 *
 * (There is no way to ask GCS for generations in descending order.)
 */
fun Storage.listGenerations(
    bucket: String,
    objectName: String,
): List<Long> =
    list(bucket, Storage.BlobListOption.prefix(objectName), Storage.BlobListOption.versions(true))
        .iterateAll()
        .filter { it.name == objectName }
        .map { it.generation }
