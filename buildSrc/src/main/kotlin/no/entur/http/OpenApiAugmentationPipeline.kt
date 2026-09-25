package no.entur.http

import com.google.protobuf.DescriptorProtos

/**
 * Runs OpenAPI post-processing augmenters over one shared parse of a gnostic-generated openapi.yaml, in order. Each
 * augmenter only mutates the parsed spec in place; parsing the YAML text and dumping it back out happens exactly once
 * here, rather than once per augmenter.
 */
class OpenApiAugmentationPipeline {
    fun augment(
        fileDescriptorSet: DescriptorProtos.FileDescriptorSet,
        openApiYaml: String,
    ): String {
        val files = buildFileDescriptors(fileDescriptorSet)
        val registry = buildExtensionRegistry(files)

        val yaml = newOpenApiYaml()
        val spec: MutableMap<String, Any?> = yaml.load(openApiYaml)

        OpenApiFailureAugmenter().augment(files, registry, spec)
        OpenApiExampleAugmenter().augment(files, registry, spec)

        return buildString {
            append("# Generated with protoc-gen-openapi and OpenApiAugmentationPipeline\n")
            append("# https://github.com/google/gnostic/tree/master/cmd/protoc-gen-openapi\n")
            append("# https://github.com/entur/validation-core/tree/master/buildSrc/src/main/kotlin/no/entur/http/OpenApiAugmentationPipeline.kt\n\n")
            append(yaml.dump(spec))
        }
    }
}
