package no.entur.http.augmenters

import com.google.protobuf.DescriptorProtos
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import no.entur.http.buildExtensionRegistry
import no.entur.http.buildFileDescriptors
import no.entur.http.newOpenApiYaml

/** Uses stand-ins for gnostic's and google.api's annotations, like [OpenApiExampleAugmenterTest]. */
class OpenApiQueryParameterAugmenterTest {
    private val fixtureDir = File(System.getProperty("fixtureDir") ?: error("fixtureDir system property not set"))

    // As gnostic generates it: no `required` or `minItems` on query parameters.
    private val openApiYaml =
        """
        openapi: 3.0.3
        info:
            title: mock
            version: 0.0.1
        paths:
            /things/{pathId}/search:
                get:
                    operationId: QueryService_Search
                    parameters:
                      - name: pathId
                        in: path
                        required: true
                        schema:
                            type: string
                      - name: thingIds
                        in: query
                        schema:
                            type: array
                            items:
                                type: integer
                                format: int32
                      - name: optionalFilter
                        in: query
                        schema:
                            type: string
                    responses:
                        "200":
                            description: OK
        components:
            schemas: {}
        """.trimIndent()

    private fun parameters(): Map<String, Map<String, Any?>> {
        val descriptorSetFile = File.createTempFile("fixture", ".binpb").apply { deleteOnExit() }
        val process =
            ProcessBuilder(
                protocExecutable(),
                "-I", fixtureDir.absolutePath,
                "--include_imports",
                "--descriptor_set_out=${descriptorSetFile.absolutePath}",
                "mock/query_service.proto",
            ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "protoc failed compiling the fixture:\n$output" }
        val files = buildFileDescriptors(descriptorSetFile.inputStream().use { DescriptorProtos.FileDescriptorSet.parseFrom(it) })

        val spec: MutableMap<String, Any?> = newOpenApiYaml().load(openApiYaml)
        OpenApiQueryParameterAugmenter().augment(files, buildExtensionRegistry(files), spec)

        @Suppress("UNCHECKED_CAST")
        val parameters = spec.at("paths", "/things/{pathId}/search", "get")["parameters"] as List<Map<String, Any?>>
        return parameters.associateBy { it["name"] as String }
    }

    @Test
    fun `marks a required query parameter required and copies its min_items`() {
        val thingIds = parameters().getValue("thingIds")

        assertEquals(true, thingIds["required"])
        assertEquals(1L, (thingIds.at("schema")["minItems"] as Number).toLong())
    }

    @Test
    fun `leaves an optional query parameter alone`() {
        val optionalFilter = parameters().getValue("optionalFilter")

        assertFalse("required" in optionalFilter)
        assertFalse("minItems" in optionalFilter.at("schema"))
    }

    @Test
    fun `leaves a path parameter as gnostic generated it`() {
        assertEquals(mapOf("type" to "string"), parameters().getValue("pathId").at("schema"))
    }
}
