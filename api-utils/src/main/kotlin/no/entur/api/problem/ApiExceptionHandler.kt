package no.entur.api.problem

import no.entur.http.proto.v1.ProblemDetail
import no.entur.http.proto.v1.fieldViolation
import no.entur.http.proto.v1.problemDetail
import no.entur.proto.problem.ConflictException
import no.entur.proto.problem.ElementNotFoundException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.server.MissingRequestValueException

/**
 * Maps exceptions to a protobuf [ProblemDetail] response: not found 404, [IllegalArgumentException] 400,
 * [ConflictException] 409, missing `If-Match` 428, Bean Validation failures 400 with per-field errors.
 *
 * [Order] is needed because Boot's `spring.webflux.problemdetails.enabled` advice also handles
 * [WebExchangeBindException], and advice precedence is otherwise unspecified.
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

    /**
     * Maps a missing required request value to 428 (RFC 6585) when it is the `If-Match` header, and to 400 otherwise.
     */
    @ExceptionHandler(MissingRequestValueException::class)
    fun handleMissingRequestValue(ex: MissingRequestValueException): ResponseEntity<ProblemDetail> =
        if (ex.label == "header" && ex.name == HttpHeaders.IF_MATCH) {
            apiProblem(HttpStatus.PRECONDITION_REQUIRED, "The If-Match header is required")
        } else {
            apiProblem(HttpStatus.BAD_REQUEST, ex.message)
        }

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
