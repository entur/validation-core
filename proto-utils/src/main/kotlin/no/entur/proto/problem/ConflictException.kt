package no.entur.proto.problem

/**
 * Thrown when a conditional request's `If-Match` header no longer matches a resource's current
 * `ETag` (RFC 7232) - e.g. it was updated concurrently since the caller last read it. Converted to
 * 409 Conflict (not 412 Precondition Failed - Entur's API guidelines don't allow 412) if
 * encountered during a REST call.
 */
class ConflictException(
    message: String?,
) : RuntimeException(message)
