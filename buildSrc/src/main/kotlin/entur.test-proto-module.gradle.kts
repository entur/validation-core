/**
 * Convention plugin for a module whose only `.proto` file(s) are test fixtures. Generates them via buf and lists them
 * in the root buf.yaml workspace, but with a pared-down override (`lint.use: [STANDARD]`, `breaking.use: []`).
 * Applied independently by each module that needs its own test-fixtures proto.
 *
 * A module applying this plugin only needs:
 * - its `.proto` file(s) under `<module>/src/test/proto`, laid out to match their package
 * - a `<module>/buf.gen.yaml` templating protoc_builtin java/kotlin into
 *   `<module>/build/generated/source/proto/test/{java,kotlin}`
 * - a `proto-utils/src/test/proto`-shaped entry under buf.yaml's `modules`, with its own pared-down
 *   `lint`/`breaking` override
 */

plugins {
    `java-library`
}

val moduleTestProtoPath = "${project.name}/src/test/proto"
val bufGenYaml = "${project.name}/buf.gen.yaml"

// Generates Java/Kotlin message classes from `.proto` files under src/test/proto.
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

// Doesn't apply the Kotlin Gradle plugin itself (root's subprojects{} already does) -
// so the Kotlin source set is reached generically by name rather than
// via its typed `kotlin` accessor, which needs that plugin declared in *this* file's own
// `plugins {}` block to be resolvable at buildSrc's compile time.
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
// includes bufGenerate's own output directories.
tasks.matching { it.name == "runKtlintCheckOverTestSourceSet" }.configureEach { dependsOn(bufGenerate) }
