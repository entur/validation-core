description = "Spring WebFlux/Security support (OpenAPI spec serving, content negotiation, " +
    "audience-validation, method-security configuration, protobuf-JSON codecs and ProblemDetail " +
    "error mapping) shared by validation-platform services."

plugins {
    // Opens @Configuration/@RestController/@RestControllerAdvice classes (Kotlin classes are final
    // by default, which breaks Spring's proxying of them).
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.ktlint)
    id("entur.test-proto-module")
}

dependencies {
    // Scope application BOMs to application/test classpaths, not tool configurations like ktlint -
    // same reasoning as proto-utils.
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.kotlin.bom))

    // ResourceLoader/RestController/ResponseEntity/MediaType, and the suspend handler's coroutine support.
    api("org.springframework.boot:spring-boot-starter-webflux")
    // MethodSecurityConfig's @EnableReactiveMethodSecurity.
    implementation("org.springframework.security:spring-security-config")
    // YamlJsonConverter's YAML<->JSON conversion.
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    // AudienceValidationGuard's constructor takes EnturAuthProperties directly, and
    // DefaultAuthProviders resolves each tenant's trusted issuers - both appear in its public
    // signature, so `api` rather than `implementation`.
    api(libs.entur.oidc.rs.webflux.config)
    api(project(":http-model"))
    api(project(":proto-utils"))

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        // Only this module talks to Spring's JSR-305-annotated APIs from Kotlin, so this is scoped
        // here rather than set repo-wide.
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

ktlint {
    version.set(libs.versions.ktlint)
    filter {
        // protoc-generated Kotlin (build/generated/sources/proto/...) isn't our code to lint.
        exclude { it.file.path.contains("${File.separator}generated${File.separator}") }
    }
}
