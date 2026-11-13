package no.entur.gcs

import com.google.cloud.storage.BlobId
import no.entur.kafka.sanitizeForLog

/**
 * Parses [uri] as a gsutil URI: `gs://bucket/object` or `gs://bucket/object#generation`.
 *
 * @throws InvalidGcsUriException if [uri] isn't a valid gsutil URI. The message is log-safe:
 * callers typically see this on an untrusted value (an event field, a delivery) that ends up
 * quoted back in a log line.
 */
fun parseGcsUri(uri: String): BlobId =
    try {
        BlobId.fromGsUtilUri(uri)
    } catch (_: IllegalArgumentException) {
        throw InvalidGcsUriException("Not a Google Storage URI (expected a gs://bucket/object link): '${sanitizeForLog(uri)}'")
    }
