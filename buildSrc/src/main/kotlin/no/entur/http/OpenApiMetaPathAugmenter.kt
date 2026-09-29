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
                                            title: kittum
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
                                            title: kittum
                                            version: 0.0.1
                                        paths: {}
                                        components:
                                            schemas: {}
        """.trimIndent()

    // gnostic itself populates the top-level `tags:` list from each real `service`'s own doc
    // comment - there's no service behind this operation's "OpenApi" tag, so it's added here too.
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
