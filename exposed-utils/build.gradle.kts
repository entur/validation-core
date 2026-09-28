description = "JetBrains Exposed-specific persistence support (keyset-paging predicates and " +
    "SortableProperty column bindings, ETag concurrency) built on proto-utils's domain-agnostic plumbing."

plugins {
    alias(libs.plugins.ktlint)
    id("entur.test-proto-module")
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.kotlin.bom))
    implementation(platform(libs.exposed.bom))

    // ConcurrencySupport.etagSqlExpression()'s Expression/QueryBuilder/TextColumnType - must stay
    // byte-for-byte in sync with the JVM-side hash in the same file (see its own KDoc), so it isn't
    // split out - and SortableProperty/PagingSupport's Column/Op, appear directly in this module's
    // public signatures, so `api` rather than `implementation`.
    api(libs.exposed.core)
    // ConcurrencySupport (PreconditionRequiredException/ResourceFields/toProtoTimestamp), and the
    // protobuf Message/Descriptors/FieldMask types this module's own signatures use, all come from
    // proto-utils.
    api(project(":proto-utils"))
    // ObjectMapper appears directly in PagingSupport's public signatures; ResponseEntity/HttpStatus
    // in ConcurrencySupport.withETag.
    api("org.springframework.boot:spring-boot-starter-webflux")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // ConcurrencySupportPostgresTest/PagingSupportPostgresTest: a real, throwaway Postgres
    // (Testcontainers) to cross-check the SQL-side expressions against their JVM-side counterparts.
    // Exposed R2DBC requires a newer kotlinx-coroutines than Spring Boot's baseline.
    testImplementation(platform(libs.kotlinx.coroutines.bom))
    testImplementation(libs.exposed.r2dbc)
    testImplementation(libs.exposed.java.time)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.postgresql:r2dbc-postgresql")
}

// This module has no src/main/proto - only its own test-fixtures .proto (used by
// ConcurrencySupportTest/PagingSupportTest & co. to exercise real generated Message types),
// handled by the entur.test-proto-module convention plugin applied above. It's a separate,
// independent copy of proto-utils's own test-fixtures proto rather than a shared dependency on it
// - see proto-utils/build.gradle.kts's own comment.

ktlint {
    version.set(libs.versions.ktlint)
    filter {
        // protoc-generated Kotlin (build/generated/sources/proto/...) isn't our code to lint - path-based
        // (not a "**/generated/**" glob) since each generated source dir is itself the pattern-matching
        // root, so "generated" never appears in the relative path glob excludes would match against.
        exclude { it.file.path.contains("${File.separator}generated${File.separator}") }
    }
}
