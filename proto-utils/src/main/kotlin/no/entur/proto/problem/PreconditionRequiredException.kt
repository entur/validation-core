package no.entur.proto.problem

/**
 * The `If-Match` header (RFC 7232 §3) required on `Update`/`Delete` is missing. Thrown explicitly
 * by controllers (using [no.entur.proto.http.requireIfMatch]) rather than relying on Spring's own required-header
 * enforcement: `@RequestHeader`'s missing-value exception (`MissingRequestHeaderException` under Spring MVC)
 * extends `jakarta.servlet.ServletException`, which doesn't exist on WebFlux's classpath - there is
 * no reactive equivalent with the same specific, catchable type.
 */
class PreconditionRequiredException(
    message: String,
) : RuntimeException(message)
