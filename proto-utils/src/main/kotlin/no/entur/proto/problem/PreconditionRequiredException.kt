package no.entur.proto.problem

/**
 * The `If-Match` header (RFC 7232 §3) required on `Update`/`Delete` is missing. Thrown explicitly by
 * controllers that pair `@RequestHeader(required = false)` with [no.entur.exposed.http.requireIfMatch],
 * so the header is mandatory only where a controller's own logic calls it, not unconditionally for
 * every caller via `@RequestHeader(required = true)`. Spring MVC's missing-header exception
 * (`MissingRequestHeaderException`) extends `jakarta.servlet.ServletException`, which doesn't exist on
 * WebFlux's classpath; WebFlux throws the unrelated, generic `MissingRequestValueException` instead
 * (shared with every other missing required value, not just headers). [ApiExceptionHandler] maps both
 * this exception and that `If-Match`-specific case to the same 428.
 */
class PreconditionRequiredException(
    message: String,
) : RuntimeException(message)
