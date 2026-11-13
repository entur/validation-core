description = "GCS URI parsing (gs://bucket/object[#generation] via BlobId) and generation-polling " +
    "(readIfChanged/changed/listGenerations) shared by validation-platform services."

plugins {
    alias(libs.plugins.ktlint)
}

dependencies {
    // BOM as `implementation`, so it doesn't leak into tool configurations like ktlint. kafka-utils
    // declares spring-kafka the same way, so its version doesn't reach here without this too.
    implementation(platform(libs.spring.boot.bom))

    // In this module's public API (parseGcsUri/readIfChanged/changed/listGenerations all use it).
    api(libs.google.cloud.storage)
    implementation(project(":kafka-utils"))
    // Used directly (not just transitively via kafka-utils' own `api`), since this module's own
    // code calls it -- a consumer that doesn't otherwise depend on kafka-utils still needs it.
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

ktlint {
    version.set(libs.versions.ktlint)
}
