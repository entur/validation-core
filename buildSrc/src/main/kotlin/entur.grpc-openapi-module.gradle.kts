/**
 * Convention plugin for a gRPC module whose `.proto` files carry `google.api.http` bindings and
 * therefore publish an OpenAPI contract via gnostic. Bundles the whole `.proto` -> Java/Kotlin +
 * OpenAPI pipeline, including the AugmentOpenApi post-processing step.
 *
 * A module applying this plugin only needs to additionally:
 * - declare its own `dependencies { api(project(":validation-model")); api(libs.bundles.protobuf) }`
 * - set `grpcOpenApiModule { specFileName = "<name>.yaml" }`
 * - have a `<module>/buf.gen.yaml` templating the google-gnostic-openapi plugin, and its own
 *   `src/main/proto`
 */

import no.entur.http.AugmentOpenApi

plugins {
    `java-library`
}

interface GrpcOpenApiModuleExtension {
    // The name specs/<specFileName> is committed under, e.g. "kittum.yaml".
    val specFileName: Property<String>
}

val grpcOpenApiModule = extensions.create<GrpcOpenApiModuleExtension>("grpcOpenApiModule")

// Every module using this plugin has rpcs carrying (entur.http.v1.failure) options, which makes
// its compiled Java/Kotlin reference entur.http.v1's own generated classes when building its
// FileDescriptor at class-init time.
dependencies {
    add("api", project(":http-model"))
}

val moduleProtoPath = "${project.name}/src/main/proto"
val bufGenYaml = "${project.name}/buf.gen.yaml"

// Generates Java/Kotlin message and service classes plus the OpenAPI YAML specification
// from `.proto` files under src/main/proto.
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

// Doesn't apply the Kotlin Gradle plugin itself (root's subprojects{} already does) -
// so the Kotlin source set is reached generically by name rather than
// via its typed `kotlin` accessor, which needs that plugin declared in *this* file's own
// `plugins {}` block to be resolvable at buildSrc's compile time.
configure<SourceSetContainer> {
    named("main") {
        java.srcDir("build/generated/source/proto/main/java")
        resources.srcDir("src/main/proto")
        (this as ExtensionAware).extensions.getByName<SourceDirectorySet>("kotlin")
            .srcDir("build/generated/source/proto/main/kotlin")
    }
}

// A separate image of the *whole* workspace, since entur.http.v1.ProblemDetail - which AugmentOpenApi needs to turn
// into a components.schemas entry - lives in http-model and, being unreachable from any rpc's actual request/response
// type, is never pulled into this module's own buf image.
val bufBuildDescriptorSet =
    tasks.register<Exec>("bufBuildDescriptorSet") {
        description = "Builds a FileDescriptorSet of the whole workspace for AugmentOpenApi."
        group = "build"
        // Root's subprojects{} only wires bufFormat as a dependency of tasks literally named
        // "bufGenerate" - this task needs the same ordering guarantee since it builds its own image
        // of the same workspace.
        dependsOn(rootProject.tasks.named("bufFormat"))
        workingDir = rootProject.projectDir
        inputs.dir("src/main/proto")
        inputs.dir(rootProject.file("http-model/src/main/proto"))
        inputs.dir(rootProject.file("validation-model/src/main/proto"))
        inputs.files(rootProject.file("buf.yaml"), rootProject.file("buf.lock"), rootProject.file(".mise.toml"))
        // A separate directory from build/generated/openapi: `buf generate --clean` wipes gnostic's own output
        // directory before every run, which would delete this file too if it lived there.
        outputs.file(layout.buildDirectory.file("generated/descriptor-set/descriptor.binpb"))
        doFirst {
            layout.buildDirectory.dir("generated/descriptor-set").get().asFile.mkdirs()
        }
        commandLine(
            miseExecutable("buf"), "build", "-o", layout.buildDirectory.file("generated/descriptor-set/descriptor.binpb").get().asFile.path,
            "--as-file-descriptor-set",
        )
    }

// Runs the custom AugmentOpenApi task to add additional information from proto annotations into the generated
// OpenAPI YAML specification.
val augmentOpenApi =
    tasks.register<AugmentOpenApi>("augmentOpenApi") {
        group = "build"
        dependsOn(bufGenerate, bufBuildDescriptorSet)
        descriptorSet = layout.buildDirectory.file("generated/descriptor-set/descriptor.binpb")
        openApiYaml = layout.buildDirectory.file("generated/openapi/openapi.yaml")
        outputYaml = layout.buildDirectory.file("generated/openapi/openapi-augmented.yaml")
    }

// Copies the augmented OpenAPI YAML specification to ../specs/<specFileName>, a build artifact. It is committed so
// that a change to a .proto file shows up as a reviewable diff of the resulting public HTTP contract.
// (This might be relaxed in the future.)
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

// Run copyOpenApiSpec on every build, so specs/<specFileName> is always rewritten from the current .proto files.
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
