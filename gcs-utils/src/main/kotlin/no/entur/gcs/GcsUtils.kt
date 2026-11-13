package no.entur.gcs

import com.google.cloud.storage.BlobId
import java.time.Instant

/** A GCS generation number is that object's creation time in epoch microseconds -- documented GCS behaviour. */
fun Long.asGenerationInstant(): Instant = Instant.ofEpochSecond(this / 1_000_000, (this % 1_000_000) * 1_000)

/** A [BlobId] without its generation. */
fun BlobId.withoutGeneration(): BlobId = BlobId.of(bucket, name)

/** A [BlobId] with a changed or cleared generation. */
fun BlobId.withGeneration(generation: Long?): BlobId = BlobId.of(bucket, name, generation)
