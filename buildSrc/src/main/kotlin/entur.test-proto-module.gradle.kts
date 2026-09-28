/**
 * Convention plugin for a module whose only `.proto` file(s) are test fixtures, not part of its
 * published API. Generates them via buf - the same `protoc_builtin` mechanism every other module
 * uses - but deliberately keeps them out of the root buf.yaml workspace: pulling test-only
 * messages into the shared workspace would subject them to buf lint's COMMENTS rules and buf
 * breaking's wire-compatibility checks, both of which exist to protect published contracts, not
 * throwaway fixtures.
 *
 * A module applying this plugin only needs:
 * - its `.proto` file(s) under `<module>/src/test/proto`, laid out to match their package (buf's
 *   PACKAGE_DIRECTORY_MATCH rule still applies to any module buf.yaml lists, even with a pared-down
 *   `lint.use`)
 * - a `<module>/buf.gen.yaml` templating protoc_builtin java/kotlin into
 *   `<module>/build/generated/source/proto/test/{java,kotlin}`
 * - a `proto-utils/src/test/proto`-shaped entry under buf.yaml's `modules`, with its own pared-down
 *   `lint`/`breaking` override (see proto-utils/build.gradle.kts's own comment for why it can't be
 *   left out of the workspace entirely - PACKAGE_DIRECTORY_MATCH and buf's dependency resolution
 *   for e.g. `google.api.field_behavior` both need it to be a workspace module)
 */

plugins {
    `java-library`
}

val moduleTestProtoPath = "${project.name}/src/test/proto"
val bufGenYaml = "${project.name}/buf.gen.yaml"

// `.proto` files under src/test/proto are the source of truth; `buf generate`
// turns them into generated Java/Kotlin message classes, same as every other module's own
// bufGenerate task - just scoped to the test source set instead of main.
val bufGenerate =
    tasks.register<Exec>("bufGenerate") {
        description = "Generates Java/Kotlin message classes for the test-fixtures proto from src/test/proto via buf."
        group = "build"
        workingDir = rootProject.projectDir
        inputs.dir("src/test/proto")
        inputs.files(rootProject.file("buf.yaml"), rootProject.file("buf.lock"), rootProject.file(".mise.toml"), bufGenYaml)
        outputs.dir("build/generated/source/proto/test/java")
        outputs.dir("build/generated/source/proto/test/kotlin")
        commandLine(miseExecutable("buf"), "generate", moduleTestProtoPath, "--template", bufGenYaml, "--clean")
    }

// Doesn't apply the Kotlin Gradle plugin itself (root's subprojects{} already does, for every
// module this applies to) - so the Kotlin source set is reached generically by name rather than
// via its typed `kotlin` accessor, which needs that plugin declared in *this* file's own
// `plugins {}` block to be resolvable at buildSrc's compile time. Same reasoning as
// entur.grpc-openapi-module.gradle.kts.
configure<SourceSetContainer> {
    named("test") {
        java.srcDir("build/generated/source/proto/test/java")
        (this as ExtensionAware).extensions.getByName<SourceDirectorySet>("kotlin")
            .srcDir("build/generated/source/proto/test/kotlin")
    }
}

tasks.named("compileTestJava") { dependsOn(bufGenerate) }
tasks.named("compileTestKotlin") { dependsOn(bufGenerate) }
// ktlint (where the consuming module applies it) also scans the test source set, which now
// includes bufGenerate's own output directories - it needs the same task-ordering dependency.
tasks.matching { it.name == "runKtlintCheckOverTestSourceSet" }.configureEach { dependsOn(bufGenerate) }
