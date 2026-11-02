description = "Persistence support for the Exposed SQL library (keyset-paging predicates and " +
    "SortableProperty column bindings, ETag concurrency) built on proto-utils's domain-agnostic plumbing."

plugins {
    alias(libs.plugins.ktlint)
    id("entur.test-proto-module")
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.kotlin.bom))
    implementation(platform(libs.exposed.bom))

    // Exposed, proto-utils, spring-web and Jackson types all appear in this module's public API.
    api(libs.exposed.core)
    api(project(":proto-utils"))
    // Not the WebFlux starter: consumers pick their own web stack.
    api("org.springframework:spring-web")
    api("tools.jackson.core:jackson-databind")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Postgres-backed tests check the SQL expressions against their JVM counterparts.
    // Exposed R2DBC needs a newer kotlinx-coroutines than Spring Boot's.
    testImplementation(platform(libs.kotlinx.coroutines.bom))
    testImplementation(libs.exposed.r2dbc)
    testImplementation(libs.exposed.java.time)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.postgresql:r2dbc-postgresql")
}

ktlint {
    version.set(libs.versions.ktlint)
    filter {
        // Skip generated code. A glob can't match it: each generated dir is its own source root.
        exclude { it.file.path.contains("${File.separator}generated${File.separator}") }
    }
}
