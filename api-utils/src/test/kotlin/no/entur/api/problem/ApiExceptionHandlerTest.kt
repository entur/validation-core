package no.entur.api.problem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.web.server.MissingRequestValueException

/** Only [ApiExceptionHandler.handleMissingRequestValue] branches on more than the exception type. */
internal class ApiExceptionHandlerTest {
    private val handler = ApiExceptionHandler()

    @Suppress("UNUSED_PARAMETER")
    private fun dummyTarget(value: String) = Unit

    private fun missingValue(
        name: String,
        label: String,
    ): MissingRequestValueException {
        val parameter = MethodParameter(ApiExceptionHandlerTest::class.java.getDeclaredMethod("dummyTarget", String::class.java), 0)
        return MissingRequestValueException(name, String::class.java, label, parameter)
    }

    @Test
    fun `a missing If-Match header maps to 428 with this API's own problem body`() {
        val response = handler.handleMissingRequestValue(missingValue(name = "If-Match", label = "header"))

        assertEquals(HttpStatus.PRECONDITION_REQUIRED, response.statusCode)
        assertEquals("The If-Match header is required", response.body?.detail)
    }

    @Test
    fun `a missing header other than If-Match falls back to a plain 400`() {
        val response = handler.handleMissingRequestValue(missingValue(name = "X-Other", label = "header"))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `a missing query parameter named If-Match is not mistaken for the header`() {
        val response = handler.handleMissingRequestValue(missingValue(name = "If-Match", label = "query parameter"))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }
}
