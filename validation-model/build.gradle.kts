description = "Domain model and API messages for the Entur validation platform."

dependencies {
    api(libs.bundles.protobuf)
    api(project(":http-model"))
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
    commandLine(miseExecutable("buf"), "generate", "validation-model/src/main/proto", "--template", "validation-model/buf.gen.yaml", "--clean")
}

sourceSets {
    main {
        java.srcDir("build/generated/source/proto/main/java")
        kotlin.srcDir("build/generated/source/proto/main/kotlin")
        // Ship the .proto sources themselves in the jar (at the same
        // entur/validation/v1/*.proto path they live at here), so consumers
        // that need the raw schema - e.g. another repo's buf workspace
        // importing this module's protos - can get it from this one
        // published artifact instead of a separate one.
        resources.srcDir("src/main/proto")
    }
}

tasks.named("compileJava") { dependsOn(bufGenerate) }
tasks.named("compileKotlin") { dependsOn(bufGenerate) }
