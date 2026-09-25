import com.google.protobuf.DescriptorProtos
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.FileInputStream

/**
 * Gradle wrapper around [OpenApiFailureAugmenter] - see its doc comment for what this actually
 * does and why. Kept as thin file-in/file-out plumbing so the real logic stays testable without
 * any Gradle API involved.
 */
abstract class AugmentOpenApiWithFailures : DefaultTask() {
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
        val augmented = OpenApiFailureAugmenter().augment(fileDescriptorSet, openApiYaml.get().asFile.readText())
        outputYaml.get().asFile.writeText(augmented)
    }
}
