description = "Kittum's gRPC/REST service definitions (entur.kittum.v1) and their Request/Response messages, built on the shared validation-model domain messages."

dependencies {
    api(project(":validation-model"))
    api(libs.bundles.protobuf)
}

// See ../validation-model/build.gradle.kts for why buf/protoc are invoked
// this way, and ../buf.yaml for why this module's protos share one workspace with it.
val bufGenerate = tasks.register<Exec>("bufGenerate") {
    description = "Generates Java/Kotlin message and service classes plus the OpenAPI YAML from src/main/proto via buf."
    group = "build"
    workingDir = rootProject.projectDir
    inputs.dir("src/main/proto")
    inputs.dir(rootProject.file("validation-model/src/main/proto"))
    inputs.files(rootProject.file("buf.yaml"), rootProject.file("buf.lock"), rootProject.file(".mise.toml"), "buf.gen.yaml")
    outputs.dir("build/generated/source/proto/main/java")
    outputs.dir("build/generated/source/proto/main/kotlin")
    outputs.dir("build/generated/openapi")
    // `buf` and `protoc` are managed by mise (../.mise.toml) via shims in
    // ~/.local/share/mise/shims.
    val miseShims = File(System.getProperty("user.home"), ".local/share/mise/shims")
    val bufExecutable = File(miseShims, "buf").let { if (it.canExecute()) it.absolutePath else "buf" }
    environment(
        "PATH",
        "$miseShims${System.getProperty("path.separator")}${System.getenv("PATH")}",
    )
    commandLine(bufExecutable, "generate", "kittum-api/src/main/proto", "--template", "kittum-api/buf.gen.yaml", "--clean")
}

sourceSets {
    main {
        java.srcDir("build/generated/source/proto/main/java")
        kotlin.srcDir("build/generated/source/proto/main/kotlin")
        resources.srcDir("src/main/proto")
    }
}

// ../specs/kittum.yaml is a copied build artifact, not hand-maintained - it is
// committed so that a change to a .proto file shows up as a reviewable diff of
// the resulting public HTTP contract. The repo-root specs/ directory is Entur's
// standard location for OpenAPI specs (CONVENTIONS.md in entur/ai), and where
// the shared gha-api lint workflow looks for them.
val copyOpenApiSpec = tasks.register<Copy>("copyOpenApiSpec") {
    description = "Copies the buf-generated OpenAPI spec over the committed specs/kittum.yaml."
    group = "documentation"
    dependsOn(bufGenerate)
    from(layout.buildDirectory.file("generated/openapi/openapi.yaml"))
    into(rootProject.layout.projectDirectory.dir("specs"))
    rename { "kittum.yaml" }
}

// Run copyOpenApiSpec on every build, so specs/kittum.yaml is always rewritten
// from the current .proto files. The spec also ships in this module's jar at
// openapi/kittum.yaml, next to the .proto sources, so a consumer gets the
// schema and the HTTP contract from the one published artifact - named
// explicitly rather than taking all of specs/, which will hold other modules'
// specs as more APIs are added.
tasks.named<ProcessResources>("processResources") {
    dependsOn(copyOpenApiSpec)
    from(rootProject.file("specs/kittum.yaml")) { into("openapi") }
}
tasks.named("compileJava") { dependsOn(bufGenerate) }
tasks.named("compileKotlin") { dependsOn(bufGenerate) }
