package no.entur.http

import com.google.protobuf.DescriptorProtos
import no.entur.http.augmenters.OpenApiExampleAugmenter
import no.entur.http.augmenters.OpenApiFailureAugmenter
import no.entur.http.augmenters.OpenApiMetaPathAugmenter

/** Runs the augmenters in order over one parse of gnostic's openapi.yaml; each mutates it in place. */
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
        OpenApiMetaPathAugmenter().augment(spec)

        return buildString {
            append("# Generated with protoc-gen-openapi and OpenApiAugmentationPipeline\n")
            append("# https://github.com/google/gnostic/tree/master/cmd/protoc-gen-openapi\n")
            append("# https://github.com/entur/validation-core/tree/master/buildSrc/src/main/kotlin/no/entur/http/OpenApiAugmentationPipeline.kt\n\n")
            append(yaml.dump(spec))
        }
    }
}
