import com.google.protobuf.DescriptorProtos
import no.entur.http.OpenApiExampleAugmenter
import no.entur.http.buildExtensionRegistry
import no.entur.http.buildFileDescriptors
import no.entur.http.newOpenApiYaml
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Exercises OpenApiExampleAugmenter against a small fixture message tree
 * (buildSrc/src/test/resources/fixture/mock/example_messages.proto) and a minimal stand-in for
 * gnostic's own annotations.proto (buildSrc/src/test/resources/fixture/gnostic/openapi/v3/) -
 * the augmenter only ever navigates the `property`/`example`/`yaml` fields by name, so a
 * hand-rolled stand-in exercises the same code path as the real gnostic schema without a
 * network/BSR dependency in this fixture, same as OpenApiFailureAugmenterTest's stand-in for
 * google/api/field_behavior.proto.
 */
class OpenApiExampleAugmenterTest {
    private val fixtureDir = File(System.getProperty("fixtureDir") ?: error("fixtureDir system property not set"))

    // Mirrors what OpenApiFailureAugmenter itself hands off for a schema it synthesizes from a
    // descriptor rather than gnostic's own codegen: properties with no "example" of their own yet.
    private val openApiYaml =
        """
        openapi: 3.0.3
        info:
            title: mock
            version: 0.0.1
        paths: {}
        components:
            schemas:
                Leaf:
                    type: object
                    properties:
                        name:
                            type: string
                        unannotated:
                            type: string
                Wrapper:
                    type: object
                    properties:
                        leaf:
                            allOf:
                              - ${'$'}ref: '#/components/schemas/Leaf'
                        leaves:
                            type: array
                            items:
                                ${'$'}ref: '#/components/schemas/Leaf'
                CycleA:
                    type: object
                    properties:
                        b:
                            allOf:
                              - ${'$'}ref: '#/components/schemas/CycleB'
                CycleB:
                    type: object
                    properties:
                        a:
                            allOf:
                              - ${'$'}ref: '#/components/schemas/CycleA'
                AlreadyHasExample:
                    type: object
                    example:
                        foo: bar
                    properties:
                        foo:
                            type: string
                NoProperties:
                    type: object
        """.trimIndent()

    private fun compileFixture(): DescriptorProtos.FileDescriptorSet {
        val descriptorSetFile = File.createTempFile("fixture", ".binpb").apply { deleteOnExit() }
        val protoc = File(System.getProperty("user.home"), ".local/share/mise/shims/protoc")
            .let { if (it.canExecute()) it.absolutePath else "protoc" }

        val process =
            ProcessBuilder(
                protoc,
                "-I", fixtureDir.absolutePath,
                "--include_source_info",
                "--include_imports",
                "--descriptor_set_out=${descriptorSetFile.absolutePath}",
                "mock/example_messages.proto",
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

    private fun result(): Map<String, Any?> {
        val files = buildFileDescriptors(compileFixture())
        val registry = buildExtensionRegistry(files)
        val spec: MutableMap<String, Any?> = newOpenApiYaml().load(openApiYaml)
        OpenApiExampleAugmenter().augment(files, registry, spec)
        return spec
    }

    @Test
    fun `fills in a field's own example from its (gnostic-openapi-v3-property) option, leaving an unannotated field alone`() {
        val nameProperty = result().at("components", "schemas", "Leaf", "properties", "name")
        assertEquals("leaf", nameProperty["example"])

        val unannotatedProperty = result().at("components", "schemas", "Leaf", "properties", "unannotated")
        assertFalse("example" in unannotatedProperty, "unannotated has no (gnostic.openapi.v3.property) option and should be left alone")
    }

    @Test
    fun `builds a whole-object example for a schema from its own fields' examples`() {
        val leaf = result().at("components", "schemas", "Leaf")
        assertEquals(mapOf("name" to "leaf"), leaf["example"])
    }

    @Test
    fun `recurses into a singular (allOf-ref) and repeated (array-ref) message-typed field's own example`() {
        val wrapper = result().at("components", "schemas", "Wrapper")
        assertEquals(
            mapOf("leaf" to mapOf("name" to "leaf"), "leaves" to listOf(mapOf("name" to "leaf"))),
            wrapper["example"],
        )
    }

    @Test
    fun `breaks a cycle between two schemas instead of recursing forever, omitting the cyclic field from both`() {
        val spec = result()
        val cycleA = spec.at("components", "schemas", "CycleA")
        val cycleB = spec.at("components", "schemas", "CycleB")
        assertFalse("example" in cycleA, "CycleA has no example left once its only field (a cycle back to itself) is omitted")
        assertFalse("example" in cycleB, "CycleB has no example left once its only field (a cycle back to itself) is omitted")
    }

    @Test
    fun `leaves a schema that already has its own example untouched`() {
        val alreadyHasExample = result().at("components", "schemas", "AlreadyHasExample")
        assertEquals(mapOf("foo" to "bar"), alreadyHasExample["example"])
    }

    @Test
    fun `adds no example to a schema with no properties to build one from`() {
        val noProperties = result().at("components", "schemas", "NoProperties")
        assertFalse("example" in noProperties)
    }
}
