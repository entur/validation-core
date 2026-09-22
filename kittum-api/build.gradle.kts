description = "Kittum's gRPC/REST service definitions (entur.kittum.v1) and their Request/Response messages, built on the shared validation-model domain messages."

dependencies {
    api(project(":validation-model"))
    api(libs.bundles.protobuf)
}

// `.proto` files under src/main/proto are the source of truth; `buf generate`
// turns them into generated Java/Kotlin message classes.
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
    commandLine(miseExecutable("buf"), "generate", "kittum-api/src/main/proto", "--template", "kittum-api/buf.gen.yaml", "--clean")
}

sourceSets {
    main {
        java.srcDir("build/generated/source/proto/main/java")
        kotlin.srcDir("build/generated/source/proto/main/kotlin")
        resources.srcDir("src/main/proto")
    }
}

// ../specs/kittum.yaml is a copied build artifact. It is committed so that a change
// to a .proto file shows up as a reviewable diff of the resulting public HTTP contract.
// The repo-root specs/ directory is Entur's standard location for OpenAPI specs.
val copyOpenApiSpec = tasks.register<Copy>("copyOpenApiSpec") {
    description = "Copies the buf-generated OpenAPI spec over the committed specs/kittum.yaml."
    group = "documentation"
    dependsOn(bufGenerate)
    from(layout.buildDirectory.file("generated/openapi/openapi.yaml"))
    into(rootProject.layout.projectDirectory.dir("specs"))
    rename { "kittum.yaml" }
}

// Run copyOpenApiSpec on every build, so specs/kittum.yaml is always rewritten
// from the current .proto files.
tasks.named<ProcessResources>("processResources") {
    dependsOn(copyOpenApiSpec)
    from(rootProject.file("specs/kittum.yaml")) { into("openapi") }
}
tasks.named("compileJava") { dependsOn(bufGenerate) }
tasks.named("compileKotlin") { dependsOn(bufGenerate) }
