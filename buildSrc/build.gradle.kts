plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

// BufTools (buf/protoc from the PATH or mise, else fetched) lives in grpc-openapi-plugin, which is a
// separate build that buildSrc cannot depend on; compile that one file here too so miseExecutable()
// has the same fallback as the plugin's tasks.
val copyBufTools =
    tasks.register<Copy>("copyBufTools") {
        from(layout.projectDirectory.file("../grpc-openapi-plugin/src/main/kotlin/no/entur/http/BufTools.kt"))
        into(layout.buildDirectory.dir("generated/bufTools/no/entur/http"))
    }

kotlin.sourceSets.getByName("main").kotlin.srcDir(copyBufTools.map { layout.buildDirectory.dir("generated/bufTools") })
