package no.entur.proto.problem

/** A stale `If-Match`. Mapped to 409, not 412, because Entur's API guidelines don't allow 412. */
class ConflictException(
    message: String?,
) : RuntimeException(message)
