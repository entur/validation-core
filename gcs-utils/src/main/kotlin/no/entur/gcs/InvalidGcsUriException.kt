package no.entur.gcs

/**
 * Thrown by [parseGcsUri] for a link that is not `gs://bucket/object` (or `#generation`).
 *
 * A type of its own, not a bare [IllegalArgumentException], so failure classification can tell
 * this permanent, never-will-parse input from any other argument check (e.g. a config guard,
 * which must stall). Still extends [IllegalArgumentException] so callers catching that keep working.
 */
class InvalidGcsUriException(
    message: String,
) : IllegalArgumentException(message)
