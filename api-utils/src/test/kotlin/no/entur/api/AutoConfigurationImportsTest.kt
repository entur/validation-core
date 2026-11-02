package no.entur.api

import no.entur.api.json.ProtoJsonCodecConfig
import no.entur.api.openapi.OpenApiController
import no.entur.api.problem.ApiExceptionHandler
import no.entur.api.security.AudienceValidationGuard
import no.entur.api.security.MethodSecurityConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A consumer's component scan never reaches `no.entur.api`, so the `AutoConfiguration.imports` file is the only registration. */
internal class AutoConfigurationImportsTest {
    private fun getConfigs(): List<String> =
        javaClass.classLoader
            .getResourceAsStream("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")!!
            .bufferedReader()
            .readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }

    @Test
    fun `the autoconfiguration imports file lists all bundled configs`() {
        assertEquals(
            listOf(
                OpenApiController::class.qualifiedName,
                AudienceValidationGuard::class.qualifiedName,
                MethodSecurityConfig::class.qualifiedName,
                ProtoJsonCodecConfig::class.qualifiedName,
                ApiExceptionHandler::class.qualifiedName,
            ),
            getConfigs(),
        )
    }

    @Test
    fun `the imported configs exist`() {
        getConfigs().forEach { Class.forName(it) }
    }
}
