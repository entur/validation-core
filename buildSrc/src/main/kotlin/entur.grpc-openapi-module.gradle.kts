/**
 * Convention plugin for a gRPC module with `google.api.http` bindings: buf codegen plus the
 * augmented OpenAPI spec. The module also needs its `dependencies`, a `buf.gen.yaml` with the
 * gnostic plugin, and `grpcOpenApiModule { specFileName = "<name>.yaml" }`.
 */

import no.entur.http.AugmentOpenApi

plugins {
    `java-library`
}

interface GrpcOpenApiModuleExtension {
    // e.g. "kittum.yaml", committed as specs/kittum.yaml.
    val specFileName: Property<String>
}

val grpcOpenApiModule = extensions.create<GrpcOpenApiModuleExtension>("grpcOpenApiModule")

// The rpcs' (entur.http.v1.failure) options make the generated FileDescriptor load http-model classes.
dependencies {
    add("api", project(":http-model"))
}

val moduleProtoPath = "${project.name}/src/main/proto"
val bufGenYaml = "${project.name}/buf.gen.yaml"

val bufGenerate =
    tasks.register<Exec>("bufGenerate") {
        description = "Generates Java/Kotlin message and service classes plus the OpenAPI YAML from src/main/proto via buf."
        group = "build"
        workingDir = rootProject.projectDir
        inputs.dir("src/main/proto")
        inputs.dir(rootProject.file("http-model/src/main/proto"))
        inputs.dir(rootProject.file("validation-model/src/main/proto"))
        inputs.files(rootProject.file("buf.yaml"), rootProject.file("buf.lock"), rootProject.file(".mise.toml"), "buf.gen.yaml")
        outputs.dir("build/generated/source/proto/main/java")
        outputs.dir("build/generated/source/proto/main/kotlin")
        outputs.dir("build/generated/openapi")
        commandLine(miseExecutable("buf"), "generate", moduleProtoPath, "--template", bufGenYaml, "--clean")
    }

// The Kotlin plugin is applied by root's subprojects{}, not here, so the typed `kotlin` accessor
// doesn't compile in buildSrc; reach the source set by name instead.
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
        // Root only wires bufFormat before tasks named "bufGenerate"; this one needs the same ordering.
        dependsOn(rootProject.tasks.named("bufFormat"))
        workingDir = rootProject.projectDir
        inputs.dir("src/main/proto")
        inputs.dir(rootProject.file("http-model/src/main/proto"))
        inputs.dir(rootProject.file("validation-model/src/main/proto"))
        inputs.files(rootProject.file("buf.yaml"), rootProject.file("buf.lock"), rootProject.file(".mise.toml"))
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
    }

// Committed so a .proto change shows up in review as a diff of the HTTP contract.
val copyOpenApiSpec =
    tasks.register("copyOpenApiSpec") {
        description = "Copies the augmented OpenAPI spec over the committed specs/${grpcOpenApiModule.specFileName.orNull}."
        group = "documentation"
        dependsOn(augmentOpenApi)
        val source = layout.buildDirectory.file("generated/openapi/openapi-augmented.yaml")
        val target = grpcOpenApiModule.specFileName.map { rootProject.layout.projectDirectory.file("specs/$it") }
        inputs.file(source)
        outputs.file(target)
        doLast {
            target.get().asFile.parentFile.mkdirs()
            source.get().asFile.copyTo(target.get().asFile, overwrite = true)
        }
    }

tasks.named<ProcessResources>("processResources") {
    dependsOn(copyOpenApiSpec)
    from(rootProject.layout.projectDirectory.dir("specs").file(grpcOpenApiModule.specFileName)) { into("openapi") }
}
tasks.named("compileJava") { dependsOn(bufGenerate) }
tasks.named("compileKotlin") { dependsOn(bufGenerate) }

tasks.named<Test>("test") {
    dependsOn(copyOpenApiSpec)
    // grpcOpenApiModule.specFileName isn't set yet at this point.
    doFirst {
        systemProperty(
            "openApiSpecFile",
            rootProject.layout.projectDirectory.dir("specs").file(grpcOpenApiModule.specFileName).get().asFile.absolutePath,
        )
    }
}
