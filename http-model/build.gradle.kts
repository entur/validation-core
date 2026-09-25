description = "Shared entur.http.v1 types for HTTP-transcoded gRPC APIs across Entur's validation platform."

dependencies {
    api(libs.bundles.protobuf)
}

// `.proto` files under src/main/proto are the source of truth; `buf generate`
// turns them into generated Java/Kotlin message classes.
val bufGenerate = tasks.register<Exec>("bufGenerate") {
    description = "Generates Java/Kotlin message classes from src/main/proto via buf."
    group = "build"
    workingDir = rootProject.projectDir
    inputs.dir("src/main/proto")
    inputs.files(rootProject.file("buf.yaml"), rootProject.file("buf.lock"), rootProject.file(".mise.toml"), "buf.gen.yaml")
    outputs.dir("build/generated/source/proto/main/java")
    outputs.dir("build/generated/source/proto/main/kotlin")
    commandLine(miseExecutable("buf"), "generate", "http-model/src/main/proto", "--template", "http-model/buf.gen.yaml", "--clean")
}

// Download gnostic proto files to generate Java/Kotlin classes for these, as protoc always embeds a reference to the
// descriptor of any file whose custom options a .proto uses - entur.http.v1.ProblemDetail's own fields carry
// (gnostic.openapi.v3.property) for OpenApiExampleAugmenter to read (see buildSrc/src/main/kotlin/OpenApiExampleAugmenter.kt).
val gnosticCommit =
    rootProject.file("buf.lock").readText()
        .substringAfter("name: buf.build/gnostic/gnostic")
        .substringAfter("commit:")
        .substringBefore("\n")
        .trim()

val bufGenerateGnosticAnnotations = tasks.register<Exec>("bufGenerateGnosticAnnotations") {
    description = "Vendors Java/Kotlin classes for gnostic's openapiv3 proto options (buf.build/gnostic/gnostic)."
    group = "build"
    workingDir = rootProject.projectDir
    inputs.files(rootProject.file("buf.yaml"), rootProject.file("buf.lock"), rootProject.file(".mise.toml"), "buf.gen.gnostic.yaml")
    outputs.dir("build/generated/source/gnostic/main/java")
    outputs.dir("build/generated/source/gnostic/main/kotlin")
    // Pinned to buf.lock's resolved commit. Use "buf dep update" to update.
    commandLine(
        miseExecutable("buf"), "generate", "buf.build/gnostic/gnostic:$gnosticCommit",
        "--path", "gnostic/openapi/v3", "--template", "http-model/buf.gen.gnostic.yaml", "--clean",
    )
}

sourceSets {
    main {
        java.srcDir("build/generated/source/proto/main/java")
        kotlin.srcDir("build/generated/source/proto/main/kotlin")
        java.srcDir("build/generated/source/gnostic/main/java")
        kotlin.srcDir("build/generated/source/gnostic/main/kotlin")
        // Ship the .proto sources themselves in the jar, same as validation-model, so any other
        // buf workspace depending on this module's published artifact can import them directly.
        resources.srcDir("src/main/proto")
    }
}

tasks.named("compileJava") { dependsOn(bufGenerate, bufGenerateGnosticAnnotations) }
tasks.named("compileKotlin") { dependsOn(bufGenerate, bufGenerateGnosticAnnotations) }
