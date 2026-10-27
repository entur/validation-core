package no.entur.api.openapi

import no.entur.api.http.ContentNegotiationSupport
import no.entur.api.http.YamlJsonConverter
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ResourceLoader
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

private val YAML_MEDIA_TYPE = MediaType.parseMediaType("application/yaml")

/**
 * Serves the OpenAPI spec bundled on the classpath at `specLocation` (an app's own `openapi/<api>.yaml`,
 * copied in from its `*-api` jar) as YAML or, on request, JSON. Both representations are read and
 * converted once, at startup, since the bundled spec can't change while the app is running - a
 * `suspend` handler wouldn't otherwise keep that work off the event-loop thread per request.
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
    ): ResponseEntity<String> =
        when (ContentNegotiationSupport.bestMatch(accept, YAML_MEDIA_TYPE, MediaType.APPLICATION_JSON)) {
            MediaType.APPLICATION_JSON -> {
                ResponseEntity
                    .ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonSpec)
            }

            else -> {
                ResponseEntity
                    .ok()
                    .contentType(YAML_MEDIA_TYPE)
                    .body(yamlSpec)
            }
        }
}
