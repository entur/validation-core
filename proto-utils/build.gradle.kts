description = "Domain-agnostic proto plumbing (update masks, oneof serialization, descriptor " +
    "reflection) shared by validation-platform services."

plugins {
    alias(libs.plugins.ktlint)
    id("entur.test-proto-module")
}

dependencies {
    // BOMs as `implementation`, so they don't leak into tool configurations like ktlint.
    implementation(platform(libs.kotlin.bom))

    // Protobuf types appear in this module's public API (e.g. UpdateMaskSupport.parse(): FieldMask?).
    api(libs.bundles.protobuf)

    // The test fixtures use (gnostic.openapi.v3.property), whose classes http-model vendors.
    testImplementation(project(":http-model"))
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

ktlint {
    version.set(libs.versions.ktlint)
    filter {
        // Skip generated code. A glob can't match it: each generated dir is its own source root.
        exclude { it.file.path.contains("${File.separator}generated${File.separator}") }
    }
}
