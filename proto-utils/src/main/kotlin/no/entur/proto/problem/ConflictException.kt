package no.entur.proto.problem

/**
 * Thrown when a request's `If-Match` no longer matches the resource's current `ETag`. Mapped to 409, not 412, because
 * Entur's API guidelines don't allow 412.
 */
class ConflictException(
    message: String?,
) : RuntimeException(message)
