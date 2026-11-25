description = "Shared entur.http.v1 types for HTTP-transcoded gRPC APIs across Entur's validation platform."

dependencies {
    api(libs.bundles.protobuf)
}

val bufGenerate = tasks.register<Exec>("bufGenerate") {
    description = "Generates Java/Kotlin message classes from src/main/proto via buf."
    group = "build"
    workingDir = rootProject.projectDir
    inputs.dir("src/main/proto")
    inputs.files(rootProject.file("buf.yaml"), rootProject.file(".mise.toml"), "buf.gen.yaml")
    outputs.dir("build/generated/source/proto/main/java")
    outputs.dir("build/generated/source/proto/main/kotlin")
    commandLine(miseExecutable("buf"), "generate", "http-model/src/main/proto", "--template", "http-model/buf.gen.yaml", "--clean")
}

// ProblemDetail's fields use (gnostic.openapi.v3.property), and the generated code references the
// descriptor of every file whose options it uses, so gnostic's annotations need Java/Kotlin classes too.
val bufGenerateGnosticAnnotations = tasks.register<Exec>("bufGenerateGnosticAnnotations") {
    description = "Generates Java/Kotlin classes for gnostic's openapiv3 proto options from third_party/proto."
    group = "build"
    workingDir = rootProject.projectDir
    inputs.dir(rootProject.file("third_party/proto/gnostic"))
    inputs.files(rootProject.file("buf.yaml"), rootProject.file(".mise.toml"), "buf.gen.gnostic.yaml")
    outputs.dir("build/generated/source/gnostic/main/java")
    outputs.dir("build/generated/source/gnostic/main/kotlin")
    commandLine(
        miseExecutable("buf"), "generate", "third_party/proto",
        "--path", "third_party/proto/gnostic/openapi/v3", "--template", "http-model/buf.gen.gnostic.yaml", "--clean",
    )
}

sourceSets {
    main {
        java.srcDir("build/generated/source/proto/main/java")
        kotlin.srcDir("build/generated/source/proto/main/kotlin")
        java.srcDir("build/generated/source/gnostic/main/java")
        kotlin.srcDir("build/generated/source/gnostic/main/kotlin")
        // Ship the .proto sources in the jar so other buf workspaces can import them.
        resources.srcDir("src/main/proto")
    }
}

// Also ship the vendored googleapis/gnostic protos ours import, with their licenses, so other buf
// workspaces don't need buf.build either (see third_party/proto/README.md).
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("third_party/proto")) { include("**/*.proto", "**/LICENSE") }
}

tasks.named("compileJava") { dependsOn(bufGenerate, bufGenerateGnosticAnnotations) }
tasks.named("compileKotlin") { dependsOn(bufGenerate, bufGenerateGnosticAnnotations) }
