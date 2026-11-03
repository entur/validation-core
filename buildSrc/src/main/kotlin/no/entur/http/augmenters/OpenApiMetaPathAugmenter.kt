package no.entur.http.augmenters

import no.entur.http.yamlMap
import no.entur.http.yamlMapOrNull
import org.yaml.snakeyaml.Yaml

/** Adds the `/v3/api-docs` path that serves the spec itself (YAML, or JSON via `Accept`). */
class OpenApiMetaPathAugmenter {
    private val apiDocsPathYaml =
        """
        /v3/api-docs:
            get:
                tags:
                  - OpenApi
                summary: Get the OpenAPI specification for this service
                description: Returns this specification itself, as YAML by default or JSON when requested via the Accept header.
                operationId: getOpenApiSpec
                parameters:
                  - name: Accept
                    in: header
                    description: The desired response format - `application/yaml` (the default) or `application/json`.
                    required: false
                    schema:
                        type: string
                responses:
                    "200":
                        description: The OpenAPI specification
                        content:
                            application/yaml:
                                schema:
                                    type: string
                                    example: |
                                        openapi: 3.0.3
                                        info:
                                            title: api-name
                                            version: 0.0.1
                                        paths: {}
                                        components:
                                            schemas: {}
                            application/json:
                                schema:
                                    type: object
                                    example:
                                        openapi: 3.0.3
                                        info:
                                            title: api-name
                                            version: 0.0.1
                                        paths: {}
                                        components:
                                            schemas: {}
        """.trimIndent()

    // gnostic builds `tags:` from services, and no service backs the "OpenApi" tag, so it's added here.
    private val apiDocsTagYaml =
        """
        name: OpenApi
        description: This service's own generated OpenAPI specification.
        """.trimIndent()

    fun augment(spec: MutableMap<String, Any?>) {
        val paths = yamlMap(spec["paths"])
        val (path, pathItem) = yamlMap(Yaml().load(apiDocsPathYaml)).entries.single()
        check(path !in paths) {
            "Path '$path' from OpenApiMetaPathAugmenter collides with an existing path in the generated spec"
        }
        paths[path] = pathItem
        spec["paths"] = paths.toSortedMap()

        val tag = yamlMap(Yaml().load(apiDocsTagYaml))
        @Suppress("UNCHECKED_CAST")
        val tags = spec.getOrPut("tags") { mutableListOf<Any?>() } as MutableList<Any?>
        check(tags.none { yamlMapOrNull(it)?.get("name") == tag["name"] }) {
            "Tag '${tag["name"]}' from OpenApiMetaPathAugmenter collides with an existing tag in the generated spec"
        }
        tags += tag
        tags.sortBy { yamlMap(it)["name"] as String }
    }
}
