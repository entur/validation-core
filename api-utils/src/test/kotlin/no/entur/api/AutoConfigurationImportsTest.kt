package no.entur.api

import no.entur.api.openapi.OpenApiController
import no.entur.api.security.AudienceValidationGuard
import no.entur.api.security.MethodSecurityConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A consuming Spring Boot application's own component scan never reaches `no.entur.api` (this
 * module's package), so these classes rely entirely on
 * `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` - the file
 * Boot's own `AutoConfigurationImportSelector` reads - to be registered at all. See proto-utils's
 * own equivalent test.
 */
internal class AutoConfigurationImportsTest {
    @Test
    fun `the autoconfiguration imports file lists exactly OpenApiController, AudienceValidationGuard and MethodSecurityConfig`() {
        val resource = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
        val lines =
            javaClass.classLoader
                .getResourceAsStream(resource)!!
                .bufferedReader()
                .readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }

        assertEquals(
            listOf(
                OpenApiController::class.qualifiedName,
                AudienceValidationGuard::class.qualifiedName,
                MethodSecurityConfig::class.qualifiedName,
            ),
            lines,
        )
        lines.forEach { Class.forName(it) }
    }
}
