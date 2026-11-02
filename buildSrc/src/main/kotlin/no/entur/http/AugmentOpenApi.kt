package no.entur.http

import com.google.protobuf.DescriptorProtos
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.FileInputStream

/** Thin Gradle wrapper, so [OpenApiAugmentationPipeline] stays testable without Gradle. */
abstract class AugmentOpenApi : DefaultTask() {
    @get:InputFile
    abstract val descriptorSet: RegularFileProperty

    @get:InputFile
    abstract val openApiYaml: RegularFileProperty

    @get:OutputFile
    abstract val outputYaml: RegularFileProperty

    @TaskAction
    fun run() {
        val fileDescriptorSet =
            FileInputStream(descriptorSet.get().asFile).use { DescriptorProtos.FileDescriptorSet.parseFrom(it) }
        val augmented = OpenApiAugmentationPipeline().augment(fileDescriptorSet, openApiYaml.get().asFile.readText())
        outputYaml.get().asFile.writeText(augmented)
    }
}
