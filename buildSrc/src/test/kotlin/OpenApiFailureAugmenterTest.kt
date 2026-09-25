import com.google.protobuf.DescriptorProtos
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Exercises OpenApiFailureAugmenter against the real entur.http.v1.Failure/ProblemDetail (from
 * http-model, this repo's actual published module for them) plus a minimal mock service
 * (buildSrc/src/test/resources/fixture/mock/service.proto) that only exists to carry a couple of
 * rpcs to attach `(entur.http.v1.failure)` options to - so this test doesn't depend on any real
 * API module's (e.g. kittum-api's) specific business content, without re-declaring entur.http.v1's
 * own types a second time just for the test.
 */
class OpenApiFailureAugmenterTest {
    private val fixtureDir = File(System.getProperty("fixtureDir") ?: error("fixtureDir system property not set"))
    private val httpModelProtoDir =
        File(System.getProperty("httpModelProtoDir") ?: error("httpModelProtoDir system property not set"))

    private val minimalOpenApiYaml =
        """
        openapi: 3.0.3
        info:
            title: mock
            version: 0.0.1
        paths:
            /things:
                get:
                    operationId: MockService_GetThing
                    responses:
                        "200":
                            description: OK
            /things/list:
                get:
                    operationId: MockService_ListThings
                    responses:
                        "200":
                            description: OK
        components:
            schemas: {}
        """.trimIndent()

    private fun compileFixture(mockProtoFile: String = "mock/service.proto"): DescriptorProtos.FileDescriptorSet {
        val descriptorSetFile = File.createTempFile("fixture", ".binpb").apply { deleteOnExit() }
        val protoc = File(System.getProperty("user.home"), ".local/share/mise/shims/protoc")
            .let { if (it.canExecute()) it.absolutePath else "protoc" }

        val process =
            ProcessBuilder(
                protoc,
                "-I", httpModelProtoDir.absolutePath,
                "-I", fixtureDir.absolutePath,
                "--include_source_info",
                "--include_imports",
                "--descriptor_set_out=${descriptorSetFile.absolutePath}",
                "entur/http/v1/failure.proto",
                "entur/http/v1/problem_detail.proto",
                mockProtoFile,
            ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "protoc failed compiling the fixture:\n$output" }

        return descriptorSetFile.inputStream().use { DescriptorProtos.FileDescriptorSet.parseFrom(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.at(vararg keys: String): Map<String, Any?> {
        var current = this
        for (key in keys) current = current[key] as? Map<String, Any?> ?: error("Missing key '$key'")
        return current
    }

    @Test
    fun `adds the response a failure option declares, and leaves other operations alone`() {
        val result = OpenApiFailureAugmenter().augment(compileFixture(), minimalOpenApiYaml)
        val spec: Map<String, Any?> = Yaml().load(result)

        val getThingResponses = spec.at("paths", "/things", "get", "responses")
        assertEquals(setOf("200", "404"), getThingResponses.keys)
        val notFound = getThingResponses["404"] as Map<String, Any?>
        assertEquals("Thing not found.", notFound["description"])
        val ref = notFound.at("content", "application/problem+json", "schema")["\$ref"]
        assertEquals("#/components/schemas/ProblemDetail", ref)

        val listThingsResponses = spec.at("paths", "/things/list", "get", "responses")
        assertEquals(
            setOf("200"),
            listThingsResponses.keys,
            "ListThings has no (entur.http.v1.failure) option and should be untouched",
        )
    }

    @Test
    fun `derives the ref'd schema from the real ProblemDetail message's fields, comments and REQUIRED behavior`() {
        val result = OpenApiFailureAugmenter().augment(compileFixture(), minimalOpenApiYaml)
        val spec: Map<String, Any?> = Yaml().load(result)

        val schema = spec.at("components", "schemas").getValue("ProblemDetail") as Map<String, Any?>
        assertEquals("object", schema["type"])
        assertEquals(listOf("title", "status"), schema["required"])

        @Suppress("UNCHECKED_CAST")
        val properties = schema["properties"] as Map<String, Any?>
        assertEquals(setOf("title", "status", "detail"), properties.keys)
        val title = properties["title"] as Map<String, Any?>
        assertEquals("string", title["type"])
        assertEquals("A short, human-readable summary of the problem type.", title["description"])
        val status = properties["status"] as Map<String, Any?>
        assertEquals("integer", status["type"])
        assertEquals("int32", status["format"])
    }

    @Test
    fun `rejects an rpc that declares the same failure code more than once`() {
        val exception =
            assertFailsWith<IllegalStateException> {
                OpenApiFailureAugmenter().augment(compileFixture("mock/duplicate_service.proto"), minimalOpenApiYaml)
            }
        assertEquals(
            "Operation 'DuplicateFailureService_DeleteThing' declares (entur.http.v1.failure) code '404' more than once",
            exception.message,
        )
    }
}
