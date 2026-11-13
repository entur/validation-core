package no.entur.gcs

import com.google.cloud.storage.BlobId
import org.slf4j.LoggerFactory
import java.time.Instant

private val logger = LoggerFactory.getLogger("no.entur.gcs.GcsUtils")

/** A GCS generation number is that object's creation time in epoch microseconds -- documented GCS behaviour. */
fun Long.asGenerationInstant(): Instant = Instant.ofEpochMilli(this / 1000)

/** A [BlobId] without its generation. */
fun BlobId.withoutGeneration(): BlobId = BlobId.of(bucket, name)

/** A [BlobId] with a changed or cleared generation. */
fun BlobId.withGeneration(generation: Long?): BlobId = BlobId.of(bucket, name, generation)
