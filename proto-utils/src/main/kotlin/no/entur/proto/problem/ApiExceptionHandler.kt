package no.entur.proto.problem

import no.entur.http.proto.v1.ProblemDetail
import no.entur.http.proto.v1.fieldViolation
import no.entur.http.proto.v1.problemDetail
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException

/**
 * Maps exceptions raised anywhere in the API to a protobuf [ProblemDetail] response (RFC 7807),
 * so callers always get a structured, uniform error body instead of a container-specific
 * fallback: [ElementNotFoundException] to 404, [IllegalArgumentException] to 400,
 * [ConflictException] to 409, [PreconditionRequiredException] to 428, and
 * [WebExchangeBindException] (Bean Validation failures) to 400 with per-field detail in
 * [ProblemDetail.errors].
 *
 * Boot's `spring.webflux.problemdetails.enabled` fallback advice also knows how to handle
 * [WebExchangeBindException] (with a generic, field-less body). Without an explicit [Order],
 * advice bean precedence is unspecified, so this must outrank it to keep [handleValidation]'s
 * field-level detail winning for exceptions this class explicitly handles.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiExceptionHandler {
    @ExceptionHandler(ElementNotFoundException::class)
    fun handleNotFound(ex: ElementNotFoundException): ResponseEntity<ProblemDetail> = apiProblem(HttpStatus.NOT_FOUND, ex.message)

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> = apiProblem(HttpStatus.BAD_REQUEST, ex.message)

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(ex: ConflictException): ResponseEntity<ProblemDetail> = apiProblem(HttpStatus.CONFLICT, ex.message)

    @ExceptionHandler(PreconditionRequiredException::class)
    fun handlePreconditionRequired(ex: PreconditionRequiredException): ResponseEntity<ProblemDetail> =
        apiProblem(HttpStatus.PRECONDITION_REQUIRED, ex.message)

    /** [ProblemDetail.errors] carries the per-field detail; `detail` itself stays a generic summary. */
    @ExceptionHandler(WebExchangeBindException::class)
    fun handleValidation(ex: WebExchangeBindException): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(
                problemDetail {
                    title = HttpStatus.BAD_REQUEST.reasonPhrase
                    status = HttpStatus.BAD_REQUEST.value()
                    detail = "One or more fields failed validation."
                    errors +=
                        ex.fieldErrors.map {
                            fieldViolation {
                                field = it.field
                                message = it.defaultMessage.orEmpty()
                            }
                        }
                },
            )
}

fun apiProblem(
    status: HttpStatus,
    detail: String?,
): ResponseEntity<ProblemDetail> =
    ResponseEntity
        .status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(
            problemDetail {
                title = status.reasonPhrase
                this.status = status.value()
                detail?.let { this.detail = it }
            },
        )
