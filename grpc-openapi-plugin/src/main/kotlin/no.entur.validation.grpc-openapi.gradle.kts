/**
 * Convention plugin for a gRPC module with `google.api.http` bindings: buf codegen plus the
 * augmented OpenAPI spec.
 *
 * The module also needs its `dependencies` (the protobuf libraries and, for the `entur.http.v1`
 * failure options its rpcs carry, `http-model`), a `buf.gen.yaml` with the gnostic plugin, and
 * `grpcOpenApiModule { specFileName = "<name>.yaml" }`. buf runs from [GrpcOpenApiModuleExtension.workspaceDir],
 * which holds `buf.yaml` and `buf.lock`.
 */

import no.entur.http.AugmentOpenApi
import no.entur.http.miseExecutable

plugins {
    `java-library`
}

interface GrpcOpenApiModuleExtension {
    // e.g. "kittum.yaml", committed as specs/kittum.yaml.
    val specFileName: Property<String>

    // The buf workspace: the directory with buf.yaml and buf.lock, and the directory buf runs in.
    // The module's own proto directory and buf.gen.yaml must be inside it. Defaults to the root project.
    val workspaceDir: DirectoryProperty

    // Where the generated spec is committed. Defaults to `specs` in the workspace.
    val specsDir: DirectoryProperty

    // The proto directories of the other workspace modules this module imports from (e.g. http-model).
    // Only used to re-run generation when one of them changes.
    val importProtoDirs: ConfigurableFileCollection
}

val grpcOpenApiModule = extensions.create<GrpcOpenApiModuleExtension>("grpcOpenApiModule")
grpcOpenApiModule.workspaceDir.convention(rootProject.layout.projectDirectory)
grpcOpenApiModule.specsDir.convention(grpcOpenApiModule.workspaceDir.dir("specs"))

fun File.relativeToWorkspace(): String =
    relativeTo(grpcOpenApiModule.workspaceDir.get().asFile).invariantSeparatorsPath

val bufGenerate =
    tasks.register<Exec>("bufGenerate") {
        description = "Generates Java/Kotlin message and service classes plus the OpenAPI YAML from src/main/proto via buf."
        group = "build"
        val workspace = grpcOpenApiModule.workspaceDir.get().asFile
        workingDir = workspace
        inputs.dir("src/main/proto")
        inputs.files(grpcOpenApiModule.importProtoDirs)
        inputs.files(File(workspace, "buf.yaml"), File(workspace, "buf.lock"), File(workspace, ".mise.toml"), "buf.gen.yaml")
        outputs.dir("build/generated/source/proto/main/java")
        outputs.dir("build/generated/source/proto/main/kotlin")
        outputs.dir("build/generated/openapi")
        commandLine(
            miseExecutable("buf"),
            "generate",
            projectDir.resolve("src/main/proto").relativeToWorkspace(),
            "--template",
            projectDir.resolve("buf.gen.yaml").relativeToWorkspace(),
            "--clean",
        )
    }

// The Kotlin plugin is applied by the build that applies this plugin, not here, so the typed `kotlin`
// accessor doesn't compile; reach the source set by name instead.
configure<SourceSetContainer> {
    named("main") {
        java.srcDir("build/generated/source/proto/main/java")
        resources.srcDir("src/main/proto")
        (this as ExtensionAware).extensions.getByName<SourceDirectorySet>("kotlin")
            .srcDir("build/generated/source/proto/main/kotlin")
    }
}

// An image of the whole workspace: ProblemDetail, which AugmentOpenApi adds to components.schemas, is
// referenced by no rpc and so never appears in this module's own buf image.
val bufBuildDescriptorSet =
    tasks.register<Exec>("bufBuildDescriptorSet") {
        description = "Builds a FileDescriptorSet of the whole workspace for AugmentOpenApi."
        group = "build"
        // A workspace may format its protos in one root-level task; this one needs the same ordering as bufGenerate.
        dependsOn(rootProject.tasks.matching { it.name == "bufFormat" })
        val workspace = grpcOpenApiModule.workspaceDir.get().asFile
        workingDir = workspace
        inputs.dir("src/main/proto")
        inputs.files(grpcOpenApiModule.importProtoDirs)
        inputs.files(File(workspace, "buf.yaml"), File(workspace, "buf.lock"), File(workspace, ".mise.toml"))
        // Not under build/generated/openapi: `buf generate --clean` wipes that directory.
        outputs.file(layout.buildDirectory.file("generated/descriptor-set/descriptor.binpb"))
        doFirst {
            layout.buildDirectory.dir("generated/descriptor-set").get().asFile.mkdirs()
        }
        commandLine(
            miseExecutable("buf"), "build", "-o", layout.buildDirectory.file("generated/descriptor-set/descriptor.binpb").get().asFile.path,
            "--as-file-descriptor-set",
        )
    }

// gnostic can't produce this on its own - see OpenApiFailureAugmenter/OpenApiExampleAugmenter.
val augmentOpenApi =
    tasks.register<AugmentOpenApi>("augmentOpenApi") {
        group = "build"
        dependsOn(bufGenerate, bufBuildDescriptorSet)
        descriptorSet = layout.buildDirectory.file("generated/descriptor-set/descriptor.binpb")
        openApiYaml = layout.buildDirectory.file("generated/openapi/openapi.yaml")
        outputYaml = layout.buildDirectory.file("generated/openapi/openapi-augmented.yaml")
        // Lets OpenApiExampleAugmenter resolve a components.schemas short name that also matches
        // another module's message (bufBuildDescriptorSet's image spans the whole workspace) to
        // this module's own message, instead of failing.
        val protoDir = projectDir.resolve("src/main/proto")
        ownProtoFiles =
            fileTree(protoDir) { include("**/*.proto") }
                .map { it.relativeTo(protoDir).invariantSeparatorsPath }
                .toSet()
    }

// Committed so a .proto change shows up in review as a diff of the HTTP contract.
val copyOpenApiSpec =
    tasks.register("copyOpenApiSpec") {
        description = "Copies the augmented OpenAPI spec over the committed specs/${grpcOpenApiModule.specFileName.orNull}."
        group = "documentation"
        dependsOn(augmentOpenApi)
        val source = layout.buildDirectory.file("generated/openapi/openapi-augmented.yaml")
        val target = grpcOpenApiModule.specsDir.file(grpcOpenApiModule.specFileName)
        inputs.file(source)
        outputs.file(target)
        doLast {
            target.get().asFile.parentFile.mkdirs()
            source.get().asFile.copyTo(target.get().asFile, overwrite = true)
        }
    }

// Run copyOpenApiSpec on every build, so specs/<specFileName> is always rewritten from the current .proto files.
tasks.named<ProcessResources>("processResources") {
    dependsOn(copyOpenApiSpec)
    from(grpcOpenApiModule.specsDir.file(grpcOpenApiModule.specFileName)) { into("openapi") }
}
tasks.named("compileJava") { dependsOn(bufGenerate) }
tasks.named("compileKotlin") { dependsOn(bufGenerate) }

tasks.named<Test>("test") {
    dependsOn(copyOpenApiSpec)
    // grpcOpenApiModule.specFileName isn't set yet at this point.
    doFirst {
        systemProperty(
            "openApiSpecFile",
            grpcOpenApiModule.specsDir.file(grpcOpenApiModule.specFileName).get().asFile.absolutePath,
        )
    }
}
