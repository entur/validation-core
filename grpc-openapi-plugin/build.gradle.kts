import java.util.Properties

plugins {
    `kotlin-dsl`
    `maven-publish`
}

group = "no.entur.validation"

// The same version as the schema modules. CD rewrites the version line of the root gradle.properties
// (entur/gha-artifactory's maven-publish) and this build, being separate, doesn't read that file on
// its own - so read it here, unless -Pversion says otherwise.
version =
    providers
        .gradleProperty("version")
        .orElse(
            providers.provider {
                Properties().apply { file("../gradle.properties").inputStream().use { load(it) } }.getProperty("version")
            },
        ).get()

description = "Gradle plugin no.entur.validation.grpc-openapi: buf codegen plus the augmented OpenAPI spec for a gRPC module with google.api.http bindings."

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.protobuf:protobuf-java:4.36.2")
    implementation("org.yaml:snakeyaml:2.7")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.4.21")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("fixtureDir", layout.projectDirectory.dir("src/test/resources/fixture").asFile.absolutePath)
    // The plugin is a separate build, so http-model is reached by path, not project reference.
    systemProperty("httpModelProtoDir", layout.projectDirectory.dir("../http-model/src/main/proto").asFile.canonicalPath)
}

publishing {
    repositories {
        maven {
            name = "EnturJFrog"
            url = uri("https://entur2.jfrog.io/entur2/entur-release-standard/")
            credentials {
                username = (findProperty("entur_artifactory_user") as String?) ?: System.getenv("ARTIFACTORY_AUTH_USER")
                password = (findProperty("entur_artifactory_password") as String?) ?: System.getenv("ARTIFACTORY_AUTH_TOKEN")
            }
        }
    }
}
