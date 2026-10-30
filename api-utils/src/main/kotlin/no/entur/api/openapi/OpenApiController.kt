package no.entur.api.openapi

import no.entur.api.http.ContentNegotiationSupport
import no.entur.api.http.YamlJsonConverter
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ResourceLoader
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.MediaType.APPLICATION_YAML
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * Serves the OpenAPI spec bundled on the classpath at `specLocation`. Supports YAML (the default) and JSON
 * representations. The resource is only read once during construction.
 */
@RestController
class OpenApiController(
    resourceLoader: ResourceLoader,
    @Value("\${entur.api.openapi.spec-location}") specLocation: String,
) {
    private val yamlSpec: String =
        resourceLoader
            .getResource("classpath:$specLocation")
            .inputStream
            .bufferedReader()
            .use { it.readText() }
    private val jsonSpec: String = YamlJsonConverter.yamlToJson(yamlSpec)

    @GetMapping("/v3/api-docs")
    suspend fun getOpenApiSpec(
        @RequestHeader(HttpHeaders.ACCEPT, required = false) accept: String?,
    ): ResponseEntity<String> {
        val ok = ResponseEntity.ok()
        return when (ContentNegotiationSupport.bestMatch(accept, APPLICATION_YAML, MediaType.APPLICATION_JSON)) {
            MediaType.APPLICATION_JSON -> ok.contentType(MediaType.APPLICATION_JSON).body(jsonSpec)
            else -> ok.contentType(APPLICATION_YAML).body(yamlSpec)
        }
    }
}
