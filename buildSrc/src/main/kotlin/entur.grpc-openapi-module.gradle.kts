/**
 * Convention plugin for a gRPC module whose `.proto` files carry `google.api.http` bindings and
 * therefore publish an OpenAPI contract via gnostic. Bundles the whole `.proto` -> Java/Kotlin +
 * OpenAPI pipeline, including the AugmentOpenApi post-processing step (see
 * OpenApiAugmentationPipeline.kt) that splices in the responses an rpc's `(entur.http.v1.failure)`
 * method options describe, the entur.http.v1.ProblemDetail schema they `$ref`, and a whole-object
 * `example` for every schema.
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

// `.proto` files under src/main/proto are the source of truth; `buf generate`
// turns them into generated Java/Kotlin message classes plus the OpenAPI YAML.
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

// Doesn't apply the Kotlin Gradle plugin itself (root's subprojects{} already does, for every
// module this applies to) - so the Kotlin source set is reached generically by name rather than
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

// A separate image of the *whole* workspace (not just this module's own src/main/proto), since
// entur.http.v1.ProblemDetail - which AugmentOpenApi needs to turn into a components.schemas
// entry - lives in http-model and, being unreachable from any rpc's actual request/response type,
// is never pulled into this module's own buf image.
val bufBuildDescriptorSet =
    tasks.register<Exec>("bufBuildDescriptorSet") {
        description = "Builds a FileDescriptorSet of the whole workspace for AugmentOpenApi."
        group = "build"
        // Root's subprojects{} only wires bufFormat as a dependency of tasks literally named
        // "bufGenerate" - this task needs the same ordering guarantee (read formatted .proto
        // files, not ones buf format -w is concurrently rewriting) since it builds its own image
        // of the same workspace.
        dependsOn(rootProject.tasks.named("bufFormat"))
        workingDir = rootProject.projectDir
        inputs.dir("src/main/proto")
        inputs.dir(rootProject.file("http-model/src/main/proto"))
        inputs.dir(rootProject.file("validation-model/src/main/proto"))
        inputs.files(rootProject.file("buf.yaml"), rootProject.file("buf.lock"), rootProject.file(".mise.toml"))
        // A separate directory from build/generated/openapi: `buf generate --clean` (bufGenerate,
        // above) wipes gnostic's own output directory before every run, which would delete this
        // file too if it lived there.
        outputs.file(layout.buildDirectory.file("generated/descriptor-set/descriptor.binpb"))
        doFirst {
            layout.buildDirectory.dir("generated/descriptor-set").get().asFile.mkdirs()
        }
        commandLine(
            miseExecutable("buf"), "build", "-o", layout.buildDirectory.file("generated/descriptor-set/descriptor.binpb").get().asFile.path,
            "--as-file-descriptor-set",
        )
    }

// gnostic has no way to turn an rpc's terse `(entur.http.v1.failure)` method options into the
// OpenAPI responses they describe, entur.http.v1.ProblemDetail - the message those responses $ref
// - into a components.schemas entry, or a whole-object `example` for a schema (as opposed to the
// per-field ones it already copies from `(gnostic.openapi.v3.property).example`) - see
// OpenApiAugmentationPipeline.kt for why. This splices all of that in as a post-processing step
// over gnostic's own output.
val augmentOpenApi =
    tasks.register<AugmentOpenApi>("augmentOpenApi") {
        group = "build"
        dependsOn(bufGenerate, bufBuildDescriptorSet)
        descriptorSet = layout.buildDirectory.file("generated/descriptor-set/descriptor.binpb")
        openApiYaml = layout.buildDirectory.file("generated/openapi/openapi.yaml")
        outputYaml = layout.buildDirectory.file("generated/openapi/openapi-augmented.yaml")
    }

// ../specs/<specFileName> is a copied build artifact. It is committed so that a change to a
// .proto file shows up as a reviewable diff of the resulting public HTTP contract. The repo-root
// specs/ directory is Entur's standard location for OpenAPI specs.
val copyOpenApiSpec =
    tasks.register<Copy>("copyOpenApiSpec") {
        description = "Copies the augmented OpenAPI spec over the committed specs/${grpcOpenApiModule.specFileName.orNull}."
        group = "documentation"
        dependsOn(augmentOpenApi)
        from(layout.buildDirectory.file("generated/openapi/openapi-augmented.yaml"))
        into(rootProject.layout.projectDirectory.dir("specs"))
        rename { grpcOpenApiModule.specFileName.get() }
    }

// Run copyOpenApiSpec on every build, so specs/<specFileName> is always rewritten
// from the current .proto files.
tasks.named<ProcessResources>("processResources") {
    dependsOn(copyOpenApiSpec)
    from(rootProject.layout.projectDirectory.dir("specs").file(grpcOpenApiModule.specFileName)) { into("openapi") }
}
tasks.named("compileJava") { dependsOn(bufGenerate) }
tasks.named("compileKotlin") { dependsOn(bufGenerate) }

tasks.named<Test>("test") {
    dependsOn(copyOpenApiSpec)
    // grpcOpenApiModule.specFileName isn't set yet at this point - this configuration action
    // runs as this plugin is applied, before the module's own build.gradle.kts (which sets it)
    // has run - so it can only be read lazily, once the task actually executes.
    doFirst {
        systemProperty(
            "openApiSpecFile",
            rootProject.layout.projectDirectory.dir("specs").file(grpcOpenApiModule.specFileName).get().asFile.absolutePath,
        )
    }
}
