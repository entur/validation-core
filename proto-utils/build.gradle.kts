description = "Domain-agnostic proto/HTTP plumbing (update masks, protobuf-JSON codecs, " +
    "ProblemDetail error mapping) shared by validation-platform services."

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

    // Message/FieldMask/Descriptors/FieldBehavior appear directly in this module's public
    // signatures (e.g. requireIdentifierMatchesPath, parseUpdateMask(): FieldMask?), so `api` rather
    // than `implementation` - consumers need these types on their own compile classpath.
    api(libs.bundles.protobuf)
    // ProblemDetail/Failure/FieldViolation appear in ApiExceptionHandler's public method signatures.
    api(project(":http-model"))
    // Only ProtoJsonCodecConfig (WebFlux codecs) and ApiExceptionHandler (@RestControllerAdvice)
    // need Spring - the Exposed-specific consumer, exposed-utils, depends on this separately for
    // its own withETag/PagingSupport uses of ResponseEntity/ObjectMapper.
    api("org.springframework.boot:spring-boot-starter-webflux")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// This module has no src/main/proto - only a test-fixtures .proto (used by DescriptorCacheTest &
// co. to exercise real generated Message types), handled by the entur.test-proto-module convention
// plugin applied above. exposed-utils has its own, independent copy of the same shared message
// shapes (Widget/WidgetStatus/Part/Detail/Note) rather than depending on this one - see its own
// build.gradle.kts. See entur.test-proto-module's own KDoc for why it's generated via buf like
// every other module, yet listed in the root buf.yaml workspace with a pared-down lint/breaking
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
