description = "Domain model and API messages for the Entur validation platform."

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
    // `buf` and `protoc` are managed by mise (../.mise.toml) via shims in
    // ~/.local/share/mise/shims.
    val miseShims = File(System.getProperty("user.home"), ".local/share/mise/shims")
    val bufExecutable = File(miseShims, "buf").let { if (it.canExecute()) it.absolutePath else "buf" }
    environment(
        "PATH",
        "$miseShims${System.getProperty("path.separator")}${System.getenv("PATH")}",
    )
    commandLine(bufExecutable, "generate", "validation-model/src/main/proto", "--template", "validation-model/buf.gen.yaml", "--clean")
}

// Download gnostic proto files to generate Java/Kotlin classes for these, as protoc always embeds a reference to the
// descriptor of any file whose custom options a .proto uses.
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
    val miseShims = File(System.getProperty("user.home"), ".local/share/mise/shims")
    val bufExecutable = File(miseShims, "buf").let { if (it.canExecute()) it.absolutePath else "buf" }
    environment(
        "PATH",
        "$miseShims${System.getProperty("path.separator")}${System.getenv("PATH")}",
    )
    // Pinned to buf.lock's resolved commit. Use "buf dep update" to update.
    commandLine(
        bufExecutable, "generate", "buf.build/gnostic/gnostic:$gnosticCommit",
        "--path", "gnostic/openapi/v3", "--template", "validation-model/buf.gen.gnostic.yaml", "--clean",
    )
}

sourceSets {
    main {
        java.srcDir("build/generated/source/proto/main/java")
        kotlin.srcDir("build/generated/source/proto/main/kotlin")
        java.srcDir("build/generated/source/gnostic/main/java")
        kotlin.srcDir("build/generated/source/gnostic/main/kotlin")
        // Ship the .proto sources themselves in the jar (at the same
        // entur/validation/v1/*.proto path they live at here), so consumers
        // that need the raw schema - e.g. another repo's buf workspace
        // importing this module's protos - can get it from this one
        // published artifact instead of a separate one.
        resources.srcDir("src/main/proto")
    }
}

tasks.named("compileJava") { dependsOn(bufGenerate, bufGenerateGnosticAnnotations) }
tasks.named("compileKotlin") { dependsOn(bufGenerate, bufGenerateGnosticAnnotations) }
