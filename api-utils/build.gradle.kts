description = "Spring WebFlux/Security support (OpenAPI spec serving, content negotiation, " +
    "audience-validation, method-security configuration, protobuf-JSON codecs and ProblemDetail " +
    "error mapping) shared by validation-platform services."

plugins {
    // Opens Spring-annotated classes so Spring can proxy them.
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.ktlint)
    id("entur.test-proto-module")
}

dependencies {
    // BOMs as `implementation`, so they don't leak into tool configurations like ktlint.
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.kotlin.bom))

    api("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.security:spring-security-config")
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    // `api`: EnturAuthProperties is in AudienceValidationGuard's public constructor.
    api(libs.entur.oidc.rs.webflux.config)
    api(project(":http-model"))
    api(project(":proto-utils"))

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

ktlint {
    version.set(libs.versions.ktlint)
    filter {
        // Skip generated code. A glob can't match it: each generated dir is its own source root.
        exclude { it.file.path.contains("${File.separator}generated${File.separator}") }
    }
}
