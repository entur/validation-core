package no.entur.http

import org.yaml.snakeyaml.Yaml

/**
 * Adds the `/v3/api-docs` path - the module's own generated OpenAPI spec, served back as YAML by
 * default or JSON when requested via the `Accept` header - to a gnostic-generated openapi.yaml.
 */
class OpenApiMetaPathAugmenter {
    // A literal fragment of the target spec.
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
                            application/json:
                                schema:
                                    type: object
        """.trimIndent()

    fun augment(spec: MutableMap<String, Any?>) {
        val paths = yamlMap(spec["paths"])
        val (path, pathItem) = yamlMap(Yaml().load(apiDocsPathYaml)).entries.single()
        check(path !in paths) {
            "Path '$path' from OpenApiMetaPathAugmenter collides with an existing path in the generated spec"
        }
        paths[path] = pathItem
        spec["paths"] = paths.toSortedMap()
    }
}
