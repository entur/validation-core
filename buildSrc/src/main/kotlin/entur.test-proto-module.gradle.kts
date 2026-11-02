/**
 * Convention plugin for a module whose only `.proto` files are test fixtures under `src/test/proto`.
 * The module also needs a `buf.gen.yaml` and an entry in root `buf.yaml` with the pared-down
 * `lint.use: [STANDARD]`, `breaking.use: []` override.
 */

plugins {
    `java-library`
}

val moduleTestProtoPath = "${project.name}/src/test/proto"
val bufGenYaml = "${project.name}/buf.gen.yaml"

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

// Kotlin source set reached by name - see entur.grpc-openapi-module.gradle.kts.
configure<SourceSetContainer> {
    named("test") {
        java.srcDir("build/generated/source/proto/test/java")
        (this as ExtensionAware).extensions.getByName<SourceDirectorySet>("kotlin")
            .srcDir("build/generated/source/proto/test/kotlin")
    }
}

tasks.named("compileTestJava") { dependsOn(bufGenerate) }
tasks.named("compileTestKotlin") { dependsOn(bufGenerate) }
// ktlint scans the test source set, which includes bufGenerate's output.
tasks.matching { it.name == "runKtlintCheckOverTestSourceSet" }.configureEach { dependsOn(bufGenerate) }
