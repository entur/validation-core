package no.entur.proto

import no.entur.proto.json.ProtoJsonCodecConfig
import no.entur.proto.problem.ApiExceptionHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A consuming Spring Boot application's own component scan never reaches `no.entur.proto` (this
 * module's package), so [ProtoJsonCodecConfig]/[ApiExceptionHandler] rely entirely on
 * `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` - the file
 * Boot's own `AutoConfigurationImportSelector` reads - to be registered at all. This parses it the
 * same naive way (one FQCN per non-comment, non-blank line) and resolves each entry, so a
 * typo'd/stale/missing line fails the build instead of silently leaving a consumer without these
 * beans.
 */
internal class AutoConfigurationImportsTest {
    @Test
    fun `the autoconfiguration imports file lists exactly ProtoJsonCodecConfig and ApiExceptionHandler`() {
        val resource = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
        val lines =
            javaClass.classLoader
                .getResourceAsStream(resource)!!
                .bufferedReader()
                .readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }

        assertEquals(listOf(ProtoJsonCodecConfig::class.qualifiedName, ApiExceptionHandler::class.qualifiedName), lines)
        lines.forEach { Class.forName(it) }
    }
}
