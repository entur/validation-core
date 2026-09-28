description = "Domain-agnostic proto/HTTP plumbing (ETag concurrency, update masks, cursor paging, " +
    "protobuf-JSON codecs, ProblemDetail error mapping) shared by validation-platform services."

plugins {
    // Opens @Configuration/@RestControllerAdvice classes (Kotlin classes are final by default,
    // which breaks Spring's proxying of them) - see ProtoJsonCodecConfig/ApiExceptionHandler.
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.ktlint)
    id("entur.test-proto-module")
}

dependencies {
    // Scope application BOMs to application/test classpaths, not tool configurations like ktlint -
    // same reasoning as consumers of this module (e.g. kittum's own root build.gradle.kts).
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.kotlin.bom))
    implementation(platform(libs.exposed.bom))

    // Message/FieldMask/Descriptors/FieldBehavior appear directly in this module's public
    // signatures (e.g. withETag<T : Message>, parseUpdateMask(): FieldMask?), so `api` rather than
    // `implementation` - consumers need these types on their own compile classpath.
    api(libs.bundles.protobuf)
    // ETagSupport.sqlExpression()'s Expression/QueryBuilder/TextColumnType - must stay byte-for-byte
    // in sync with the JVM-side hash in the same file (see its own KDoc), so it isn't split out.
    api(libs.exposed.core)
    // ProblemDetail/Failure/FieldViolation appear in ApiExceptionHandler's public method signatures.
    api(project(":http-model"))
    // ObjectMapper appears directly in PagingSupport's public signatures (nextCursor/decodeCursor) -
    // same reasoning as the other `api` deps above.
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

// This module has no src/main/proto - only a test-fixtures .proto (used by ConcurrencySupportTest
// & co. to exercise real generated Message types), handled by the entur.test-proto-module
// convention plugin applied above. See its own KDoc for why it's generated via buf like every
// other module, yet listed in the root buf.yaml workspace with a pared-down lint/breaking
// override instead of the full rules a published contract gets.

kotlin {
    compilerOptions {
        // Only this module talks to Spring's JSR-305-annotated APIs (WebFlux codecs, the
        // exception handler) from Kotlin, so this is scoped here rather than set repo-wide.
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

ktlint {
    version.set(libs.versions.ktlint)
    filter {
        // protoc-generated Kotlin (build/generated/sources/proto/...) isn't our code to lint - path-based
        // (not a "**/generated/**" glob) since each generated source dir is itself the pattern-matching
        // root, so "generated" never appears in the relative path glob excludes would match against.
        exclude { it.file.path.contains("${File.separator}generated${File.separator}") }
    }
}
