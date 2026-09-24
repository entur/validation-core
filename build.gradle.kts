plugins {
    kotlin("jvm") version "2.4.20" apply false
}

allprojects {
    group = "no.entur.validation"

    repositories {
        mavenCentral()
    }
}

val protoDirs = provider { subprojects.map { it.projectDir.resolve("src/main/proto") } }

// Root-level: `buf format` rewrites the whole workspace in one shot.
val bufFormat = tasks.register<Exec>("bufFormat") {
    description = "Formats all .proto files in place via `buf format -w`."
    group = "build"
    inputs.files(protoDirs)
    inputs.files("buf.yaml", "buf.lock")
    commandLine(miseExecutable("buf"), "format", "-w")
}

// Root-level: `buf lint` checks the whole workspace in one shot.
val bufLint = tasks.register<Exec>("bufLint") {
    description = "Lints all .proto files via `buf lint`."
    group = "verification"
    dependsOn(bufFormat)
    inputs.files(protoDirs)
    inputs.files("buf.yaml", "buf.lock")
    commandLine(miseExecutable("buf"), "lint")
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
            vendor = JvmVendorSpec.ADOPTIUM
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    tasks.matching { it.name == "bufGenerate" }.configureEach { dependsOn(bufFormat) }
    tasks.matching { it.name == "check" }.configureEach { dependsOn(bufLint) }

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
            }
        }
        repositories {
            maven {
                name = "EnturJFrog"
                url = uri("https://entur2.jfrog.io/entur2/entur-release-standard/")
                credentials {
                    username = (findProperty("enturArtifactoryUser") as String?) ?: System.getenv("ARTIFACTORY_AUTH_USER")
                    password = (findProperty("enturArtifactoryPassword") as String?) ?: System.getenv("ARTIFACTORY_AUTH_TOKEN")
                }
            }
        }
    }
}
