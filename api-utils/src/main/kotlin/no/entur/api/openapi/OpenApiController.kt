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
 * Serves the OpenAPI spec bundled on the classpath at [specLocation] (an app's own `openapi/<api>.yaml`,
 * copied in from its `*-api` jar) as YAML or, on request, JSON.
 */
@RestController
class OpenApiController(
    private val resourceLoader: ResourceLoader,
    @Value("\${entur.api.openapi.spec-location}") private val specLocation: String,
) {
    @GetMapping("/v3/api-docs")
    suspend fun getOpenApiSpec(
        @RequestHeader(HttpHeaders.ACCEPT, required = false) accept: String?,
    ): ResponseEntity<String> {
        val yamlContent = loadSpec()
        return when (ContentNegotiationSupport.bestMatch(accept, YAML_MEDIA_TYPE, MediaType.APPLICATION_JSON)) {
            MediaType.APPLICATION_JSON -> {
                ResponseEntity
                    .ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(YamlJsonConverter.yamlToJson(yamlContent))
            }

            else -> {
                ResponseEntity
                    .ok()
                    .contentType(YAML_MEDIA_TYPE)
                    .body(yamlContent)
            }
        }
    }

    private fun loadSpec(): String {
        val resource = resourceLoader.getResource("classpath:$specLocation")
        return resource.inputStream.bufferedReader().use { it.readText() }
    }
}
